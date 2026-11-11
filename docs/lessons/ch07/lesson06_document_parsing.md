# 第七章第六节：【新增】文档解析技术：Apache Tika / PDFBox / POI / EasyExcel 实战

大家好，我是秋天。

前面讲的切分、Embedding，都是建立在"我已经拿到干净的纯文本"这个假设上。但现实是：知识库里 80% 的文档是 PDF、Word、Excel、PPT——**把这些都正确解析成文本，本身就是一门苦活**。一个扫描版 PDF 解析成一堆乱码，后面切得再好也白搭。

这节课是 V2 新增的实战章节，直接上手四个企业级文档解析主力库：**Apache Tika（万能解析器）、PDFBox（PDF 专精）、POI（Office 全家桶）、EasyExcel（大 Excel 救星）**。全是 Java 原生，不用调 Python。

学完这节课，你能掌握：

1. **Apache Tika 为什么是"一站式"解析首选**，一个 API 吃遍 PDF/Word/Excel/PPT。
2. **PDFBox 处理扫描版 PDF、提取表格和坐标**（Tika 做不到的细活）。
3. **POI 读 Word 的段落/表格结构**，以及 **EasyExcel 流式读百万行 Excel** 为什么比 POI 省内存。

<callout emoji="💡"><p>招聘映射：JD 里明确列了"Apache Tika / PDFBox / POI / EasyExcel"。这是"文档解析"这条能力的硬技能点，面试官会直接让你写"怎么读一个 200MB 的 Excel 不 OOM"——这节课就是标准答案。</p></callout>

---

## 一、文档解析在 RAG 链路里的位置

```
原始文件 (PDF/Word/Excel/PPT/扫描件)
        │
        ▼
 【文档解析 / lesson06 重点】
   提取文本 + 结构 + 表格 + 元数据
        │  干净的纯文本 + 结构化片段
        ▼
   切分(chunking) → Embedding → 入库   (lesson02/03)
```

解析错了，后面全错。先给一张工具选型地图：

```
文件类型          推荐工具              理由
──────────────────────────────────────────────────────
任意格式(快速)     Apache Tika          自动探测类型，一行代码出文本
PDF(精细控制)      PDFBox               提取坐标/表格/注解，处理扫描件+OCR
Word/Excel/PPT     Apache POI           读段落/单元格/样式，结构最全
超大 Excel(>10万行) EasyExcel           流式读，内存不爆
扫描版 PDF         PDFBox + Tesseract   先 OCR 再抽取
```

---

## 二、Apache Tika：一行代码吃遍所有格式

Tika 的核心是**自动类型探测 + 统一解析接口**。你不用关心文件是啥，扔进去就出文本。

### 2.1 基础用法

```java
// ch07-rag/src/main/java/demo/parse/TikaDemo.java
package demo.parse;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import java.io.File;
import java.io.FileInputStream;

public class TikaDemo {
    public static void main(String[] args) throws Exception {
        Tika tika = new Tika();
        File file = new File("/data/kb/退换货政策2024.pdf");

        // 1) 自动探测 MIME 类型（pdf / docx / xlsx ...）
        String mime = tika.detect(file);
        System.out.println("文件类型: " + mime);  // application/pdf

        // 2) 一行解析出纯文本
        String text = tika.parseToString(new FileInputStream(file));
        System.out.println("解析字符数: " + text.length());

        // 3) 顺带提取元数据（作者、标题、页数）
        Metadata md = new Metadata();
        tika.parse(new FileInputStream(file), md);
        for (String name : md.names()) {
            System.out.println(name + " = " + md.get(name));
        }
    }
}
```

### 2.2 在索引 pipeline 里用 Tika 做统一加载器

```java
// ch07-rag/src/main/java/demo/parse/TikaLoader.java
package demo.parse;

import org.apache.tika.Tika;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/** 用 Tika 把任意格式文档统一转成 LangChain4j Document */
public class TikaLoader {
    private static final Tika TIKA = new Tika();

    public static List<Document> loadDirectory(String dir) throws Exception {
        List<Document> docs = new ArrayList<>();
        File root = new File(dir);
        for (File f : root.listFiles()) {
            if (f.isFile()) {
                String text = TIKA.parseToString(new FileInputStream(f));
                Metadata md = new Metadata();
                md.put("source", f.getName());
                md.put("size", String.valueOf(f.length()));
                docs.add(Document.from(text, md));
            }
        }
        return docs;
    }

    public static void main(String[] args) throws Exception {
        List<Document> docs = loadDirectory("/data/kb");
        System.out.println("加载文档数: " + docs.size());
    }
}
```

