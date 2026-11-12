package com.forgeagent.rag.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PDF 解析(Apache PDFBox)
 *
 * <p>逐页抽取并写入 {@code [[page=N]]} 标记,供检索引用定位页码。三条防护:</p>
 * <ul>
 *   <li><b>扫描页</b>:一页提取不到 20 个字符就判为扫描页(没有文本层),跳过并计数,
 *       让上层标"需 OCR"而不是产出一堆空块;</li>
 *   <li><b>乱码页</b>:字体映射损坏时会抽出"字形汤",按可识别字符占比判定后跳过;</li>
 *   <li><b>规模</b>:页数上限 2000、正文上限 2000 万字符,超出即截断并告警——</li>
 * </ul>
 *
 * @author ForgeAgent
 */
@lombok.extern.slf4j.Slf4j
public class PdfDocumentParser implements DocumentParser {

    /**
     * 单页文本少于此长度视为扫描页(无文本层)
     */
    static final int SCANNED_PAGE_TEXT_THRESHOLD = 20;

    /**
     * 页数上限
     */
    static final int MAX_PAGES = 2000;

    /**
     * 正文长度上限(字符)
     */
    static final int MAX_CHARS = 20_000_000;

    /**
     * 图片数量超上限时的统一告警
     */
    static final String IMAGE_CAP_WARNING = "图片超过上限 " + DocumentImage.MAX_IMAGES + " 张,仅处理前 "
        + DocumentImage.MAX_IMAGES + " 张";

    /**
     * 单图像素上限(约 2000 万像素):再大解码就会吃掉大量堆内存
     */
    static final long MAX_IMAGE_PIXELS = 20_000_000L;

    @Override
    public String extension() {
        return "pdf";
    }

    @Override
    public String parse(byte[] bytes) throws Exception {
        return parseRich(bytes).text();
    }

    @Override
    public ParsedText parseRich(byte[] bytes) throws Exception {
        return parseDocument(bytes).text();
    }

