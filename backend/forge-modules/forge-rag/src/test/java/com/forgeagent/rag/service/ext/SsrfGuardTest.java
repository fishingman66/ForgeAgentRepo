package com.forgeagent.rag.service.ext;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5-4 SSRF 守护单测:内网/回环/非 http 拒绝,正文抽取去噪
 *
 * @author ForgeAgent
 */
@Tag("dev")
class SsrfGuardTest {

    @Test
    void rejectsInternalAndLoopback() {
        assertThrows(ServiceException.class, () -> SsrfGuard.verify("http://127.0.0.1/admin"));
        assertThrows(ServiceException.class, () -> SsrfGuard.verify("http://localhost:8080/"));
        assertThrows(ServiceException.class, () -> SsrfGuard.verify("http://192.168.1.10/x"));
        assertThrows(ServiceException.class, () -> SsrfGuard.verify("http://10.0.0.5/x"));
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThrows(ServiceException.class, () -> SsrfGuard.verify("file:///etc/passwd"));
        assertThrows(ServiceException.class, () -> SsrfGuard.verify("ftp://example.com/x"));
        assertThrows(ServiceException.class, () -> SsrfGuard.verify("not a url"));
    }

    @Test
    void allowsPublicHost() {
        // example.com 是公网保留示例域,解析结果不属内网
        SsrfGuard.verify("https://example.com/doc");
    }

    @Test
    void plainTextStripsScriptAndTags() {
        String html = "<html><head><title>标题</title><script>alert(1)</script></head>"
            + "<body><p>第一段</p><style>.a{}</style><p>第二段 &amp; 说明</p></body></html>";
        String text = SsrfGuard.toPlainText(html);
        assertTrue(text.contains("第一段"));
        assertTrue(text.contains("第二段 & 说明"));
        assertFalse(text.contains("alert(1)"));
        assertFalse(text.contains("<p>"));
        assertFalse(text.contains(".a{}"));
    }
}