对应 Maven 依赖：

```xml
<!-- ch07-rag/pom.xml 片段 -->
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-core</artifactId>
    <version>2.9.2</version>
</dependency>
<dependency>
    <groupId>org.apache.tika</groupId>
    <artifactId>tika-parsers</artifactId>
    <version>2.9.2</version>
</dependency>
```

<callout emoji="✅"><p>Tika 的杀手锏是"零配置统一入口"。知识库里 PDF/Word/Excel 混着放时，与其为每个格式写一套加载器，不如全丢给 Tika，它内部自动路由到对应的 parser。新人最容易犯的错是"我先判断后缀再选库"，纯属给自己增加维护负担。</p></callout>

---

## 三、PDFBox：PDF 的精细手术刀

Tika 对 PDF 的解析底层其实也用了 PDFBox，但当你需要**精细控制**（提取坐标、表格、批注、处理加密 PDF）时，直接用 PDFBox 更顺手。

### 3.1 提取文本 + 每页内容

```java
// ch07-rag/src/main/java/demo/parse/PdfBoxDemo.java
package demo.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import java.awt.Rectangle;
import java.io.File;

public class PdfBoxDemo {
    public static void main(String[] args) throws Exception {
        try (PDDocument doc = PDDocument.load(new File("/data/kb/合同模板.pdf"))) {
            System.out.println("页数: " + doc.getNumberOfPages());

            // 1) 全文档文本
            PDFTextStripper stripper = new PDFTextStripper();
            String full = stripper.getText(doc);
            System.out.println(full.substring(0, Math.min(200, full.length())));

            // 2) 按区域提取（比如只取第1页右上角的"合同编号"）
            PDFTextStripperByArea area = new PDFTextStripperByArea();
            area.addRegion("contractNo", new Rectangle(400, 50, 150, 30));
            area.extractRegions(doc.getPage(0));
            System.out.println("合同编号: " + area.getText("contractNo"));
        }
    }
}
```

### 3.2 处理加密 / 扫描版 PDF

```java
// ch07-rag/src/main/java/demo/parse/PdfBoxEncrypted.java
package demo.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

public class PdfBoxEncrypted {
    public static String extract(String path, String password) throws Exception {
        try (PDDocument doc = PDDocument.load(new File(path), password)) {
            // 扫描版 PDF 没有文本层，getText 会返回空 → 需接 OCR
            String text = new PDFTextStripper().getText(doc);
            if (text.isBlank()) {
                System.out.println("⚠️ 疑似扫描版，需 OCR（Tesseract）");
                return ocrFallback(path);
            }
            return text;
        }
    }

    private static String ocrFallback(String path) {
        // 生产接 Tesseract：Runtime.getRuntime().exec("tesseract ...")
        // 或用云 OCR（如 DashScope / 百度 OCR）防止本地 OCR 精度不足
        return "[OCR 结果占位]";
    }
}
```

<callout emoji="⚠️"><p>扫描版 PDF 是 RAG 项目的头号刺客。它本质是"图片堆"，没有任何文本层，`getText()` 返回空字符串。解决方案只有 OCR：本地 Tesseract 免费但中文精度一般；云 OCR（如阿里云/DashScope）精度高但有成本。生产建议"本地 OCR 兜底 + 云 OCR 提质"双轨。</p></callout>

### 3.3 提取表格（PDF 表格是出了名的难）

PDF 没有"表格"结构，表格只是线条+文字坐标。PDFBox 可提取文字带坐标，再用规则聚合成表格：

