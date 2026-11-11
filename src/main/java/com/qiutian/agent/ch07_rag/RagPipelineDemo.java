package com.qiutian.agent.ch07_rag;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.ArrayList;
import java.util.List;

/**
 * ch07-rag · 端到端 RAG 流水线：分割 → 向量化 → 检索 → 拼上下文 → 生成
 *
 * 对应 lesson01（RAG 核心原理：索引-检索-生成三阶段）。
 *
 * 设计：索引链路（分割/向量化/入库）离线、可重复；检索链路在线、低延迟。
 *   - 分割/检索用纯 Java + InMemoryEmbeddingStore，无密钥也能跑通骨架（向量用离线哈希向量）。
 *   - 检测到 OPENAI_API_KEY 时，用真实 OpenAiEmbeddingModel 替换向量部分（key 守卫）。
 *   - 生成阶段必须真实模型，故整体用 ensureKey 守卫，缺 key 仅演示到"拼好上下文"为止。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx（生成步骤需要）。
 */
public class RagPipelineDemo {

    /** 让 LLM 基于检索到的资料作答的接口（AiServices 绑定 ChatModel，不接 retriever，ctx 手动注入）。 */
    interface Generator {
        String answer(String questionAndContext);
    }

    public static void main(String[] args) {
        Demo.banner("RagPipelineDemo · 端到端 RAG：分割 → 向量化 → 检索 → 拼上下文 → 生成");

        // 第 0 步：一份"私有知识"（模拟解析后的纯文本，真实来自 Tika/PDFBox）
        String document = "第一章 退换货政策。\n\n"
                + "客户在签收商品之日起七日内，可申请无理由退货。退货商品应当保持吊牌完好、不影响二次销售。\n\n"
                + "退款申请提交后，系统将在二十四小时内完成审核。审核通过后，款项将原路退回至付款账户，一般一至三个工作日到账。\n\n"
                + "若商品存在质量问题，客户可凭订单号申请换货。换货流程需先将瑕疵商品寄回仓库，仓库验收无误后发出新品。\n\n"
                + "本店支持七天无理由退换货，详情见退换货政策第三章。";

        // 第 1 步：分割（复用 DocumentSplitterDemo 的递归字符分割）
        Demo.note("第 1 步：递归字符分割（chunkSize=120, overlap=30）。");
        List<String> chunks = DocumentSplitterDemo.recursiveSplit(document, 120, 30,
                new String[]{"\n\n", "\n", "。", "！", "？", "；", "，", "、", ""});
        Demo.note("切出 " + chunks.size() + " 个 chunk。");

        // 第 2 步：向量化并入库（InMemoryEmbeddingStore）
        // 离线骨架用 Env.get 判断是否可用真实模型，避免误报"需要密钥"；
        // 真正的"必须密钥"场景（生成步骤）才用 Demo.ensureKey 守卫。
        OpenAiEmbeddingModel realEmbedding = null;
        if (Env.get("OPENAI_API_KEY") != null) {
            realEmbedding = OpenAiEmbeddingModel.builder()
                    .apiKey(Env.get("OPENAI_API_KEY"))
                    .modelName("text-embedding-3-small")
                    .build();
        }
        Demo.note("第 2 步：向量化并写入 InMemoryEmbeddingStore"
                + (realEmbedding == null ? "（无密钥，使用离线哈希向量演示）" : "（使用真实 OpenAI embedding）") + "。");
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String cid = "kb#c" + i;
            Metadata md = Metadata.from("cite_id", cid);
            TextSegment seg = TextSegment.from(chunks.get(i), md);
            segments.add(seg);
            Embedding emb = (realEmbedding == null)
                    ? Embedding.from(HybridSearchDemo.embedOffline(chunks.get(i)))
                    : realEmbedding.embed(chunks.get(i)).content();
            store.add(emb, seg);
        }
        Demo.note("已入库 " + segments.size() + " 个向量片段。");

        // 第 3 步：检索（在线，向量召回 Top-3）
        String question = "退款一般多久能到账？";
        Demo.note("第 3 步：检索 —— 把问题向量化，从库中召回最相关的 Top-3 片段。");
        Embedding qEmb = (realEmbedding == null)
                ? Embedding.from(HybridSearchDemo.embedOffline(question))
                : realEmbedding.embed(question).content();
        List<EmbeddingMatch<TextSegment>> hits = store.search(
                EmbeddingSearchRequest.builder().queryEmbedding(qEmb).maxResults(3).minScore(0.0).build()
        ).matches();

        // 第 4 步：拼装上下文（带 cite_id，便于溯源）
        Demo.note("第 4 步：拼装上下文（保留 cite_id 用于引用溯源）。");
        StringBuilder context = new StringBuilder();
        for (EmbeddingMatch<TextSegment> m : hits) {
            String cid = m.embedded().metadata().getString("cite_id");
            context.append("[").append(cid).append("] ").append(m.embedded().text()).append("\n");
        }
        System.out.println("── 检索到的上下文 ──");
        System.out.println(context);

        // 第 5 步：生成（必须真实模型，key 守卫）
        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            Demo.note("第 5 步：生成需要 OPENAI_API_KEY，当前缺失，仅演示到『拼好上下文』为止（代码已编译通过）。");
            return;
        }
        Demo.note("第 5 步：把『上下文 + 问题』交给 LLM，并强制『仅依据资料 + 标注来源』以抑制幻觉。");
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.0)
                .build();
        Generator generator = AiServices.builder(Generator.class)
                .chatModel(chatModel)
                .build();
        String prompt = "你是一个严谨的客服助手。请仅根据下面的【参考资料】回答，不要编造；"
                + "在答案末尾用 [来源: cite_id] 标注依据。\n\n"
                + "【参考资料】\n" + context + "\n"
                + "【用户问题】\n" + question;
        String answer = generator.answer(prompt);
        System.out.println("── 生成答案 ──");
        System.out.println(answer);

        Demo.note("小结：RAG = 索引(分割+向量化+入库,离线) + 检索(在线) + 生成(带上下文+约束)。"
                + "生产用 pgvector/Milvus 替换 InMemory，并用 QuestionAnswerAdvisor / RetrievalAugmentor 串链。");
    }
}
