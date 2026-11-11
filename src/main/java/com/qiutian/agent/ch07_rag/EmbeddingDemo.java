package com.qiutian.agent.ch07_rag;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.List;

/**
 * ch07-rag · Embedding 模型：文本向量化
 *
 * 对应 lesson03（Embedding 模型详解：文本向量化技术、主流模型选型）。
 *
 * 核心要点：
 *   - Embedding 把"离散文字"映射到"连续向量空间"，语义相近 → 向量空间里距离近。
 *   - 相似度首选余弦（方向敏感、长度无关）：归一化后点积 = 余弦，可加速 ANN 检索。
 *   - 中文/内网场景首推 BGE / GTE / M3E 本地部署；通用省心可选 OpenAI text-embedding-3。
 *   - 索引与检索必须用【同一个】embedding 模型，否则向量空间不一致，召回全错。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx（真实模型调用前必须 key 守卫）。
 */
public class EmbeddingDemo {

    public static void main(String[] args) {
        Demo.banner("EmbeddingDemo · OpenAiEmbeddingModel 文本向量化");

        // 编译安全铁律：所有需要真实模型的调用，必须先 ensureKey，缺 key 直接 return。
        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        Demo.note("构建 OpenAiEmbeddingModel（text-embedding-3-small，1536 维）。");
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("text-embedding-3-small")
                .build();

        Demo.note("对若干句子做向量化，观察维度与语义相似度。");
        String q1 = "如何申请退款";
        String q2 = "退款流程是什么";
        String q3 = "今天天气真好";

        Embedding e1 = embeddingModel.embed(q1).content();
        Embedding e2 = embeddingModel.embed(q2).content();
        Embedding e3 = embeddingModel.embed(q3).content();

        System.out.println("  向量维度: " + e1.dimension());
        float[] v1 = e1.vector();
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < Math.min(8, v1.length); i++) {
            head.append(String.format("%.4f ", v1[i]));
        }
        System.out.println("  \"" + q1 + "\" 前 8 维: " + head);

        // 余弦相似度（也可直接用本类的 cosineOffline 纯 Java 实现做对照）
        double simNear = cosineOffline(e1.vector(), e2.vector());
        double simFar = cosineOffline(e1.vector(), e3.vector());
        System.out.println("  近义句 \"" + q1 + "\" ↔ \"" + q2 + "\" 余弦相似度: " + String.format("%.4f", simNear));
        System.out.println("  无关句 \"" + q1 + "\" ↔ \"" + q3 + "\" 余弦相似度: " + String.format("%.4f", simFar));

        Demo.note("结论：语义相近的句子向量余弦接近 1，无关句子接近 0 —— 这正是向量检索能『语义匹配』的根基。");
        Demo.note("生产建议：入库前对 embedding 做 L2 归一化，使『余弦=点积』，可用 IVF+PQ 等量化索引把检索延迟压到毫秒级。");
        Demo.note("批量/异步向量化与限流：海量文档请分批并发 + 单批失败重试（见 lesson03 第八节），避免触发 RPM/TPM 限流。");
    }

    /** 纯 Java 余弦相似度实现（向量的方向夹角），用于离线对照与理解原理。 */
    public static double cosineOffline(float[] a, float[] b) {
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // 仅用于演示"批量向量化"的入参形态（避免未使用告警，方法本身完整可用）
    static List<Embedding> embedBatch(OpenAiEmbeddingModel model, List<String> texts) {
        return model.embedAll(texts.stream().map(dev.langchain4j.data.segment.TextSegment::from).toList()).content();
    }
}
