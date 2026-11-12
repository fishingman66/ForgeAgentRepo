package com.forgeagent.rag.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.io.Serial;
import java.io.Serializable;
/**
 * 知识库检索请求对象
 *
 * @author ForgeAgent
 */
@Data
public class KnowledgeSearchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标知识库ID
     */
    @NotNull(message = "知识库ID不能为空")
    private Long knowledgeId;

    /**
     * 查询文本
     */
    @NotBlank(message = "查询内容不能为空")
    @Size(max = 1000, message = "查询内容不能超过 1000 字符")
    private String query;

    /**
     * 返回条数(1-20)
     */
    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 20, message = "topK 最大为 20")
    private Integer topK = 5;

    /**
     * 检索模式(vector 纯向量 / keyword 纯关键词 / hybrid 混合,默认 hybrid)
     */
    private String searchMode = "hybrid";

    /**
     * 是否开启重排序精排(需知识库已配置重排序模型,默认关闭)
     */
    private Boolean rerank = Boolean.FALSE;

    /**
     * 是否允许走检索结果缓存(仅对话链路置 true;命中测试保持 false,保证分块编辑/禁用后实时可见)
     */
    private Boolean cacheable = Boolean.FALSE;

    /**
     * R5-6 MCP filters:限定文档范围(空=不限);在检索前作为允许列表下推
     */
    private java.util.List<Long> documentIds;

    /**
     * 体检 S2:内部检索入口(searchInternal)的调用方工作空间ID——
     * 目标知识库必须属于该空间或为全局库,防止跨空间读取;search(登录态)不使用该字段
     */
    private Long callerWorkspaceId;

}
