#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
inference_optimize.py —— 第18章第3节「推理优化：量化、KV Cache、vLLM」配套参考代码

【这是 Python 参考实现，不参与 Maven 编译】
微调/推理优化生态在 Python 侧最成熟。本文件演示：
  1) INT8 / FP8 量化加载（bitsandbytes 的 load_in_8bit / transformers 的量化配置）
  2) KV Cache 的来龙去脉（图文注释为主，理解显存与速度的命门）
  3) vLLM 高吞吐推理（LLM + SamplingParams + generate，真实可运行的最小片段）

对 Java 工程师的意义（贯穿全文注释）：
  推理优化发生在"服务端"（vLLM / 量化），Java 侧几乎零改动——
  只要 vLLM 暴露的是 OpenAI 兼容接口，把 base-url 指过去即可（见 README）。

⚠️ 量化加载与 vLLM 都需要 GPU + 已下载的模型权重；本文件可 `py_compile` 通过，
   真正运行需安装在 torch/CUDA 环境，否则会在模型加载处报错（属预期）。
"""

import argparse

import torch
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
)


# ===========================================================================
# 一、KV Cache 说明（注释为主，无需运行）
# ===========================================================================
"""
KV Cache（键值缓存）：自回归生成时，每生成一个 token 都要用到前面所有 token
的 Key/Value 矩阵。若每次重算，复杂度 O(N²)；推理框架把历史 K/V 缓存下来，
只算新 token，降到 O(N)，这就是"空间换时间"。

代价：KV Cache 随「上下文长度 × 并发数」线性增长显存：
    显存(KV) ≈ 2 × 层数 × 隐藏维度 × 序列长度 × 并发 × 每元素字节数
    → 长上下文 + 高并发时，KV Cache 可能比权重还大（这是最易被忽视的隐性成本）。

两条优化主路（呼应 lesson03）：
  - 省空间：KV 量化(INT8/FP8)、分组查询注意力 GQA（模型结构层已支持）
  - 省浪费：PagedAttention（vLLM 把 KV 像 OS 分页一样管理，利用率 ~20%→~90%）
