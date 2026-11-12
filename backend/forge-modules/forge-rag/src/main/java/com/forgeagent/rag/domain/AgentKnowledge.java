package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;
import java.io.Serial;
/**
 * Agent 知识库注册对象 agent_knowledge
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_knowledge")
public class AgentKnowledge extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 知识库名称(全局唯一)
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 作用域(normal普通/temp会话附件临时索引)
     */
    private String kbScope;

    /**
     * 所属会话ID(仅temp)
     */
    private Long sessionId;

    /**
     * 所属工作空间ID(NULL表示全局/历史数据,向后兼容)
     */
    private Long workspaceId;

    /**
     * 状态(0正常 1停用)
     */
    private String status;

    /**
     * 嵌入模型(local-hash 或 agent_model 配置名称)
     */
    private String embeddingModel;

    /**
     * 重排序模型名称(agent_model 配置名称,配置后检索可开启精排)
     */
    private String rerankModel;

    /**
     * 相关度阈值(0~1,作用于向量相似度/精排分,低于该分数的分块不召回;NULL 不过滤)
     */
    private java.math.BigDecimal scoreThreshold;
/**
 * 精排相关度阈值(0~1,作用于 rerankScore;NULL 不过滤精排结果)
 */
private java.math.BigDecimal rerankThreshold;

    /**
     * 向量库后端(当前支持 milvus)
     */
    private String vectorBackend;

    /**
     * 向量维度
     */
    private Integer vectorDim;

    /**
     * 零召回兜底(R4-3):1=允许基于通用知识回答但须显式声明(默认);
     * 0=严格模式一律拒答(金融/医疗场景)
     */
    private Integer fallbackAnswerEnabled;

    /**
     * 上传后需审核(R5-2):1=新文档落 PENDING_REVIEW,审核通过才参与检索
     */
    private Integer reviewRequired;

    /**
     * 库级检索参数覆盖 JSON(R5-6):{topK,searchMode,vectorWeight,keywordWeight,scoreThreshold,rerank};
     * NULL=完全跟随全局
     */
    private String retrievalOverrides;

    /**
     * 分块大小(字符数)
     */
    private Integer chunkSize;

    /**
     * 分块重叠字符数
     */
    private Integer chunkOverlap;

    /**
     * 分块计价单位:char(字符,存量口径) / token(预估 token,跨语种更稳)
     */
    private String chunkUnit;

    /**
     * 分块策略:structure(结构感知,默认) / fixed(定长对照)
     */
    private String splitStrategy;

    /**
     * 解析清洗规则 JSON(NULL=默认:页眉页脚/空白归一开,NFKC/PII 关)
     */
    private String cleanRules;

    /**
     * 解析管线指纹(sha256:管线版本+规范化清洗规则;改清洗规则即变化 → 文档标脏)
     */
    private String parseFingerprint;

    /**
     * 分块配置指纹(sha256:单位+策略+大小+重叠+父子参数;改分块配置即变化 → 文档标脏)
     */
    private String chunkFingerprint;

    /**
     * 父子分块开关(1=先切父块再切子块,检索命中子块注入父块全文)
     */
    private String parentChunkEnabled;

    /**
     * 父块目标大小(字符)
     */
    private Integer parentChunkSize;

    /**
     * 父块含禁用子块时的可见性策略:1=整块不返回(默认,宁可丢上下文也不漏禁用内容) 0=返回但挖除禁用片段
     */
    private String hideParentWithDisabledChild;

    /**
     * 文档数量(冗余统计)
     */
    private Integer docCount;

    /**
     * 备注
     */
    private String remark;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