```java
// ch07-rag/src/main/java/demo/parse/PdfTableExtract.java
package demo.parse;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.*;

public class PdfTableExtract extends PDFTextStripper {
    // 重写 writeString 收集每个字符的坐标，再按 y 坐标聚类成行/列
    private final List<TextPosition> positions = new ArrayList<>();

    public PdfTableExtract() throws Exception { super(); }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) {
        positions.addAll(textPositions); // 收集带 x/y 坐标的文字
    }

    public static void main(String[] args) throws Exception {
        try (PDDocument doc = PDDocument.load(new File("/data/kb/价格表.pdf"))) {
            PdfTableExtract extractor = new PdfTableExtract();
            extractor.setSortByPosition(true); // 按坐标排序，保持表格结构
            extractor.getText(doc);
            System.out.println("已收集字符坐标，可进一步聚类还原表格");
        }
    }
}
```

<callout emoji="💡"><p>真实项目里，PDF 表格还原强烈建议直接用成熟方案（如 Camelot/腾讯云文档解析 API），别自己造轮子聚合坐标——边界情况多到怀疑人生。PDFBox 适合"提取文字+坐标"的底层能力，复杂表格交给专门工具或云 API 更稳。</p></callout>

---

## 四、Apache POI：Office 全家桶解析

Word、Excel、PPT 都归 POI 管。重点是**保留文档结构**（段落、标题、表格单元格），这对 chunk 切分很有价值——lesson02 讲过，按结构切比按字数切好。

### 4.1 读 Word（.docx）保留段落与表格

```java
// ch07-rag/src/main/java/demo/parse/PoiWordDemo.java
package demo.parse;

import org.apache.poi.xwpf.usermodel.*;
import java.io.FileInputStream;

public class PoiWordDemo {
    public static void main(String[] args) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(
                new FileInputStream("/data/kb/员工手册.docx"))) {

            // 遍历段落（保留标题层级）
            for (XWPFParagraph p : doc.getParagraphs()) {
                String style = p.getStyle();           // Heading1/Heading2...
                String text = p.getText();
                if (!text.isBlank()) {
                    System.out.println("[" + style + "] " + text);
                }
            }

            // 遍历表格
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        System.out.print(cell.getText() + "\t");
                    }
                    System.out.println();
                }
            }
        }
    }
}
```

### 4.2 POI 读 Excel 的"内存陷阱"

POI 的 `XSSFWorkbook` 是**一次性把整个 Excel 加载进内存**，一个 200MB 的 Excel 能让 JVM 直接 OOM。这就是 EasyExcel 存在的理由。

```java
// ❌ 危险写法：大文件直接 OOM
// XSSFWorkbook wb = new XSSFWorkbook(new FileInputStream("200MB.xlsx"));
```

```xml
<!-- ch07-rag/pom.xml 片段 -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>easyexcel</artifactId>
    <version>3.3.4</version>
</dependency>
```

---

## 五、EasyExcel：百万行 Excel 的流式救星

EasyExcel 用**SAX 模式逐行解析**，内存占用恒定，不随文件大小增长。这是企业处理"大批量业务数据入库"的标配。

### 5.1 读 Excel（监听器模式，不 OOM）

```java
// ch07-rag/src/main/java/demo/parse/EasyExcelRead.java
package demo.parse;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.metadata.data.ReadSheet;

import java.util.ArrayList;
import java.util.List;

/** 数据行对象：字段对应 Excel 列 */
class RowData {
    private String sku;
    private String name;
    private String category;
    // getter/setter 省略
    public String getSku() { return sku; }
    public void setSku(String s) { this.sku = s; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getCategory() { return category; }
    public void setCategory(String c) { this.category = c; }
}

/** 流式监听器：每读一行回调一次，不占全量内存 */
class RowListener implements ReadListener<RowData> {
    private final List<RowData> batch = new ArrayList<>();

    @Override
    public void invoke(RowData data, AnalysisContext ctx) {
        batch.add(data);
        if (batch.size() >= 1000) {        // 攒够一批就处理（如切分入库）
            process(batch);
            batch.clear();
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext ctx) {
        if (!batch.isEmpty()) process(batch);
    }

    private void process(List<RowData> rows) {
        System.out.println("处理 " + rows.size() + " 行");
        // 这里调用 lesson02 的切分 + lesson03 的 Embedding 入库
    }
}

public class EasyExcelRead {
    public static void main(String[] args) {
        // 200MB 文件也不会 OOM，因为逐行流式读取
        EasyExcel.read("/data/kb/商品目录.xlsx", RowData.class, new RowListener())
                 .sheet()
                 .doRead();
        System.out.println("只读指定 sheet 也可：");
        EasyExcel.read("/data/kb/商品目录.xlsx")
                 .sheet("价格表").doRead();
    }
}
```

