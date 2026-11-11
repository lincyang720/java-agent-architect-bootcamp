# 第十八章第2节：微调方法：LoRA / QLoRA 与领域适配

大家好，我是秋天。

上一节我们定了"什么时候该微调"。这一节动手——但别慌，我不要求你成为 PyTorch 专家。我要让你**看懂、能跑、能讲清原理与成本**的，是当下最主流的高效微调方法：**LoRA 和 QLoRA**。它们把"微调一个 7B/13B 模型"从"要 8 张 A100"降到"一张消费级显卡就能玩"，是 2024–2026 微调民主化的功臣。

这节课讲什么、学完掌握什么：

- 讲什么：① 全量微调 vs 参数高效微调（PEFT）的本质区别；② LoRA 原理（低秩分解，只训少量参数）；③ QLoRA（4-bit 量化 + LoRA，显存再砍一截）；④ 用 HuggingFace PEFT 跑一个真实可运行的 LoRA 训练示例；⑤ 对 Java 工程师的意义（你不用训，但要会接、会管、会算账）。
- 学完掌握：你理解"为什么 LoRA 又快又省还不丢通用能力"；你能跑通一个 PEFT 训练脚本；你知道微调产物（adapter）怎么部署、怎么被你的 Java Agent 调用。

<callout emoji="💡"><p>招聘映射：JD 加分项明确写"熟悉 LoRA/QLoRA、PEFT、领域适配"。面试官不指望你手搓训练框架，但他要你"懂原理、懂成本、懂怎么把微调模型接进生产"。这一节给的就是这个——原理 + 可运行代码 + 工程落地视角，三件套齐活。</p></callout>

---

## 一、先搞懂：全量微调为什么又贵又危险

传统"全量微调（Full Fine-tuning）"是**把模型的全部参数都更新一遍**：

```text
全量微调：
- 7B 模型 ≈ 70亿参数，FP16 下权重 ≈ 14GB
- 训练时要存：权重 + 梯度 + 优化器状态
  → 显存 ≈ 权重(14G) + 梯度(14G) + 优化器(≈2~3×权重) ≈ 60GB+
- 结论：单张 A100(80G) 勉强，小模型都吃力，大模型要多卡
```

更糟的是"灾难性遗忘"风险高——全量改，容易把通用能力也洗掉。所以全量微调是"大厂基础模型团队"的活，绝大多数应用层团队不该碰。

<callout emoji="⚠️"><p>反模式："我们有 7B 模型，直接全量微调吧"。结果显存爆了、训了两天、通用能力掉了、还不好回滚。应用层微调的正确打开方式是 PEFT——只动一小撮参数，又快又省又可逆。</p></callout>

---

## 二、LoRA 原理：低秩分解，只训"补丁"

LoRA（Low-Rank Adaptation）的核心洞察：**模型适配新任务，不需要改全部权重，只需要学一个"低秩补丁"**。

数学上，把某个权重矩阵 W（维度 d×d）的更新 ΔW，分解为两个小矩阵的乘积：

```
原始：  y = W · x          （W 冻结，不训）
LoRA：  y = W · x + ΔW · x = W·x + B·A·x
                              └─┬─┘
                          B(d×r) × A(r×d)，r << d（秩很小，如 r=8）

训练时：只训 A 和 B（参数量 ≈ 2×d×r），W 完全冻结
推理时：可把 B·A 合并回 W（无额外延迟），或单独加载 adapter
```

关键收益：

```text
LoRA 相比全量微调：
- 可训练参数：从 100% 降到 ~0.1%-1%（7B 模型约训几百万参数）
- 显存：省 2-3 倍（不存优化器状态/梯度给冻结层）
- 可逆：adapter 是独立文件，不要就卸，原模型毫发无损（防遗忘）
- 可叠加：格式 LoRA + 话术 LoRA 分开训，按需组合
```

<callout emoji="✅"><p>一句话记住 LoRA：原模型当"冷冻地基"，只学一个轻量"补丁"（adapter）。地基不动 → 不遗忘；补丁很小 → 省显存、好回滚；补丁独立 → 可组合。这就是它又便宜又安全的根本。</p></callout>

