#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
finetune_lora.py —— 第18章第2节「LoRA / QLoRA 与领域适配」配套参考代码

【这是 Python 参考实现，不参与 Maven 编译】
本章是加分项，微调/推理优化生态在 Python 侧最成熟（HuggingFace transformers / peft）。
对 Java 工程师的意义：你大概率不亲手跑训练，但要懂 LoRA 是什么、adapter 产物长啥样、
怎么被部署/调用（见 inference_optimize.py 与 README）。

本文件给出一个"可 py_compile 通过、逻辑完整、注释详尽"的 QLoRA 微调脚本骨架：
  - argparse 参数解析（模型、数据、LoRA 超参、量化精度）
  - 数据集加载占位（datasets.load_dataset，对话格式 → 单条文本）
  - LoraConfig（peft 真实 API）
  - Trainer（transformers 真实 API）做监督微调
  - 保存 adapter；以及可选的 merge_and_unload 合并权重

⚠️ 实际训练需要 GPU + 真实数据（几百~几千条指令样本，格式见 lesson02 的 JSONL）。
   本脚本可直接 `py_compile` 通过；真正 `python3 finetune_lora.py --data tickets.jsonl`
   需在有 torch/CUDA 的环境里执行，否则会在加载模型时报错（属预期）。
"""

import argparse
import json

import torch
from datasets import load_dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    DataCollatorForLanguageModeling,
    Trainer,
    TrainingArguments,
)
from peft import (
    LoraConfig,
    PeftModel,
    TaskType,
    get_peft_model,
    prepare_model_for_kbit_training,
)


# ---------------------------------------------------------------------------
# 1. 命令行参数解析：把"训什么/怎么训"全部外置，方便复用与 CI 化
# ---------------------------------------------------------------------------
def parse_args():
    p = argparse.ArgumentParser(
        description="QLoRA / LoRA 微调骨架（基于 HuggingFace transformers + peft）"
    )
    # 模型与数据
    p.add_argument("--model_id", type=str,
                   default="Qwen/Qwen2.5-7B-Instruct",
                   help="底座模型 HuggingFace 仓库名或本地路径")
    p.add_argument("--data", type=str, required=True,
                   help="训练数据 JSONL 路径，每行一个 {\"messages\":[...]}（见 lesson02 格式）")
    p.add_argument("--output_dir", type=str,
                   default="./qwen-lora-adapter",
                   help="adapter 保存目录（仅几 MB~几十 MB，非整模型）")

    # 量化精度：QLoRA=4bit；显存充足可设 0 走 FP16 底座
    p.add_argument("--load_in_4bit", type=int, default=1,
                   help="1=QLoRA(4bit NF4 底座)，0=LoRA(FP16 底座)")

    # LoRA 超参（呼应 lesson02 超参调优地图）
    p.add_argument("--lora_r", type=int, default=16,
                   help="秩 rank：容量上限，常用 8/16/32，先小后大")
    p.add_argument("--lora_alpha", type=int, default=32,
                   help="缩放因子，通常 2×r")
    p.add_argument("--lora_dropout", type=float, default=0.05,
                   help="防过拟合 dropout")
    p.add_argument("--target_modules", type=str,
                   default="q_proj,k_proj,v_proj,o_proj,gate_proj,up_proj,down_proj",
                   help="注入 LoRA 的模块名，逗号分隔")

    # 训练超参
    p.add_argument("--epochs", type=int, default=3)
    p.add_argument("--per_device_batch", type=int, default=2)
    p.add_argument("--grad_accum", type=int, default=4,
                   help="梯度累积步数，等效增大 batch")
    p.add_argument("--lr", type=float, default=2e-4,
                   help="LoRA 学习率通常 1e-4~2e-4，远大于全量微调")
    p.add_argument("--max_seq_len", type=int, default=1024)
    p.add_argument("--merge", type=int, default=0,
                   help="1=训练后把 LoRA 合并进底座权重并单独保存（merge_and_unload）")
    return p.parse_args()


# ---------------------------------------------------------------------------
# 2. 数据准备：把 messages 对话格式压成单条训练文本（SFT 标准做法）
#    lesson02 的 JSONL 形如：
#    {"messages":[{"role":"system","content":"..."},
#                {"role":"user","content":"..."},
#                {"role":"assistant","content":"..."}]}
# ---------------------------------------------------------------------------
def build_tokenize(tokenizer, max_seq_len):
    # 用模型自带的对话模板更稳妥；这里用通用 chat template（多数 Instruct 模型支持）
    def preprocess(examples):
        # examples["messages"] 可能是单条或多条（batched），统一成 list
        msgs_list = examples["messages"]
        if not isinstance(msgs_list, list):
            msgs_list = [msgs_list]
        texts = []
        for msgs in msgs_list:
            # apply_chat_template 会按模型规范插入 <|im_start|> 等特殊 token
            text = tokenizer.apply_chat_template(
                msgs, tokenize=False, add_generation_prompt=False
            )
            texts.append(text)
        # 直接返回文本，再统一 tokenize（避免 map 时 shape 不一致）
        return {"text": texts}

    def tokenize(examples):
        return tokenizer(
            examples["text"],
            truncation=True,
            max_length=max_seq_len,
            padding="max_length",
        )

    return preprocess, tokenize


# ---------------------------------------------------------------------------
# 3. 主流程
# ---------------------------------------------------------------------------
def main():
    args = parse_args()

    # ---- 3.1 量化配置（QLoRA 核心：4-bit NF4 + 双重量化）----
    if args.load_in_4bit:
        bnb_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",            # NormalFloat4，针对正态分布权重定制
            bnb_4bit_compute_dtype=torch.float16,
            bnb_4bit_use_double_quant=True,       # 双重量化：连量化常数也量化，再省显存
        )
        quant_kwargs = {"quantization_config": bnb_config}
    else:
        bnb_config = None
        quant_kwargs = {}

    # ---- 3.2 加载底座与 tokenizer ----
    # device_map="auto" 让 transformers/accelerate 自动把层分到可用 GPU/CPU
    tokenizer = AutoTokenizer.from_pretrained(args.model_id)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token  # 因果 LM 常用 eos 作 pad

    model = AutoModelForCausalLM.from_pretrained(
        args.model_id,
        device_map="auto",
        **quant_kwargs,
    )

    # 量化模型（4-bit/8-bit）需先 prepare 才能正常反向传播 / 梯度检查点
    if args.load_in_4bit:
        model = prepare_model_for_kbit_training(model)

    # ---- 3.3 LoRA 配置：只训低秩"补丁"，底座冻结 ----
    target_modules = [m.strip() for m in args.target_modules.split(",") if m.strip()]
    lora_config = LoraConfig(
        task_type=TaskType.CAUSAL_LM,            # 因果语言模型
        r=args.lora_r,                           # 秩
        lora_alpha=args.lora_alpha,              # 缩放
        lora_dropout=args.lora_dropout,
        target_modules=target_modules,           # 注意力 + FFN 投影层
        bias="none",
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()
    # 输出类似：trainable params: 20,369,408 || all params: 7,030,000,000 || trainable%: 0.29%

    # ---- 3.4 加载并预处理训练数据（占位：替换 --data 为真实数据即可）----
    # 真实场景：从业务日志/数据库抽样本（Java 工程师最熟的活，见 lesson01）
    raw = load_dataset("json", data_files=args.data)["train"]
    preprocess, tokenize = build_tokenize(tokenizer, args.max_seq_len)
    raw = raw.map(preprocess, batched=False)
    tokenized = raw.map(tokenize, batched=True, remove_columns=["text", "messages"])

    # 因果 LM 用语言建模数据收集器：把输入本身当标签，shift 后计算 next-token loss
    data_collator = DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False)

    # ---- 3.5 训练参数 ----
    training_args = TrainingArguments(
        output_dir=args.output_dir,
        per_device_train_batch_size=args.per_device_batch,
        gradient_accumulation_steps=args.grad_accum,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        fp16=True,                              # 混合精度；Ampere+ 可改 bf16=True
        logging_steps=5,
        save_strategy="epoch",
        optim="paged_adamw_8bit",               # 分页优化器，防止显存峰值 OOM
        report_to="none",                       # 不接 wandb 等，纯本地训练
    )

    # ---- 3.6 Trainer 监督微调 ----
    # 注：课程原文用了 trl.SFTTrainer（更省心）；此处用 transformers.Trainer
    #     以零额外依赖达成同等效果，API 真实存在。
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized,
        data_collator=data_collator,
        tokenizer=tokenizer,
    )
    trainer.train()

    # ---- 3.7 保存：只存 LoRA adapter（轻量、可逆、可回滚）----
    trainer.save_model(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)
    print(f"[完成] LoRA adapter 已保存到 {args.output_dir}（仅几 MB~几十 MB）")

    # ---- 3.8 可选：合并权重后再单独保存（推理零额外开销）----
    if args.merge:
        # 把 LoRA 补丁合并回底座，得到一份"已微调"的完整权重
        merged = PeftModel.from_pretrained(model, args.output_dir)
        merged = merged.merge_and_unload()      # peft 真实 API：合并并卸载 adapter
        merged_dir = args.output_dir + "-merged"
        merged.save_pretrained(merged_dir)
        tokenizer.save_pretrained(merged_dir)
        print(f"[完成] 合并后权重已保存到 {merged_dir}（可直接 load 使用）")


# 让脚本在 `python3 -m py_compile` 时不会执行训练逻辑
if __name__ == "__main__":
    main()