    /**
     * R5-5:逐页抽文本 + 抽图。图片只从"有文本的页"里抽(扫描页仍按无文本层处理,
     * 整页 VLM 兜底不在本批次范围);每页图片以 {@code [[img:n]]} 标记追加到该页末尾。
     */
    @Override
    public ParsedDocument parseDocument(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            int total = doc.getNumberOfPages();
            int limit = Math.min(total, MAX_PAGES);
            List<String> warnings = new ArrayList<>();
            if (total > MAX_PAGES) {
                warnings.add("页数超过上限 " + MAX_PAGES + ",仅解析前 " + MAX_PAGES + " 页");
            }
            StringBuilder sb = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            List<DocumentImage> images = new ArrayList<>();
            Set<String> seenHashes = new HashSet<>();
            int scanned = 0;
            int garbled = 0;
            boolean truncated = false;
            for (int page = 1; page <= limit; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text;
                try {
                    text = stripper.getText(doc);
                } catch (Exception e) {
                    // 单页抽取失败不影响整篇(坏页常见于嵌入了异常字体)
                    warnings.add("第 " + page + " 页抽取失败已跳过");
                    continue;
                }
                String stripped = text == null ? "" : text.strip();
                if (stripped.length() < SCANNED_PAGE_TEXT_THRESHOLD) {
                    scanned++;
                    continue;
                }
                if (GarbledTextDetector.isGarbled(stripped)) {
                    garbled++;
                    continue;
                }
                if (sb.length() + stripped.length() > MAX_CHARS) {
                    truncated = true;
                    break;
                }
                sb.append("[[page=").append(page).append("]]\n").append(stripped).append('\n');
                // 图片:按页资源里的 image XObject 抽,同文档内按内容哈希去重(重复 logo 只描述一次)
                List<DocumentImage> pageImages = extractPageImages(doc, page, images.size(),
                    seenHashes, warnings);
                for (DocumentImage image : pageImages) {
                    sb.append("[[img=").append(image.index()).append("]]\n");
                }
                images.addAll(pageImages);
            }
            if (scanned > 0) {
                warnings.add(scanned + " 页无文本层(扫描件),需 OCR 才能检索");
            }
            if (garbled > 0) {
                warnings.add(garbled + " 页文本乱码,已跳过");
            }
            if (truncated) {
                warnings.add("正文超过 " + MAX_CHARS + " 字符,已截断");
            }
            return new ParsedDocument(new ParsedText(sb.toString(), total, scanned, garbled, warnings),
                images);
        }
    }

    /**
     * 抽单页图片:过滤小图标,按内容哈希去重,超过单文档上限即停止并告警。
     * 递归进入 Form XObject——真实 PDF(含 pymupdf/Office 导出)常把图片放在表单资源里,
     * 只扫页面直属资源会漏图。
     *
     * @param baseIndex 该页第一张图片的全局序号
     */
    private List<DocumentImage> extractPageImages(PDDocument doc, int pageNo, int baseIndex,
                                                  Set<String> seenHashes, List<String> warnings) {
        List<DocumentImage> result = new ArrayList<>();
        if (baseIndex >= DocumentImage.MAX_IMAGES) {
            return result;
        }
        try {
            org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(pageNo - 1);
            collectFromResources(page.getResources(), pageNo, baseIndex, result, seenHashes, warnings, 0);
        } catch (Exception e) {
            log.warn("[PDF] 第 {} 页图片抽取失败: {}", pageNo, e.getMessage());
            warnings.add("第 " + pageNo + " 页图片抽取失败已跳过");
        }
        return result;
    }

    private void collectFromResources(org.apache.pdfbox.pdmodel.PDResources resources, int pageNo, int baseIndex,
                                      List<DocumentImage> out, Set<String> seenHashes,
                                      List<String> warnings, int depth) {
        if (resources == null || depth > 3) {
            if (resources == null) {
                log.warn("[PDF] 第 {} 页资源字典为空", pageNo);
            }
            return;
        }
        for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
            if (baseIndex + out.size() >= DocumentImage.MAX_IMAGES) {
                if (!warnings.contains(IMAGE_CAP_WARNING)) {
                    warnings.add(IMAGE_CAP_WARNING);
                }
                return;
            }
            org.apache.pdfbox.pdmodel.graphics.PDXObject xObject;
            try {
                xObject = resources.getXObject(name);
            } catch (Exception e) {
                log.warn("[PDF] 第 {} 页 XObject {} 读取失败: {}", pageNo, name.getName(), e.getMessage());
                continue;
            }
            if (xObject == null) {
                continue;
            }
            if (xObject instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {
                collectFromResources(form.getResources(), pageNo, baseIndex, out, seenHashes, warnings, depth + 1);
                continue;
            }
            if (!(xObject instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image)) {
                continue;
            }
            int width;
            int height;
            try {
                width = image.getWidth();
                height = image.getHeight();
            } catch (Exception e) {
                continue;
            }
            if (width < DocumentImage.MIN_PIXELS || height < DocumentImage.MIN_PIXELS) {
                continue;
            }
            // 超大图跳过:解码到内存再编码,像素过多会把解析线程打爆
            if ((long) width * height > MAX_IMAGE_PIXELS) {
                continue;
            }
            byte[] data;
            try {
                java.awt.image.BufferedImage buffered = image.getImage();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(buffered, "png", bos);
                data = bos.toByteArray();
            } catch (Exception e) {
                log.warn("[PDF] 第 {} 页图片解码失败(suffix={}): {}", pageNo, image.getSuffix(), e.getMessage());
                continue;
            }
            if (data.length == 0 || !seenHashes.add(hashOf(data))) {
                continue;
            }
            out.add(new DocumentImage(baseIndex + out.size(), pageNo, null,
                "image/png", data, width, height));
        }
    }

    private static String mediaTypeOf(org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image) {
        try {
            String suffix = image.getSuffix();
            if (suffix == null) {
                return "image/png";
            }
            return switch (suffix.toLowerCase()) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "tif", "tiff" -> "image/tiff";
                case "gif" -> "image/gif";
                default -> "image/png";
            };
        } catch (Exception e) {
            return "image/png";
        }
    }

    static String hashOf(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            return String.valueOf(java.util.Arrays.hashCode(data));
        }
    }
}
