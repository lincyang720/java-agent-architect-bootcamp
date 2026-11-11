#!/usr/bin/env python3
"""RAGAS 风格离线评测 · 参考实现（Java 课程配套的 Python 参考，不影响 Maven 编译）。

对一个 (question, context, answer[, ground_truth]) 数据集计算：
    - faithfulness     答案是否完全基于给定上下文（不编造）
    - answer_relevancy 答案是否切题
    - context_recall   应检索到的事实是否都在 context 中

运行：
    pip install -r requirements.txt
    export OPENAI_API_KEY=sk-xxx
    python ragas_eval.py --input samples.jsonl --output report.json
    python ragas_eval.py --demo          # 不依赖密钥，查看指标说明
    python ragas_eval.py --help
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from typing import List, Optional


@dataclass
class EvalSample:
    question: str
    contexts: List[str]
    answer: str
    ground_truth: str = ""


def load_samples(path: str) -> List[EvalSample]:
    """从 JSONL 或 JSON 数组文件加载评测样本。"""
    samples: List[EvalSample] = []
    if path.endswith(".jsonl"):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                obj = json.loads(line)
                samples.append(_to_sample(obj))
    else:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        for obj in data:
            samples.append(_to_sample(obj))
    return samples


def _to_sample(obj: dict) -> EvalSample:
    contexts = obj.get("contexts", [])
    if isinstance(contexts, str):
        contexts = [contexts]
    return EvalSample(
        question=obj.get("question", ""),
        contexts=list(contexts),
        answer=obj.get("answer", ""),
        ground_truth=obj.get("ground_truth", ""),
    )


def run_ragas(samples: List[EvalSample], model: str, embeddings: str) -> dict:
    """调用 RAGAS 计算三项指标。需要安装 ragas + 设置 OPENAI_API_KEY。"""
    try:
        from datasets import Dataset
        from ragas import evaluate
        from ragas.metrics import faithfulness, answer_relevancy, context_recall
        from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    except ImportError as e:  # pragma: no cover
        raise SystemExit(
            "缺少依赖，请先执行：pip install -r requirements.txt\n原始错误：" + str(e)
        )

    judge_llm = ChatOpenAI(model=model, temperature=0)
    judge_emb = OpenAIEmbeddings(model=embeddings)
    data = Dataset.from_list(
        [
            {
                "question": s.question,
                "answer": s.answer,
                "contexts": s.contexts,
                "ground_truth": s.ground_truth,
            }
            for s in samples
        ]
    )
    result = evaluate(
        data,
        metrics=[faithfulness, answer_relevancy, context_recall],
        llm=judge_llm,
        embeddings=judge_emb,
    )
    # ragas 的 result 既支持 .to_pandas() 也支持 dict 取值，统一转 dict 方便落盘
    try:
        return result.to_dict()
    except Exception:  # pragma: no cover
        return {k: v for k, v in dict(result).items()}


def main() -> int:
    parser = argparse.ArgumentParser(
        description="RAGAS 风格离线评测：faithfulness / answer_relevancy / context_recall"
    )
    parser.add_argument(
        "--input", "-i", default="samples.jsonl",
        help="评测样本文件（JSONL 或 JSON 数组），每行含 question/contexts/answer/ground_truth",
    )
    parser.add_argument(
        "--output", "-o", default="report.json",
        help="评测报告输出路径（JSON）",
    )
    parser.add_argument("--model", default="gpt-4o-mini", help="裁判模型名")
    parser.add_argument("--embeddings", default="text-embedding-3-small", help="嵌入模型名")
    parser.add_argument(
        "--demo", action="store_true",
        help="不依赖 OPENAI_API_KEY，使用示例数据打印指标含义后退出",
    )
    args = parser.parse_args()

    if args.demo:
        print("Demo 模式：RAGAS 需要 OPENAI_API_KEY 才能真实打分。")
        print("三项指标含义：")
        print("  faithfulness     答案是否完全基于给定上下文（不编造）")
        print("  answer_relevancy 答案是否切题")
        print("  context_recall   应检索到的事实是否都在 context 中")
        print("示例样本（JSONL）：")
        print('  {"question":"退款多久到账?",'
              '"contexts":["退款审核通过后24小时内原路退回。"],'
              '"answer":"退款24小时内到账。",'
              '"ground_truth":"退款审核通过后24小时内原路退回。"}')
        return 0

    if not os.environ.get("OPENAI_API_KEY"):
        print("未设置 OPENAI_API_KEY，无法调用 RAGAS 真实打分。", file=sys.stderr)
        print("可用 --demo 查看指标说明，或设置密钥后重跑。", file=sys.stderr)
        return 2

    samples = load_samples(args.input)
    if not samples:
        print("未加载到任何评测样本：", args.input, file=sys.stderr)
        return 1

    report = run_ragas(samples, args.model, args.embeddings)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("评测完成，报告已写入：", args.output)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
