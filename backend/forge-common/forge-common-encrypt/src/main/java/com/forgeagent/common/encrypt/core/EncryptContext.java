package com.forgeagent.common.encrypt.core;

import lombok.Data;
import com.forgeagent.common.encrypt.enums.AlgorithmType;
import com.forgeagent.common.encrypt.enums.EncodeType;

/**
 * 加密上下文 用于encryptor传递必要的参数。
 *
 * @author 老马; 改造: 智造 ForgeAgent 2026-11
 * @version 4.6.0
 */
@Data
public class EncryptContext {

    /**
     * 默认算法
     */
    private AlgorithmType algorithm;

    /**
     * 安全秘钥
     */
    private String password;

    /**
     * 公钥
     */
    private String publicKey;

    /**
     * 私钥
     */
    private String privateKey;

    /**
     * 编码方式，base64/hex
     */
    private EncodeType encode;

}
