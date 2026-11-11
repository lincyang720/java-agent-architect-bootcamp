# 第18章「模型微调与推理优化」Python 参考代码

> 本章是《Java 版 Agent 架构师训练营》的加分项。微调 / 推理优化的生态在 Python 侧最成熟，
> 因此本章示例以 Python 为主；**这些文件不参与 Maven 编译**，仅作为参考实现。
> 对 Java 工程师的价值不在于"亲手写训练脚本"，而在于"懂原理、懂成本、懂怎么把微调/量化后的模型
> 通过标准接口接进你的 Java Agent"——这一点贯穿每个文件的注释。

## 文件与章节对应

| 文件 | 对应章节 | 内容 |
| --- | --- | --- |
| `finetune_lora.py` | 第18章 第2节《LoRA / QLoRA 与领域适配》 | QLoRA/LoRA 微调骨架：`argparse` 参数解析、数据集加载占位、`LoraConfig`、`Trainer` 训练、保存 adapter、`merge_and_unload` 合并权重 |
| `inference_optimize.py` | 第18章 第3节《推理优化：量化、KV Cache、vLLM》 | INT8/FP8 量化加载、`KV Cache` 原理注释、`vLLM` 的 `LLM(...)`+`SamplingParams`+`generate` 高吞吐推理片段 |
| `requirements.txt` | 第18章全部 | 依赖清单（transformers / peft / datasets / bitsandbytes / accelerate / vllm 及合理版本区间） |

> 第18章 第1节《什么时候需要微调》是决策框架（无代码），其"决策表 / 数据形态 / 成本账"
> 在 `finetune_lora.py` 的数据格式注释与 README「对 Java 工程师的意义」中均有呼应。

## 安装依赖

```bash
cd course/code/ch18_finetune/python
pip install -r requirements.txt
```

> 注意：
> - `torch` 需与本机 CUDA 版本匹配，建议在 GPU 环境安装（CPU 仅能跑通语法，无法真正训练/推理）。
> - `vllm` 需要较新的 Linux + CUDA 环境；macOS / 纯 CPU 无法运行其推理引擎。

## 语法检查（必须）

这两个 `.py` 文件需通过 `python3 -m py_compile` 语法检查（**不依赖 GPU/数据**）：

```bash
python3 -m py_compile finetune_lora.py inference_optimize.py
# 若无报错即 0 语法错误；也可用通配：
# python3 -m py_compile *.py
```

## 运行方式

### 1) 微调（finetune_lora.py）—— 对应第2节

```bash
# 准备数据：JSONL，每行 {"messages":[{role,content}, ...]}，格式见第2节
# 然后运行（需 GPU）：
python3 finetune_lora.py \
    --data tickets.jsonl \
    --model_id Qwen/Qwen2.5-7B-Instruct \
    --load_in_4bit 1 \          # QLoRA（4-bit 底座，消费卡可玩）；显存足可设 0 走 FP16
    --lora_r 16 --lora_alpha 32 \
    --output_dir ./qwen-lora-adapter \
    --merge 1                   # 训练后把 adapter 合并进底座权重单独保存
```

- 不传 `--data` 会报错（数据必需）；训练本身需要 GPU + 真实样本（几百~几千条指令对）。
- 产出是**仅几 MB~几十 MB 的 LoRA adapter**，而非整模型——可逆、可回滚、可组合。

### 2) 推理优化（inference_optimize.py）—— 对应第3节

```bash
# 量化加载推理（INT8 / NF4 / FP8）
python3 inference_optimize.py --mode int8 --prompt "订单A123物流延迟怎么办"

# vLLM 高吞吐批处理推理
python3 inference_optimize.py --mode vllm --prompt "订单A123物流延迟怎么办"
```

vLLM 也可直接启动 **OpenAI 兼容 HTTP 服务**（Java 侧调用入口）：

```bash
python -m vllm.entrypoints.openai.api_server \
    --model ./qwen25-7b-awq-4bit \
    --enable-lora \
    --lora-modules lora_t=./adapters/tickets \
    --port 8000
```

## 对 Java 工程师的意义（重点）

你是 Java 工程师，在微调/推理优化项目里的角色**不是亲手训练**，而是：

1. **数据管线**：从业务日志/数据库抽取、清洗训练样本（你最熟的系统）。
2. **接入层**：把微调好的 adapter 部署成推理服务（vLLM / 云微调平台）。
3. **OpenAI 兼容调用**：因为 vLLM / Ollama / 云端 API 都暴露 OpenAI 兼容接口，
   Java 侧的 LangChain4j / Spring AI / 原生 `HttpClient` **只需改 base-url 即可切换供应商**：

   ```yaml
   # Spring AI 示例：把 OpenAI base-url 指向你的 vLLM
   spring:
     ai:
       openai:
         base-url: http://${VLLM_HOST:localhost}:8000/v1
         api-key: ${VLLM_API_KEY:EMPTY}
         chat:
           options:
             model: qwen25-7b-awq-4bit
   ```

   ```java
   // 或 Java 17 HttpClient 直调 /v1/chat/completions，
   // 指定 LoRA 时加 header（如 X-Lora-Name: lora_t）即可。详见第3节课程原文。
   ```

4. **成本治理与版本管理**：模型路由（简单任务走小模型）、Prompt 缓存、adapter 版本与回滚、
   量化粒度按业务敏感度分级（核心业务留 INT8/FP16，边缘业务放 4-bit）。

> 架构纪律：**Java 业务进程永远不直接加载模型权重**，只通过 OpenAI 兼容 HTTP 消费模型能力。
> 这样量化策略、LoRA 组合、GPU 扩缩都可在推理层独立演进，业务代码零改动（关注点分离）。

## 关键 API 速查（均为真实存在的库/类）

- `peft.LoraConfig` / `get_peft_model` / `PeftModel.merge_and_unload`
- `transformers.Trainer` / `TrainingArguments` / `BitsAndBytesConfig` / `AutoModelForCausalLM`
- `datasets.load_dataset`
- `vllm.LLM` / `vllm.SamplingParams` / `llm.generate`