---

## 三、QLoRA：把显存再砍一截（4-bit 量化）

QLoRA = **Quantized LoRA**：在 LoRA 基础上，把"冻结的底座模型"用 4-bit 量化（NF4）压缩，训练时再反量化计算。这样显存进一步暴跌：

```text
7B 模型显存占用对比（训练时）：
全量微调(FP16)        ≈ 60-80 GB   （多卡）
LoRA(FP16 底座)       ≈ 16-24 GB   （单张 24G 卡可玩）
QLoRA(4-bit 底座)     ≈ 6-8 GB     （单张 8-12G 消费卡如 3060/4070 可玩）
```

代价：4-bit 有极轻微精度损失，但对大多数领域适配任务几乎无感。QLoRA 让"个人/小团队微调"成为现实。

```text
QLoRA 三件套（技术点，理解即可）：
1. 4-bit NormalFloat(NF4)：针对正态分布权重量身定制的量化，比 INT4 更优
2. 双重量化(Double Quantization)：连量化常数也再量化，再省显存
3. 分页优化器(Paged Optimizers)：用 CPU 内存当分页，防显存峰值 OOM
```

<callout emoji="💡"><p>选型口诀：显存够（≥24G）→ 用 LoRA(FP16 底座)，精度最好；显存紧（≤12G）→ 用 QLoRA(4-bit)，精度微调可接受。绝大多数企业/个人场景，QLoRA 是性价比之王。</p></callout>

---

## 四、实战：用 PEFT 跑一个 LoRA 训练（Python，可运行）

下面是**真实可运行**的 LoRA 微调骨架（基于 HuggingFace `transformers` + `peft` + `datasets` + `bitsandbytes`）。以"工单结构化抽取"（lesson01 说的"最划算场景"）为例。

环境依赖（`requirements.txt`）：

```text
transformers>=4.44.0
peft>=0.12.0
datasets>=2.20.0
bitsandbytes>=0.43.0
accelerate>=0.33.0
torch>=2.3.0
```

### 4.1 数据准备（JSONL，沿用 lesson01 格式）

```jsonl
{"messages":[
  {"role":"system","content":"你是工单结构化抽取器，输出JSON{order_id,reason,level}。"},
  {"role":"user","content":"订单A123因物流延迟用户投诉，紧急"},
  {"role":"assistant","content":"{\"order_id\":\"A123\",\"reason\":\"物流延迟\",\"level\":\"紧急\"}"}
]}
{"messages":[
  {"role":"system","content":"你是工单结构化抽取器，输出JSON{order_id,reason,level}。"},
  {"role":"user","content":"订单B456质量问题申请退货，普通"},
  {"role":"assistant","content":"{\"order_id\":\"B456\",\"reason\":\"质量问题\",\"level\":\"普通\"}"}
]}
```

### 4.2 训练脚本（QLoRA + PEFT，含中文注释）

