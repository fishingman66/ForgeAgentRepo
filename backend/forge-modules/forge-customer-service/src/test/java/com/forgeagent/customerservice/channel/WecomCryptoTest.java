package com.forgeagent.customerservice.channel;

import com.forgeagent.callcenter.common.CsChannelSignature;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 / B5:企微安全模式(加密)加解密与适配器行为。
 *
 * <p>这些用例覆盖"加密模式不得降级为明文"这条安全底线:
 * 配了 AESKey 却收到明文报文、receiveid 不匹配、密文被篡改,都必须失败。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
@DisplayName("企微加密模式")
class WecomCryptoTest {

    private static final String CORP_ID = "ww1234567890abcdef";

    private String aesKey() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 7 + 1);
        }
        String encoded = Base64.getEncoder().encodeToString(raw);
        return encoded.endsWith("=") ? encoded.substring(0, encoded.length() - 1) : encoded;
    }

    @Test
    @DisplayName("加密 → 解密往返一致(含中文与 XML 结构)")
    void roundTrip() {
        String plain = "<xml><Content><![CDATA[你好,订单 A123 已发货]]></Content></xml>";
        String encrypted = WecomCrypto.encrypt(aesKey(), CORP_ID, plain);
        assertNotEquals(plain, encrypted);
        assertEquals(plain, WecomCrypto.decrypt(aesKey(), CORP_ID, encrypted));
    }

    @Test
    @DisplayName("同一明文两次加密结果不同(随机前缀),但都能解回")
    void randomPrefixMakesCipherDifferent() {
        String plain = "hello";
        String first = WecomCrypto.encrypt(aesKey(), CORP_ID, plain);
        String second = WecomCrypto.encrypt(aesKey(), CORP_ID, plain);
        assertNotEquals(first, second, "每次加密应带不同 random(16)");
        assertEquals(plain, WecomCrypto.decrypt(aesKey(), CORP_ID, first));
        assertEquals(plain, WecomCrypto.decrypt(aesKey(), CORP_ID, second));
    }

    @Test
    @DisplayName("receiveid 与配置的 CorpID 不一致 → 拒绝(防跨企业报文重放)")
    void receiveIdMismatchRejected() {
        String encrypted = WecomCrypto.encrypt(aesKey(), CORP_ID, "hi");
        ServiceException ex = assertThrows(ServiceException.class,
            () -> WecomCrypto.decrypt(aesKey(), "ww-other-corp", encrypted));
        assertTrue(ex.getMessage().contains("receiveid"));
    }

    @Test
    @DisplayName("EncodingAESKey 不是合法 Base64 或长度不对 → 拒绝")
    void badAesKeyRejected() {
        assertThrows(ServiceException.class, () -> WecomCrypto.encrypt("not-base64-!!!", CORP_ID, "x"));
        // 16 字节 = 合法 base64 但长度不是 32
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        String trimmed = shortKey.endsWith("=") ? shortKey.substring(0, shortKey.length() - 1) : shortKey;
        assertThrows(ServiceException.class, () -> WecomCrypto.encrypt(trimmed, CORP_ID, "x"));
    }

    @Test
    @DisplayName("密文被篡改 → 拒绝,不返回垃圾明文")
    void tamperedCipherRejected() {
        String encrypted = WecomCrypto.encrypt(aesKey(), CORP_ID, "hello world");
        char last = encrypted.charAt(encrypted.length() - 1);
        String tampered = encrypted.substring(0, encrypted.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThrows(ServiceException.class, () -> WecomCrypto.decrypt(aesKey(), CORP_ID, tampered));
    }

    @Test
    @DisplayName("空密文 / 空 AESKey → 拒绝")
    void blankInputsRejected() {
        assertThrows(ServiceException.class, () -> WecomCrypto.decrypt(aesKey(), CORP_ID, ""));
        assertThrows(ServiceException.class, () -> WecomCrypto.decrypt("", CORP_ID, "abc"));
    }

    // ---------------- 适配器:模式判定与拒绝降级 ----------------

    private CsChannel channel(String aesKey) {
        CsChannel channel = new CsChannel();
        channel.setChannelCode("mail-test");
        channel.setChannelSecret("token123");
        channel.setCorpId(CORP_ID);
        channel.setEncodingAesKey(aesKey);
        return channel;
    }

    @Test
    @DisplayName("配了 AESKey 即判定为加密模式")
    void encryptedModeDetection() {
        assertTrue(WecomChannelAdapter.encryptedMode(channel(aesKey())));
        assertFalse(WecomChannelAdapter.encryptedMode(channel(null)));
        assertFalse(WecomChannelAdapter.encryptedMode(channel("  ")));
        assertFalse(WecomChannelAdapter.encryptedMode(null));
    }

    @Test
    @DisplayName("加密模式下收到无 Encrypt 的明文 POST → 验签失败(绝不降级)")
    void plaintextPostRejectedInEncryptedMode() {
        WecomChannelAdapter adapter = new WecomChannelAdapter();
        CsChannel ch = channel(aesKey());
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "n1";
        Map<String, String> params = new HashMap<>();
        params.put("msg_signature", "whatever");
        params.put("timestamp", String.valueOf(ts));
        params.put("nonce", nonce);
        String plainBody = "<xml><MsgType>text</MsgType><Content>hi</Content></xml>";
        assertFalse(adapter.verify(ch, params, plainBody, new CsModuleProperties()));
    }

    @Test
    @DisplayName("加密模式 POST:签名前像含 Encrypt,正确签名通过")
    void encryptedPostSignatureAccepted() {
        WecomChannelAdapter adapter = new WecomChannelAdapter();
        CsChannel ch = channel(aesKey());
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "n2";
        String encrypt = WecomCrypto.encrypt(aesKey(), CORP_ID, "<xml><MsgType>text</MsgType></xml>");
        String signature = CsChannelSignature.sha1("token123", String.valueOf(ts), nonce, encrypt);
        Map<String, String> params = new HashMap<>();
        params.put("msg_signature", signature);
        params.put("timestamp", String.valueOf(ts));
        params.put("nonce", nonce);
        String body = "<xml><Encrypt><![CDATA[" + encrypt + "]]></Encrypt></xml>";
        assertTrue(adapter.verify(ch, params, body, new CsModuleProperties()));
    }

    @Test
    @DisplayName("加密模式解析:解密后再取字段")
    void encryptedParseDecrypts() {
        WecomChannelAdapter adapter = new WecomChannelAdapter();
        CsChannel ch = channel(aesKey());
        String inner = "<xml><MsgType>text</MsgType><FromUserName>zhangsan</FromUserName>"
            + "<Content>我的订单在哪</Content><MsgId>m-100</MsgId></xml>";
        String encrypt = WecomCrypto.encrypt(aesKey(), CORP_ID, inner);
        String body = "<xml><Encrypt><![CDATA[" + encrypt + "]]></Encrypt></xml>";
        CsInboundMessage inbound = adapter.parse(body, new HashMap<>(), ch);
        assertEquals("m-100", inbound.msgId());
        assertEquals("zhangsan", inbound.externalUserId());
        assertEquals("我的订单在哪", inbound.content());
    }

    @Test
    @DisplayName("加密模式回包:Encrypt/MsgSignature/TimeStamp/Nonce 齐全且可解回")
    void encryptedReplyStructure() {
        WecomChannelAdapter adapter = new WecomChannelAdapter();
        CsChannel ch = channel(aesKey());
        CsInboundMessage inbound = new CsInboundMessage("m-1", "u1", "hi", "text", "corp");
        String xml = adapter.formatReply(inbound, "已发货", ch);
        assertTrue(xml.contains("<Encrypt>"), "加密回包必须带 Encrypt");
        assertTrue(xml.contains("<MsgSignature>"));
        assertTrue(xml.contains("<TimeStamp>"));
        assertTrue(xml.contains("<Nonce>"));
        String encrypt = WechatMpChannelAdapter.tag(xml, "Encrypt");
        String plain = WecomCrypto.decrypt(aesKey(), CORP_ID, encrypt);
        assertTrue(plain.contains("已发货"));
    }

    @Test
    @DisplayName("明文模式回包不含 Encrypt(行为保持不变)")
    void plainReplyUnchanged() {
        WecomChannelAdapter adapter = new WecomChannelAdapter();
        CsInboundMessage inbound = new CsInboundMessage("m-1", "u1", "hi", "text", "corp");
        String xml = adapter.formatReply(inbound, "已发货", channel(null));
        assertFalse(xml.contains("<Encrypt>"));
        assertTrue(xml.contains("<Content><![CDATA[已发货]]></Content>"));
    }

    @Test
    @DisplayName("加密模式 GET 验证:echostr 解密后明文返回")
    void echoDecryptsInEncryptedMode() {
        WecomChannelAdapter adapter = new WecomChannelAdapter();
        CsChannel ch = channel(aesKey());
        String encrypted = WecomCrypto.encrypt(aesKey(), CORP_ID, "1234567890");
        Map<String, String> params = new HashMap<>();
        params.put("echostr", encrypted);
        assertEquals("1234567890", adapter.echo(params, ch));
    }
}
