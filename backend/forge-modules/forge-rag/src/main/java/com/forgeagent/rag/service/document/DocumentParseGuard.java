package com.forgeagent.rag.service.document;

import com.forgeagent.common.core.exception.ServiceException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 上传文档的安全闸门:魔数校验 + 压缩包安全检查。
 *
 * <p><b>解决什么</b>:此前只按扩展名分派解析器,把扩展名改成 `.docx` 的可执行文件会被直接丢给 POI;
 * 一个 200 字节的 zip 炸弹能让解压把内存吃满;`../../etc/passwd` 这类条目名在后续落盘环节可能越界。
 * 这三类问题都不会在功能测试里暴露,但都是真实的攻击面。</p>
 *
 * <p>校验分两段:上传时做一次(快速失败,不落盘),解析前再确认一次(防绕过入口直接调用)。
 * 具体上限见常量,数值与参考项目一致:</p>
 * <ul>
 *   <li>单个压缩包条目数 ≤ 2000</li>
 *   <li>解压总量 ≤ 500MB,单条目 ≤ 100MB</li>
 *   <li>条目名不得为绝对路径、不得含 `..` 段、不得以盘符开头</li>
 * </ul>
 *
 * @author ForgeAgent
 */
public final class DocumentParseGuard {

    /**
     * 压缩包条目数上限(防 entry count bomb)
     */
    public static final int MAX_ZIP_ENTRIES = 2000;

    /**
     * 解压总量上限
     */
    public static final long MAX_UNCOMPRESSED_BYTES = 500L * 1024 * 1024;

    /**
     * 单条目解压上限
     */
    public static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;

    /**
     * 需要魔数校验的扩展名
     */
    private static final Set<String> OOXML = Set.of("docx", "xlsx", "pptx");

    static {
        // POI 解压比防线:即便攻击者声明了很小的解压体积,实际膨胀比过大也会被拒
        // (与 verifyZipSafety 的"实读计数"互为补强;POI 不存在时静默跳过)
        try {
            org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.01d);
        } catch (Throwable ignored) {
            // 老版本 POI 或未引入 POI:不影响其它格式
        }
    }

    private DocumentParseGuard() {
    }

    /**
     * 校验文件内容与扩展名是否一致(防"改名"绕过)
     *
     * @param ext   小写扩展名
     * @param bytes 文件内容
     */
    public static void verifyMagic(String ext, byte[] bytes) {
        if (ext == null || bytes == null || bytes.length == 0) {
            throw new ServiceException("文件内容为空");
        }
        String e = ext.toLowerCase(Locale.ROOT);
        switch (e) {
            case "pdf" -> requirePrefix(bytes, "%PDF".getBytes(StandardCharsets.US_ASCII), "PDF");
            case "docx", "xlsx", "pptx" -> requirePrefix(bytes, new byte[]{0x50, 0x4B, 0x03, 0x04}, "ZIP(docx/xlsx)");
            case "png" -> requirePrefix(bytes, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, "PNG");
            case "jpg", "jpeg" -> requirePrefix(bytes, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "JPEG");
            case "gif" -> requirePrefix(bytes, "GIF8".getBytes(StandardCharsets.US_ASCII), "GIF");
            default -> {
                // 纯文本类(txt/md/csv/json/html/htm/sql):无魔数可校,改为拒绝含 NUL 的二进制内容
                if (looksBinary(bytes)) {
                    throw new ServiceException("文件内容不是文本(疑似二进制),请检查扩展名是否与实际格式一致");
                }
            }
        }
    }

    /**
     * 压缩包安全检查:条目数、解压总量、条目名越界
     *
     * @param bytes 压缩包内容
     */
    public static void verifyZipSafety(byte[] bytes) {
        verifyZipSafety(bytes, MAX_ZIP_ENTRIES, MAX_UNCOMPRESSED_BYTES);
    }

    /**
     * 可注入上限的版本(便于单测用小阈值覆盖炸弹分支)
     */
    public static void verifyZipSafety(byte[] bytes, int maxEntries, long maxUncompressed) {
        int entries = 0;
        long total = 0;
        try (InputStream in = new ByteArrayInputStream(bytes);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) {
                    throw new ServiceException("压缩包条目数超过上限(" + maxEntries + "),疑似压缩炸弹");
                }
                String name = entry.getName() == null ? "" : entry.getName();
                if (isUnsafeEntryName(name)) {
                    throw new ServiceException("压缩包内含非法路径条目:" + name);
                }
                long entryBytes = 0;
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw new ServiceException("压缩包内单个条目解压后超过上限,疑似压缩炸弹:" + name);
                    }
                    if (total > maxUncompressed) {
                        throw new ServiceException("压缩包解压总量超过上限(" + maxUncompressed
                            + " 字节),疑似压缩炸弹");
                    }
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (IOException e) {
            throw new ServiceException("压缩包已损坏或不受支持:" + e.getMessage());
        }
        if (entries == 0) {
            throw new ServiceException("压缩包内没有任何条目");
        }
    }

    /**
     * 条目名是否越界:绝对路径、盘符、`..` 段
     */
    static boolean isUnsafeEntryName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/")) {
            return true;
        }
        if (normalized.length() > 1 && normalized.charAt(1) == ':') {
            return true;
        }
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * OOXML 是否需要做压缩包检查
     */
    public static boolean needsZipCheck(String ext) {
        return ext != null && OOXML.contains(ext.toLowerCase(Locale.ROOT));
    }

    private static void requirePrefix(byte[] bytes, byte[] prefix, String label) {
        if (bytes.length < prefix.length) {
            throw new ServiceException("文件内容与扩展名不符(应为 " + label + ")");
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                throw new ServiceException("文件内容与扩展名不符(应为 " + label + "),请确认文件未被改名");
            }
        }
    }

    /**
     * 前 512 字节内出现 NUL 即视为二进制
     */
    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 512);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