### 5.2 把 Excel 行变成 RAG chunk 的策略

Excel 不是"连续文本"，不能按字数切。正确做法是**每行/每区域作为一个语义单元**：

```java
// ch07-rag/src/main/java/demo/parse/ExcelToChunk.java
package demo.parse;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.ReadListener;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;

import java.util.ArrayList;
import java.util.List;

public class ExcelToChunk {
    /** 把商品 Excel 的每行，拼成一段结构化文本作为 chunk */
    static class ChunkListener implements ReadListener<RowData> {
        final List<Document> chunks = new ArrayList<>();
        @Override public void invoke(RowData d, com.alibaba.excel.context.AnalysisContext c) {
            String text = String.format(
                "商品:%s 名称:%s 分类:%s", d.getSku(), d.getName(), d.getCategory());
            Metadata md = new Metadata();
            md.put("sku", d.getSku());
            md.put("type", "product");
            chunks.add(Document.from(text, md));
        }
        @Override public void doAfterAllAnalysed(
                com.alibaba.excel.context.AnalysisContext c) {}
    }

    public static void main(String[] args) {
        ChunkListener l = new ChunkListener();
        EasyExcel.read("/data/kb/商品目录.xlsx", RowData.class, l).sheet().doRead();
        System.out.println("生成 chunk 数: " + l.chunks.size());
        // 后续：embedding + 入库
    }
}
```

<callout emoji="✅"><p>Excel 入库的黄金法则：一行（或一组相关行）就是一个 chunk，并把它本身的列（sku、分类）写进元数据。这样检索时不仅能"语义搜"，还能"按 sku 精确过滤"，这就是 lesson02 说的元数据价值。很多团队把 Excel 整个当文本切，丢了结构化优势，可惜。</p></callout>

---

## 六、企业落地：统一解析服务架构

给你一个真实的知识库解析服务设计：

```yaml
# ch07-rag/src/main/resources/parser-config.yml
parsers:
  universal:               # 80% 走 Tika 统一入口
    engine: tika
    fallback: pdfbox
  pdf:
    engine: pdfbox
    ocr:
      enabled: true
      primary: aliyun-ocr     # 云 OCR 提质
      fallback: tesseract     # 本地兜底
  docx:
    engine: poi
    preserve-structure: true  # 保留标题层级做结构化切分
  xlsx:
    engine: easyexcel         # 流式，防 OOM
    row-as-chunk: true
    emit-metadata: [sku, category]
```

```
                 ┌─────────────────────────────┐
   文件上传 ─────►│      统一解析服务            │
                 │  ├ Tika(自动探测类型)        │
                 │  ├ PDFBox(PDF 精细/扫描OCR)  │
                 │  ├ POI(Word 结构)            │
                 │  └ EasyExcel(大 Excel 流式)  │
                 └──────────────┬──────────────┘
                                │ 纯文本 + 结构 + 元数据
                                ▼
                        chunk → embedding → 入库
```

<callout emoji="💡"><p>为什么要把解析单独做成"服务"而不是散落在各处？因为解析是 CPU 密集 + 易出错（加密、扫描、损坏文件）的环节，集中处理才能统一做重试、超时、降级（解析失败不影响其他文件）。这也是微服务"单一职责"思想在 RAG 工程里的体现。</p></callout>

---

## 七、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 大 Excel 用 XSSFWorkbook | OOM 服务挂 | 换 EasyExcel 流式 |
| 扫描版 PDF 直接 getText | 拿到空文本 | 接 OCR 双轨 |
| POI 忘记关流 | 文件锁/内存泄漏 | try-with-resources |
| 解析异常不捕获 | 整个索引中断 | 单文件失败跳过+告警 |
| Excel 当纯文本切 | 结构信息丢失 | 行/区域作 chunk + 元数据 |