```python
# train_lora.py  —— QLoRA 微调 7B 模型做工单结构化
import torch
from datasets import load_dataset
from transformers import (
    AutoModelForCausalLM, AutoTokenizer,
    BitsAndBytesConfig, TrainingArguments,
)
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from trl import SFTTrainer   # 监督微调高层封装，更简单

# ---------- 1. 4-bit 量化配置（QLoRA 核心） ----------
bnb_config = BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_quant_type="nf4",            # NF4 量化
    bnb_4bit_compute_dtype=torch.float16,
    bnb_4bit_use_double_quant=True,       # 双重量化，再省显存
)

model_id = "Qwen/Qwen2.5-7B-Instruct"    # 底座模型，按需替换
tokenizer = AutoTokenizer.from_pretrained(model_id)

model = AutoModelForCausalLM.from_pretrained(
    model_id,
    quantization_config=bnb_config,
    device_map="auto",
)
model = prepare_model_for_kbit_training(model)   # 让冻结层兼容梯度检查点

# ---------- 2. LoRA 配置（只训低秩补丁） ----------
lora_config = LoraConfig(
    r=16,                      # 秩：越大容量越高、显存略增（8/16/32 常用）
    lora_alpha=32,             # 缩放因子，通常 2×r
    lora_dropout=0.05,         # 防过拟合
    target_modules=["q_proj","k_proj","v_proj","o_proj",
                    "gate_proj","up_proj","down_proj"],  # 注意力+FFN
    bias="none",
    task_type="CAUSAL_LM",
)

model = get_peft_model(model, lora_config)
model.print_trainable_parameters()
# 输出类似：trainable params: 20,369,408 || all params: 7,030,000,000 || trainable%: 0.29%

# ---------- 3. 加载数据（对话格式） ----------
data = load_dataset("json", data_files="tickets.jsonl")["train"]

def format_example(ex):
    # 把 messages 转成单条文本（SFT 标准做法）
    text = ""
    for m in ex["messages"]:
        text += f"<|im_start|>{m['role']}\n{m['content']}<|im_end|>\n"
    return {"text": text}

data = data.map(format_example)

# ---------- 4. 训练参数 ----------
training_args = TrainingArguments(
    output_dir="./qwen-lora-tickets",
    per_device_train_batch_size=2,
    gradient_accumulation_steps=4,
    num_train_epochs=3,
    learning_rate=2e-4,           # LoRA 学习率通常比全量微调大
    fp16=True,
    logging_steps=5,
    save_strategy="epoch",
    optim="paged_adamw_8bit",    # 分页优化器，防 OOM
)

trainer = SFTTrainer(
    model=model,
    train_dataset=data,
    tokenizer=tokenizer,
    args=training_args,
    max_seq_length=1024,
)

trainer.train()
trainer.save_model("./qwen-lora-tickets-final")   # 只保存 LoRA adapter（很小，几 MB~几十 MB）
```

<callout emoji="✅"><p>注意 `save_model` 只保存 LoRA adapter——通常只有几 MB 到几十 MB，而不是整个 14GB 模型。这就是 LoRA 的可逆性：原模型原封不动，你手里就多了一个轻量"补丁文件"。部署时底座 + adapter 组合即可。</p></callout>

### 4.3 推理时加载 adapter

```python
# infer_lora.py —— 加载底座 + LoRA adapter 做推理
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

base = AutoModelForCausalLM.from_pretrained("Qwen/Qwen2.5-7B-Instruct",
                                             device_map="auto")
tok = AutoTokenizer.from_pretrained("Qwen/Qwen2.5-7B-Instruct")

# 把 LoRA 补丁挂上底座
model = PeftModel.from_pretrained(base, "./qwen-lora-tickets-final")
model = model.merge_and_unload()   # 可选：合并进权重，推理零额外开销

prompt = "订单C789物流损坏要求赔偿，紧急"
inputs = tok(f"<|im_start|>system\n你是工单结构化抽取器，输出JSON{{order_id,reason,level}}。<|im_end|>\n<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n", return_tensors="pt").to(model.device)
out = model.generate(**inputs, max_new_tokens=128)
print(tok.decode(out[0], skip_special_tokens=True))
# 期望输出：{"order_id":"C789","reason":"物流损坏","level":"紧急"}
```

---

## 五、原理再深挖：为什么 LoRA 不丢通用能力

很多同学担心"训了领域数据，模型会不会忘本"。LoRA 的设计天然缓解这点：

```text
LoRA 防遗忘的机制：
1. 底座 W 完全冻结 → 通用知识原样保留，物理上改不了
2. 只学一个"补丁" B·A → 新任务是"在通用能力上叠加领域风格"
3. adapter 可卸 → 不想要了，卸掉回到原模型，零损失
4. 可加通用样本(10-20%)进训练集 → 进一步稳住通用能力

对比全量微调：W 全改 → 新任务数据主导 → 容易洗掉通用 → 遗忘
```

