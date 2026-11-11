package com.forgeagent.rag.service.document;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传闸门:魔数一致性 + 压缩包安全。
 *
 * <p>这些分支在功能测试里永远不会被走到——只有真的传一个改名文件或压缩炸弹才会触发,
 * 所以必须靠单测锁住。</p>
 */
@Tag("dev")
class DocumentParseGuardTest {

    private static byte[] zipOf(String... entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String e : entries) {
                String[] parts = e.split(":", 2);
                zip.putNextEntry(new ZipEntry(parts[0]));
                zip.write(parts[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void pdfWithoutMagicIsRejected() {
        byte[] notPdf = "hello".getBytes(StandardCharsets.UTF_8);
        assertThrows(ServiceException.class, () -> DocumentParseGuard.verifyMagic("pdf", notPdf));
        assertDoesNotThrow(() -> DocumentParseGuard.verifyMagic("pdf",
            "%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void ooxmlMustBeZip() {
        assertThrows(ServiceException.class, () -> DocumentParseGuard.verifyMagic("docx",
            "MZ\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1)),
            "把可执行文件改名成 docx 必须被拒");
        assertDoesNotThrow(() -> DocumentParseGuard.verifyMagic("docx", zipOf("word/document.xml:<a/>")));
    }

    @Test
    void textFormatsRejectBinaryContent() {
        assertThrows(ServiceException.class, () -> DocumentParseGuard.verifyMagic("txt",
            new byte[]{'a', 'b', 0, 'c'}));
        assertDoesNotThrow(() -> DocumentParseGuard.verifyMagic("md",
            "# 标题\n正文".getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> DocumentParseGuard.verifyMagic("json", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unsafeEntryNamesAreDetected() {
        assertTrue(DocumentParseGuard.isUnsafeEntryName("../etc/passwd"));
        assertTrue(DocumentParseGuard.isUnsafeEntryName("word/../../x.xml"));
        assertTrue(DocumentParseGuard.isUnsafeEntryName("/absolute/path"));
        assertTrue(DocumentParseGuard.isUnsafeEntryName("C:/windows/system32"));
        assertTrue(DocumentParseGuard.isUnsafeEntryName("a\\..\\b"));
        assertFalse(DocumentParseGuard.isUnsafeEntryName("word/document.xml"));
        assertFalse(DocumentParseGuard.isUnsafeEntryName("a..b/c.xml"), "含 .. 但不是独立路径段,应当放行");
    }

    @Test
    void zipSlipIsRejected() {
        byte[] evil = zipOf("../evil.xml:boom", "word/document.xml:<a/>");
        assertThrows(ServiceException.class, () -> DocumentParseGuard.verifyZipSafety(evil));
    }

    @Test
    void tooManyEntriesIsRejected() {
        byte[] many = zipOf("a:1", "b:1", "c:1", "d:1", "e:1");
        assertThrows(ServiceException.class,
            () -> DocumentParseGuard.verifyZipSafety(many, 3, 10_000_000L),
            "条目数超过上限应判为压缩炸弹");
        assertDoesNotThrow(() -> DocumentParseGuard.verifyZipSafety(many, 10, 10_000_000L));
    }

    @Test
    void uncompressedSizeBombIsRejected() {
        byte[] bomb = zipOf("big:" + "x".repeat(5000));
        assertThrows(ServiceException.class,
            () -> DocumentParseGuard.verifyZipSafety(bomb, 100, 1000L),
            "解压总量超过上限应判为压缩炸弹");
    }

    @Test
    void normalDocxPassesDefaultLimits() {
        byte[] docx = zipOf("word/document.xml:<w:document/>",
            "[Content_Types].xml:<Types/>", "word/_rels/document.xml.rels:<Relationships/>");
        assertDoesNotThrow(() -> DocumentParseGuard.verifyZipSafety(docx));
    }

    @Test
    void emptyZipIsRejected() {
        assertThrows(ServiceException.class, () -> DocumentParseGuard.verifyZipSafety(zipOf()));
    }

    @Test
    void corruptZipIsRejected() {
        assertThrows(ServiceException.class, () -> DocumentParseGuard.verifyZipSafety(
            new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x01, 0x02}));
    }
}