---

## 八、统一解析服务的 Spring Boot 骨架

给你一个把四种解析器集成的服务入口：

```java
// ch07-rag/src/main/java/demo/parse/UnifiedParser.java
package demo.parse;

import org.apache.tika.Tika;
import java.io.File;
import java.io.FileInputStream;

public class UnifiedParser {
    private final Tika tika = new Tika();

    /** 统一入口：Tika 探测类型后路由到专用解析器 */
    public String parse(File file) throws Exception {
        String mime = tika.detect(file);
        return switch (mime) {
            case "application/pdf" -> PdfBoxDemo.extractText(file);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                -> PoiWordDemo.extract(file);
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                -> EasyExcelRead.readFirstSheet(file);
            default -> tika.parseToString(new FileInputStream(file));
        };
    }
}
```

```xml
<!-- 解析服务相关依赖汇总 -->
<dependencies>
    <dependency><groupId>org.apache.tika</groupId><artifactId>tika-parsers</artifactId><version>2.9.2</version></dependency>
    <dependency><groupId>org.apache.pdfbox</groupId><artifactId>pdfbox</artifactId><version>3.0.2</version></dependency>
    <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.2.5</version></dependency>
    <dependency><groupId>com.alibaba</groupId><artifactId>easyexcel</artifactId><version>3.3.4</version></dependency>
</dependencies>
```

---

## 九、解析质量的自动校验

解析出来不能" blindly 入库"，要做质量门禁：

```java
// ch07-rag/src/main/java/demo/parse/ParseQualityGate.java
package demo.parse;

public class ParseQualityGate {
    /** 解析结果太短/乱码，视为失败 */
    public static boolean passed(String text, String source) {
        if (text == null || text.isBlank()) return false;
        double cjkRatio = text.chars()
                .filter(c -> c > 0x4E00 && c < 0x9FA5).count() * 1.0 / text.length();
        // 中文文档 CJK 占比应 > 0.2，否则可能解析失败/乱码
        if (source.endsWith(".pdf") && cjkRatio < 0.1) return false;
        return true;
    }
}
```

<callout emoji="✅"><p>解析质量门禁是"垃圾进垃圾出"的防线。扫描版 PDF 若 OCR 失败，文本会是空或乱码，直接入库会污染整个知识库。加一道"中文占比/长度"的简单校验，能拦掉大部分解析失败，省下大量下游 badcase。</p></callout>

---

## 十、解析性能与资源红线

| 红线 | 后果 | 防护 |
|------|------|------|
| 大 Excel 用 XSSF | OOM | EasyExcel 流式 |
| 同步解析阻塞主线程 | 接口超时 | 异步+队列 |
| 单文件失败中断全量 | 索引不全 | 跳过+告警 |
| 不限制文件大小 | 资源打满 | 大小上限+超时 |

## 小结

这节课我们实战了四个文档解析主力库：

- **Apache Tika**：统一入口，自动探测类型一行出文本，知识库多格式混放的首选。
- **PDFBox**：PDF 精细控制（坐标、区域、加密、扫描），复杂表格/结构化需求用它对底层。
- **Apache POI**：Word/Excel/PPT 结构解析，保留标题层级和表格，便于结构化切分。
- **EasyExcel**：SAX 流式读大 Excel，内存恒定不 OOM，每行作 chunk 并写入列元数据。
- 工程上把解析做成独立服务，统一重试、超时、降级，避免单文件失败拖垮全量索引。

下一节讲查询改写、上下文压缩与引用溯源（lesson07），把"检索后的最后一公里"做扎实。

---

## 延伸阅读 / 引用文档

1. Apache Tika 官方文档: https://tika.apache.org/
2. Apache PDFBox 文档: https://pdfbox.apache.org/docs/
3. Apache POI 文档: https://poi.apache.org/
4. Alibaba EasyExcel GitHub: https://github.com/alibaba/easyexcel
5. Tesseract OCR: https://github.com/tesseract-ocr/tesseract
6. LangChain4j Document Loaders: https://docs.langchain4j.dev/integrations/document-loaders-and-splitters/
