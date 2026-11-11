package com.qiutian.agent.ch07_rag;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;

/**
 * ch07-rag · 文档处理：两种文本分割（Chunking）策略
 *
 * 对应 lesson02（文档处理技术：文本分割策略、元数据提取）。
 *
 * 本节课核心：RAG 效果 70% 取决于索引阶段，而切分是索引阶段的第一战场。
 * 这里给出两种纯 Java、可离线运行的实现：
 *   1) 递归字符分割（Recursive Character Splitting）：按"结构边界 → 字符"优先级递归切，
 *      相邻块保留 overlap 重叠，避免边界语义断裂（LangChain4j 的 DocumentSplitters.recursive 等价实现）。
 *   2) 语义分块（按标点断句 + 长度上限）：尊重句子边界，避免把一个完整句子腰斩。
 *
 * 说明：本 Demo 直接处理纯文本。生产环境中纯文本来自"文档解析"环节（见下方注释中的
 * Apache Tika / PDFBox / POI 代码片段），解析出干净文本后再做切分。
 */
public class DocumentSplitterDemo {

    /** 中文优先的分隔符优先级，与 lesson02 强调的 separators 完全一致。
     *  注意：默认英文分隔符按空格断词对中文几乎无效，必须把中文标点放进去。 */
    private static final String[] RECURSIVE_SEPS = {"\n\n", "\n", "。", "！", "？", "；", "，", "、", ""};

    /** 句子级分隔符，用于语义分块时按句断。 */
    private static final String SENTENCE_SEPS = "。！？；\n";

    public static void main(String[] args) {
        Demo.banner("DocumentSplitterDemo · 递归字符分割 + 语义(标点/长度)分块");

        // 一份模拟的《退换货政策》纯文本（真实场景由 Tika/PDFBox 解析得到）
        String doc = "第一章 退换货政策。\n\n"
                + "客户在签收商品之日起七日内，可申请无理由退货。退货商品应当保持吊牌完好、不影响二次销售。\n\n"
                + "退款申请提交后，系统将在二十四小时内完成审核。审核通过后，款项将原路退回至付款账户，一般一至三个工作日到账。\n\n"
                + "若商品存在质量问题，客户可凭订单号申请换货。换货流程需先将瑕疵商品寄回仓库，仓库验收无误后发出新品。\n\n"
                + "本政策最终解释权归本店所有。";

        Demo.note("策略一：递归字符分割（chunkSize=120, overlap=30）");
        List<String> recursive = recursiveSplit(doc, 120, 30, RECURSIVE_SEPS);
        printChunks("递归字符分割", recursive);

        Demo.note("策略二：语义分块（按标点断句 + 长度上限 120，不腰斩句子）");
        List<String> semantic = semanticSplit(doc, 120);
        printChunks("语义分块", semantic);

        Demo.note("对比结论：递归字符分割更可控（尊重结构 + 重叠保连续），是最常用的默认打底策略；"
                + "语义分块更贴合主题边界，适合主题跳跃明显的长文档。生产常『递归打底 + 长文档叠加语义』。");
        Demo.note("提示：真实文本来自文档解析，见本文件末尾注释中的 Tika / PDFBox / POI 代码片段。"
                + "切分后还应抽取元数据（source/page/category）用于过滤与溯源，详见 lesson02 第五节。");
    }

    // ===================== 策略一：递归字符分割 =====================

