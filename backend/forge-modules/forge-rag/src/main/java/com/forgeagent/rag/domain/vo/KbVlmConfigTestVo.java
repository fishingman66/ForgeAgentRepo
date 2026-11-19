package com.forgeagent.rag.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 图片识别模型连通性测试结果视图对象
 *
 * @author ForgeAgent
 */
@Data
public class KbVlmConfigTestVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否连通成功
     */
    private Boolean ok;

    /**
     * 结果描述(成功回显模型前 40 字,失败附原因前 200 字)
     */
    private String message;

    /**
     * 实际使用的模型名(配置不完整时为 null)
     */
    private String model;

}