"""


# ===========================================================================
# 二、INT8 / FP8 量化加载（推理侧，省显存、降成本）
# ===========================================================================
def load_quantized_model(model_id: str, mode: str = "int8"):
    """用 transformers + bitsandbytes 做 8-bit / 4-bit(NF4) 量化加载。

    mode:
      - "int8" : 对称/非对称整数量化，精度损失小、几乎零成本，部署首选
      - "fp8"  : H100 等原生支持，精度优于 INT8（需硬件；此处用 torch.float8 占位说明）
      - "nf4"  : 4-bit NF4（QLoRA 同款思路，也可用于纯推理省显存）
    """
    if mode == "int8":
        quant_cfg = BitsAndBytesConfig(
            load_in_8bit=True,                   # 推理侧 INT8 量化，API 真实存在
            bnb_8bit_compute_dtype=torch.float16,
        )
    elif mode == "nf4":
        quant_cfg = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.float16,
            bnb_4bit_use_double_quant=True,
        )
    elif mode == "fp8":
        # FP8 通常由 vLLM / 特定硬件路径处理；transformers 直接加载多走 compute dtype。
        # 这里用注释说明：H100 上可设 bnb 的 fp8 或交给 vLLM 的 --dtype fp8。
        quant_cfg = None  # 占位：实际 FP8 推理推荐走 vLLM（见下方 vLLM 段）
        print("[说明] FP8 推理建议直接用 vLLM 启动（--dtype fp8），硬件原生支持、精度更优。")
    else:
        raise ValueError(f"未知量化模式: {mode}")

    tokenizer = AutoTokenizer.from_pretrained(model_id)
    if quant_cfg is not None:
        model = AutoModelForCausalLM.from_pretrained(
            model_id,
            quantization_config=quant_cfg,
            device_map="auto",
        )
    else:
        # FP8 占位：退化为普通加载，真实场景由推理引擎处理精度
        model = AutoModelForCausalLM.from_pretrained(
            model_id, device_map="auto", torch_dtype=torch.float16
        )
    return model, tokenizer


def generate_demo(model, tokenizer, prompt: str, max_new_tokens: int = 128):
    """用量化后的模型做一条最小推理（HuggingFace 原生 generate）。"""
    # 以 Qwen 风格的 chat 模板拼装；不同模型模板不同，可用 tokenizer.apply_chat_template
    messages = [{"role": "user", "content": prompt}]
    text = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(text, return_tensors="pt").to(model.device)
    with torch.no_grad():                      # 推理不需要梯度，关掉省显存
        out = model.generate(**inputs, max_new_tokens=max_new_tokens)
    return tokenizer.decode(out[0], skip_special_tokens=True)


# ===========================================================================
# 三、vLLM 高吞吐推理（连续批处理 + PagedAttention + 多 LoRA）
# ===========================================================================
def vllm_high_throughput_demo(model_id: str, prompts: list):
    """vLLM 的 LLM 引擎做离线/批处理推理的最小可运行片段。

    真实 API（vllm>=0.5）：
        from vllm import LLM, SamplingParams
        llm = LLM(model=model_id)                 # 可选 tensor_parallel_size / gpu_memory_utilization
        params = SamplingParams(temperature=0.3, max_tokens=512)
        outputs = llm.generate(prompts, params)    # 连续批处理，自动攒批
    若需启动 OpenAI 兼容 HTTP 服务（Java 侧调用入口），改用命令行：
        python -m vllm.entrypoints.openai.api_server --model <model> --port 8000
    详见 README「Java 工程师如何调用」。
    """
    # 延迟导入：仅当真正进入该分支才需要 vllm（py_compile 不触发导入）
    from vllm import LLM, SamplingParams

    # 1) 构造引擎。量化(AWQ/GPTQ/FP8)在加载时由 vLLM 自动识别权重格式
    llm = LLM(
        model=model_id,
        tensor_parallel_size=1,        # 多卡用 >1
        gpu_memory_utilization=0.90,   # 交给 vLLM 管理 KV Cache 显存
        max_model_len=8192,            # 按真实上下文长度设，避免 KV 爆显存
        # enable_lora=True,            # 若要多 LoRA 动态挂载（见 lesson02 多 LoRA 架构）
    )

    # 2) 采样参数：温度、最大生成长度等
    sampling_params = SamplingParams(
        temperature=0.3,
        top_p=0.9,
        max_tokens=512,
    )

    # 3) 生成：内部连续批处理，数个请求一起进，吞吐数倍于原生推理
    outputs = llm.generate(prompts, sampling_params)

    # 4) 取结果
    results = []
    for o in outputs:
        results.append(o.outputs[0].text)   # vLLM 输出结构：RequestOutput.outputs[].text
    return results


# ===========================================================================
# 四、命令行入口（演示两种推理路径）
# ===========================================================================
def main():
    ap = argparse.ArgumentParser(description="推理优化演示：量化加载 + vLLM 高吞吐")
    ap.add_argument("--model_id", default="Qwen/Qwen2.5-7B-Instruct")
    ap.add_argument("--mode", choices=["int8", "nf4", "fp8", "vllm"], default="int8",
                    help="推理路径：int8/nf4/fp8 量化加载，或 vllm 高吞吐")
    ap.add_argument("--prompt", default="订单A123因物流延迟用户投诉，请结构化抽取为JSON。")
    args = ap.parse_args()

    if args.mode == "vllm":
        out = vllm_high_throughput_demo(args.model_id, [args.prompt])
        print(out[0])
    else:
        model, tok = load_quantized_model(args.model_id, args.mode)
        print(generate_demo(model, tok, args.prompt))


if __name__ == "__main__":
    main()
