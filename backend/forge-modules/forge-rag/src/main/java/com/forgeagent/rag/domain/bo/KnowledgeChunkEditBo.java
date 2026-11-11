package com.forgeagent.rag.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;
/**
 * 知识库分块编辑请求对象
 *
 * @author ForgeAgent
 */
@Data
public class KnowledgeChunkEditBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分块ID
     */
    @NotNull(message = "分块ID不能为空")
    private Long id;

    /**
     * 分块文本内容(编辑后自动重新向量化)
     */
    @NotBlank(message = "分块内容不能为空")
    @Size(max = 2000, message = "分块内容不能超过 2000 字符")
    private String content;

}
