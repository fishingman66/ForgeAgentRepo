package com.forgeagent.rag.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.rag.domain.AgentKnowledge;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.common.core.validate.QueryGroup;
import java.io.Serial;
import java.io.Serializable;
/**
 * 知识库注册业务对象
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKnowledge.class, reverseConvertGenerate = false)
public class AgentKnowledgeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填)
     */
    @NotNull(message = "知识库ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 所属工作空间ID(新增必填;NULL表示全局,仅超管可创建全局知识库)
     */
    private Long workspaceId;

    /**
     * 知识库名称
     */
    @NotBlank(message = "知识库名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(min = 2, max = 50, message = "知识库名称长度须在 2-50 字符之间", groups = {AddGroup.class, EditGroup.class})
    private String name;

    /**
     * 知识库描述
     */
    @Size(max = 200, message = "知识库描述不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String description;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 嵌入模型(local-hash 或 agent_model 配置名称)
     */
    @Size(max = 100, message = "嵌入模型标识不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String embeddingModel;

    /**
     * 重排序模型名称(agent_model 配置名称,可选)
     */
    @Size(max = 100, message = "重排序模型标识不能超过 100 字符", groups = {AddGroup.class, EditGroup.class})
    private String rerankModel;

    /**
     * 相关度阈值(0~1,作用于向量相似度/精排分,为空不过滤)
     */
    @DecimalMin(value = "0", message = "相关度阈值不能小于 0", groups = {AddGroup.class, EditGroup.class})
    @DecimalMax(value = "1", message = "相关度阈值不能大于 1", groups = {AddGroup.class, EditGroup.class})
    private java.math.BigDecimal scoreThreshold;
/**
 * 精排相关度阈值(0~1,作用于 rerankScore;NULL 不过滤精排结果)
 */
private java.math.BigDecimal rerankThreshold;

    /**
     * 分块大小(字符数)
     */
    @Min(value = 100, message = "分块大小不能小于 100", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 2000, message = "分块大小不能超过 2000", groups = {AddGroup.class, EditGroup.class})
    private Integer chunkSize;

    /**
     * 分块重叠字符数
     */
    @Min(value = 0, message = "分块重叠不能小于 0", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 500, message = "分块重叠不能超过 500", groups = {AddGroup.class, EditGroup.class})
    private Integer chunkOverlap;

    /**
     * 分块计价单位:char(字符) / token(预估 token);留空按 char,与存量库一致
     */
    private String chunkUnit;

    /**
     * 分块策略:structure(结构感知) / fixed(定长对照);留空按 structure
     */
    private String splitStrategy;

    /**
     * 清洗规则 JSON 字符串(headerFooter/blankNormalize/nfkc/piiMask 布尔键 + regexReplace 数组);
     * 留空按默认(页眉页脚/空白归一开,NFKC/PII 关)
     */
    private String cleanRules;

    /**
     * 父子分块开关(1=先切父块再切子块,检索命中子块注入父块全文)
     */
    private String parentChunkEnabled;

    /**
     * 父块目标大小(字符)
     */
    private Integer parentChunkSize;

    /**
     * 备注
     */
    @Size(max = 200, message = "备注不能超过 200 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

    /**
     * 查询关键字(仅查询用)
     */
    @Size(max = 50, groups = {QueryGroup.class})
    private String keyword;

}