<callout emoji="💡"><p>工程实践：训练集里混入 10%-20% 的通用对话样本（如 ShareGPT 类），能有效防止 LoRA 把模型"带偏"。评测时务必双线监控（新任务 + 通用能力），这就是 lesson01 说的"评测闭环"。</p></callout>

---

## 六、成本与产出：一次微调到底花多少

结合 lesson01 的成本框架，把这次训练量化：

```text
本次 QLoRA 训练（7B, 3000 条, 3 epoch）：
- 算力：单卡 24G(A100/3090 都行)，约 1-3 小时
- 云成本：约 ¥200-800（按需）
- 数据：若人工标注 3000 条 ≈ 视领域 ¥5万-30万（最大头）
- 产出：adapter 文件 ≈ 20-80 MB，可部署、可回滚、可组合
对比全量微调：多卡 + 数天 + 易遗忘 → QLoRA 省一个数量级
```

<callout emoji="⚠️"><p>成本大头从来不是 GPU，是**数据**。很多团队算错了账，以为"微调就花点算力"，结果卡在标注上。养成习惯：先评估数据可得性再决定微调，别等 GPU 跑起来才发现没数据。</p></callout>

---

## 七、对 Java 工程师的意义：你不用训，但要会"接"

这是关键一节——你是 Java 工程师，大概率**不亲手跑训练脚本**，但你必须懂怎么把微调产物用起来。职责边界：

```text
算法/数据同学负责：         你（Java 工程师）负责：
- 准备训练数据              - 从业务系统抽取/清洗样本（你最熟）
- 跑 LoRA/QLoRA 训练        - 把 adapter 部署成推理服务（lesson03）
- 评测模型质量             - 用 OpenAI 兼容接口接进 Agent（lesson03）
                           - 成本治理/模型路由（lesson04）
                           - 版本管理与回滚（adapter 版本）
```

你的核心交付：把 `./qwen-lora-tickets-final` 这个 adapter，变成 Java Agent 能调用的一个"模型端点"。这通常通过两种方式：

```
方式A：vLLM + LoRA（推荐，lesson03 细讲）
   vLLM 加载底座，动态挂多个 LoRA adapter
   Java 用 OpenAI 兼容 HTTP 调用，header 指定用哪个 LoRA

方式B：云微调平台（百炼/千帆/OpenAI）
   平台托管训练+部署，给你一个 model id
   Java 用 DashScope/OpenAI SDK 直接调用，像调普通模型
```

<callout emoji="✅"><p>你的价值不在"会写训练脚本"，而在"让微调模型安全、低成本、可回滚地进生产"。这一节你懂了 LoRA 是什么、adapter 怎么部署、成本几何，再配合 lesson03 的推理优化，就能端到端交付一个微调模型服务——这才是 Java Agent 架构师该有的全链路视角。</p></callout>

---

## 八、常见坑位清单

```text
坑1：rank 设太大（如 r=128） → 显存涨、过拟合、失去 LoRA 轻量优势
     解：从 r=8/16 起，评测不够再加

坑2：学习率照搬全量微调(2e-5) → LoRA 几乎不收敛
     解：LoRA 通常 1e-4 ~ 2e-4

坑3：训练集只有正例/单一模式 → 模型只认这类，泛化崩
     解：覆盖边界 case + 混入通用样本

坑4：底座和 adapter 版本不匹配 → 加载报错
     解：adapter 绑定 base model 版本，纳入版本管理

坑5：忘了评测双线 → 上线才发现通用能力掉了
     解：lesson01 的评测闭环必须跑
```

---

## 八（续）、量化方法全景：INT8 / FP8 / GPTQ / AWQ

LoRA 训的是"补丁"，但**底座模型本身的精度**也决定显存和速度。推理优化（lesson03 详讲）里量化是关键，这里先建立概念，和 LoRA 区分开：