    /**
     * 递归字符分割：先按高优先级分隔符切，若某块仍超 chunkSize 则降级到更细分隔符继续切，
     * 直到最细的""（字符级）硬切。最后把相邻小块贪心合并到 chunkSize，并保留 overlap 重叠。
     */
    public static List<String> recursiveSplit(String text, int chunkSize, int overlap, String[] seps) {
        // 1) 递归切成"原子块"（每块 <= chunkSize）
        List<String> pieces = splitRecursive(text, chunkSize, seps, 0);
        // 2) 贪心合并相邻块，达到 chunkSize 就断开，并在新块头部带上上一块末尾的 overlap 字符
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            String p = piece.trim();
            if (cur.length() > 0 && cur.length() + p.length() > chunkSize) {
                chunks.add(cur.toString().trim());
                // 保留上一块末尾 overlap 个字符，保证边界语义连续
                String tail = cur.length() <= overlap ? cur.toString() : cur.substring(cur.length() - overlap);
                cur.setLength(0);
                cur.append(tail);
            }
            if (cur.length() > 0) {
                cur.append("\n");
            }
            cur.append(p);
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString().trim());
        }
        return chunks;
    }

    private static List<String> splitRecursive(String text, int chunkSize, String[] seps, int idx) {
        if (idx >= seps.length || text.length() <= chunkSize) {
            return hardSplit(text, chunkSize); // 没有更多分隔符或已足够小，按字符硬切
        }
        List<String> parts = splitBy(text, seps[idx]); // 按当前分隔符切（保留分隔符在块尾）
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.length() <= chunkSize) {
                out.add(part);
            } else {
                out.addAll(splitRecursive(part, chunkSize, seps, idx + 1)); // 降级到更细分隔符
            }
        }
        return out;
    }

    /** 按单个字符分隔符切分，分隔符保留在每块末尾（中文标点断句更自然）。 */
    private static List<String> splitBy(String text, String sep) {
        List<String> out = new ArrayList<>();
        if (sep.isEmpty()) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (sep.indexOf(c) >= 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 按 chunkSize 硬切（兜底）。 */
    private static List<String> hardSplit(String text, int chunkSize) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        for (int i = 0; i < text.length(); i += chunkSize) {
            out.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return out;
    }

    // ===================== 策略二：语义分块（按标点/长度） =====================

    /**
     * 语义分块：先按句子分隔符切成句子，再贪心合并到 maxLen。
     * 因为以"句子"为最小单元，不会把一句话从中间腰斩，更贴近语义边界。
     */
    public static List<String> semanticSplit(String text, int maxLen) {
        List<String> sentences = splitBy(text, SENTENCE_SEPS);
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (s.isBlank()) {
                continue;
            }
            String sent = s.trim();
            if (cur.length() > 0 && cur.length() + sent.length() > maxLen) {
                chunks.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(" ");
            }
            cur.append(sent);
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString().trim());
        }
        return chunks;
    }

    // ===================== 工具 =====================

    private static void printChunks(String title, List<String> chunks) {
        System.out.println("  [" + title + "] 共 " + chunks.size() + " 块：");
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            int len = c.length();
            String preview = c.length() > 36 ? c.substring(0, 36) + "…" : c;
            System.out.println("    #" + i + " [" + len + "字] " + preview.replace("\n", " "));
        }
    }

    /*
     * ============================================================
     * 附录：生产环境"文档解析"代码片段（pom 未引入依赖，仅作注释参考）
     * ——对应 lesson06（Apache Tika / PDFBox / POI / EasyExcel）
     * ============================================================
     *
     * // 1) Apache Tika：一行代码自动探测类型并解析任意格式（PDF/Word/Excel/PPT）
     * // 依赖：org.apache.tika:tika-core、org.apache.tika:tika-parsers
     * // Tika tika = new Tika();
     * // String text = tika.parseToString(new FileInputStream(new File("/data/kb/退换货政策2024.pdf")));
     * // Metadata md = new Metadata();
     * // tika.parse(new FileInputStream(file), md); // md 里含作者/标题/页数
     *
     * // 2) PDFBox：PDF 精细控制（坐标、区域、加密、扫描件+OCR）
     * // 依赖：org.apache.pdfbox:pdfbox
     * // try (PDDocument doc = PDDocument.load(new File("/data/kb/合同模板.pdf"))) {
     * //     String full = new PDFTextStripper().getText(doc);
     * // }
     *
     * // 3) Apache POI：读 Word 保留段落与表格结构（结构化切分更优）
     * // 依赖：org.apache.poi:poi-ooxml
     * // try (XWPFDocument doc = new XWPFDocument(new FileInputStream("/data/kb/员工手册.docx"))) {
     * //     for (XWPFParagraph p : doc.getParagraphs()) { p.getText() 保留标题层级 }
     * // }
     *
     * 解析得到纯文本 text 后，再调用本 Demo 的 recursiveSplit / semanticSplit 即可进入索引链路。
     * 大 Excel 请用 EasyExcel 流式读取（逐行不 OOM），每行作为一个 chunk 并写入列元数据。
     * ============================================================
     */
}
