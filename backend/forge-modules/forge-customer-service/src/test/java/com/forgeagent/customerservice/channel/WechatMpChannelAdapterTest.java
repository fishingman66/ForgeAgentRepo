package com.forgeagent.customerservice.channel;

import com.forgeagent.callcenter.common.CsChannelSignature;

import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C3:公众号 XML 解析、CDATA 转义与验签。 */
@Tag("dev")
class WechatMpChannelAdapterTest {

    private final WechatMpChannelAdapter adapter = new WechatMpChannelAdapter();

    @Test
    void parsesTextXmlWithCdata() {
        String xml = "<xml><FromUserName><![CDATA[openid-1]]></FromUserName>"
            + "<ToUserName><![CDATA[gh-1]]></ToUserName>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[怎么退货]]></Content><MsgId>12345</MsgId></xml>";
        CsInboundMessage msg = adapter.parse(xml, Map.of("timestamp", "1"));
        assertEquals("openid-1", msg.externalUserId());
        assertEquals("gh-1", msg.toUser());
        assertEquals("怎么退货", msg.content());
        assertEquals("12345", msg.msgId());
    }

    @Test
    void cdataEscapesTerminator() {
        assertEquals("<![CDATA[a]]]]><![CDATA[>b]]>", WechatMpChannelAdapter.cdata("a]]>b"));
    }

    @Test
    void verifyAcceptsCorrectSignatureAndRejectsBadOne() {
        CsChannel channel = new CsChannel();
        channel.setChannelSecret("token123");
        CsModuleProperties props = new CsModuleProperties();
        String ts = String.valueOf(Instant.now().getEpochSecond());
        Map<String, String> params = new HashMap<>();
        params.put("timestamp", ts);
        params.put("nonce", "abc");
        params.put("signature", CsChannelSignature.sha1("token123", ts, "abc"));
        assertTrue(adapter.verify(channel, params, props));
        params.put("signature", "deadbeef");
        assertFalse(adapter.verify(channel, params, props));
        params.put("timestamp", "1");
        assertFalse(adapter.verify(channel, params, props));
    }
}