```text
维度             训练时量化(QLoRA)      推理时量化(部署优化)
─────────────────────────────────────────────────────────────
目的             省训练显存             省部署显存/提吞吐/降延迟
代表             NF4(4-bit)             INT8 / FP8 / GPTQ / AWQ
影响            微调质量(轻微)         推理质量(轻微)+速度

四种推理量化对比：
- INT8：对称/非对称整数量化，简单通用，精度损失中等，几乎零成本
- FP8：H100/新卡原生支持，精度优于 INT8，需硬件支持
- GPTQ：基于二阶信息的权重量化，4-bit 效果好，需校准集，推理稍慢
- AWQ：激活感知权重量化，只保护"重要权重"，4-bit 精度常优于 GPTQ，
       且推理快（对 vLLM 友好，lesson03 主推）
```

<callout emoji="💡"><p>别混淆：QLoRA 的 4-bit 是"训练时为了省显存"，GPTQ/AWQ 的 4-bit 是"部署时为了省显存提速"。一个发生在训练侧，一个发生在推理侧。两者可以叠加：QLoRA(4-bit) 训出来的 adapter，部署时底座还能再 AWQ(4-bit)。</p></callout>

## 八（再续）、LoRA 超参调优：从哪下手

很多人第一次跑 LoRA 就闷头训，结果不好又不知道调啥。给你一张调优地图：

```text
超参              常用值         调大效果                  调小效果
──────────────────────────────────────────────────────────────
r(秩)             8/16/32       容量高、拟合强、显存涨     轻量、防过拟合
lora_alpha        2×r           对补丁的缩放，影响学习强度  反之
learning_rate     1e-4~2e-4    收敛快但易不稳             稳但慢
lora_dropout      0.05~0.1      防过拟合                  更易过拟合
target_modules    注意力+FFN     覆盖广、容量大            精简、省资源
num_epochs        2~5           学更透但易过拟合          欠拟合
```

调优顺序建议：先固定 `r=16, lr=2e-4`，看训练 loss 是否下降、评测是否提升；不够再调 `r` 和 `epochs`；出现过拟合就加 `lora_dropout` 和通用样本比例。

<callout emoji="⚠️"><p>新手最常犯：直接 `r=128` 想"训得全一点"——结果显存爆、还过拟合，失去 LoRA 轻量优势。记住 LoRA 的精髓是"小补丁解决特定任务"，不是越大越好。先小后大，用评测说话。</p></callout>

## 八（补）、多 LoRA 服务架构：一个底座挂多个补丁

企业里通常不会只训一个 LoRA。不同业务线各有一个，怎么高效服务？答案是**一个底座 + 多个 adapter 动态挂载**（vLLM 原生支持，lesson03 细讲）：

```text
                    ┌─────────────────────────────────┐
请求(指定 LoRA)      │        vLLM 推理服务            │
  ┌─────────────▶   │  底座模型(如 Qwen2.5-7B)        │
  │                 │   ├─ adapter: 工单结构化(lora_t) │
  │  "用 lora_t"    │   ├─ adapter: 客服话术(lora_c)  │
  │                 │   └─ adapter: 报告生成(lora_r)  │
  └─────────────▶   │  按请求 header 动态加载对应补丁 │
                    └─────────────────────────────────┘
        Java Agent 调用时在请求里指定 lor a 名即可
```

好处：底座只占一份显存，N 个 LoRA 共享；新增业务线只训一个小 adapter，不必重新部署大模型。这是平台工程（lesson04）里"多租户/多业务模型管理"的底层能力。

## 八（末）、LoRA 与 RAG 怎么组合

回到 lesson01 的决策：知识用 RAG，风格/约束用 LoRA。两者组合是生产常态：

```text
组合形态：底座(通用) + LoRA(领域风格/格式) + RAG(私域知识检索)
例子：医疗客服
  - RAG 检索最新临床指南（知识，实时、可溯源）
  - LoRA 固化"医生口吻 + 必含免责声明"（风格/强约束）
  - Prompt 给角色与当前任务说明
  → 知识新、风格稳、合规硬，三者各司其职
```

<callout emoji="✅"><p>组合时的工程顺序：先 RAG 解决知识，再 LoRA 固化风格/约束，最后用评测（第十六章）量化"加了 LoRA 是否更好"。别一上来就全上，否则出问题你分不清是哪个环节的贡献。</p></callout>

---

## 八（训）、训练监控：怎么判断训得好不好

跑训练不是"等它跑完"，要会读信号。关键监控项：

```text
训练时应盯的信号：
- train_loss：持续下降且趋于平缓 = 正常；震荡不降 = 学习率/数据有问题
- eval_loss（验证集）：比 train_loss 高很多 = 过拟合；两者都降 = 健康
- grad_norm：突然爆大(NaN) = 梯度爆炸，学习率太大或数据有脏样本
- 可训练参数占比：确认只训了 LoRA（如 0.29%），不是全量

早停信号：
- eval_loss 连续多轮不降反升 → 过拟合，停训
- train_loss 远低于 eval_loss → 数据泄漏或严重过拟合
```

```python
# 在 TrainingArguments 里打开评估与早停（配合验证集）
training_args = TrainingArguments(
    ...,
    eval_strategy="epoch",          # 每轮评估
    load_best_model_at_end=True,    # 保留最优
    metric_for_best_model="eval_loss",
    # 早停需 EarlyStoppingCallback（transformers 内置）
)
```

<callout emoji="⚠️"><p>新手常"盲训到底"，结果过拟合了还不知道。养成习惯：开 eval、看 eval_loss 拐点、必要时早停。一次无效训练烧的是真金白银的 GPU 和标注数据，监控能帮你及时止损。</p></callout>

---

## 九、面试自测

1. **LoRA 原理？** —— 冻结底座 W，只训低秩补丁 B·A（r<<d），参数量降两个数量级。
2. **QLoRA 又省了啥？** —— 把冻结底座 4-bit(NF4) 量化，显存再砍，消费卡可玩。
3. **为什么 LoRA 不遗忘？** —— 底座物理冻结、补丁可逆可卸、可混通用样本。
4. **产出是什么？** —— 几 MB~几十 MB 的 adapter 文件，非整模型；可组合可回滚。
5. **Java 工程师职责？** —— 不一定要训，但要会部署 adapter、接进 Agent、管成本与版本。

---

## 十、小结

这一节我们把 LoRA/QLoRA 从概念打到了可运行：

1. **全量微调又贵又险**：显存爆炸 + 易遗忘，应用层不该碰。
2. **LoRA 原理**：冻结底座，只训低秩补丁 B·A，可训练参数降到 ~0.3%，可逆、可组合、不遗忘。
3. **QLoRA**：4-bit(NF4) 量化底座 + 双重量化 + 分页优化，显存再砍，消费卡可玩。
4. **实战**：用 PEFT + TRL 跑通 QLoRA 训练，产出仅几 MB 的 adapter；推理时挂载/合并。
5. **防遗忘**：底座冻结 + 混通用样本 + 评测双线（呼应 lesson01）。
6. **Java 工程师定位**：不必亲手训，但要会部署 adapter（vLLM/云平台）、接进 Agent、管成本与版本——端到端交付才是架构师视角。

下一节（lesson03）我们聊最后一关：**推理优化**——量化（INT8/FP8/GPTQ/AWQ）、KV Cache、vLLM，以及如何用 Java 低成本调用微调好的模型。

---

## 延伸阅读 / 引用文档

1. HuggingFace PEFT 官方文档（LoRA/QLoRA）：https://huggingface.co/docs/peft/
2. QLoRA 原论文（4-bit 微调）：https://arxiv.org/abs/2305.14314
3. LoRA 原论文（Low-Rank Adaptation）：https://arxiv.org/abs/2106.09685
4. TRL 监督微调（SFTTrainer）文档：https://huggingface.co/docs/trl/sft_trainer
5. bitsandbytes（4-bit 量化）文档：https://huggingface.co/docs/bitsandbytes/
6. 阿里云百炼微调平台（企业落地参考）：https://help.aliyun.com/zh/model-studio/
