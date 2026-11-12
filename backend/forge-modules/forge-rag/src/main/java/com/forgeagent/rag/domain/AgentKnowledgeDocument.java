package com.forgeagent.rag.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.mybatis.core.domain.BaseEntity;
import java.io.Serial;
import java.util.Date;
/**
 * 知识库文档对象 agent_knowledge_document
 *
 * @author ForgeAgent
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_knowledge_document")
public class AgentKnowledgeDocument extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属知识库ID
     */
    private Long knowledgeId;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件扩展名(小写,如 txt/md/docx/xlsx/pdf)
     */
    private String fileExt;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 文件内容MD5(同一知识库内按内容去重)
     */
    private String fileMd5;

    /**
     * 文件存储路径
     */
    private String filePath;

    /**
     * 解析向量化状态(0处理中 1可用 2失败)
     */
    private String status;

    /**
     * 失败原因
     */
    private String failReason;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * 当前生效代次:重建成功后切换;检索只认该代次的分块(切换前旧代仍可查)
     */
    private Integer activeGeneration;

    /**
     * 处理提示(如「向量写入失败,已排队补偿」「扫描件需 OCR,当前未启用」)
     */
    private String notice;

    /**
     * 可见性(R5-1):INHERIT 继承空间(默认) / RESTRICTED 仅授权角色可见
     */
    private String visibility;

    /**
     * 发布态(R5-2):DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED;仅 PUBLISHED 参与检索
     */
    private String publishStatus;

    /**
     * 生效时间(NULL=立即生效)
     */
    private Date effectiveAt;

    /**
     * 过期时间(NULL=不过期)
     */
    private Date expiresAt;

    /**
     * 回收站(1=已入回收站,不参与检索,超期物理清理)
     */
    private Integer trashed;

    /**
     * 入回收站时间
     */
    private Date trashedAt;

    /**
     * 配置过期:1=知识库分块/清洗口径已变,待差异重建(重建成功后清 0)
     */
    private Integer configStale;

    /**
     * 摄取时的解析管线指纹;与知识库当前指纹一致且 parsed_text 可用时,重建只重切不重解析
     */
    private String parseFingerprint;

    /**
     * 解析+清洗后的正文(复用产物;超大文档不落,置 NULL 不阻断;查询列表时默认不加载)
     */
    @com.baomidou.mybatisplus.annotation.TableField(select = false)
    private String parsedText;

    /**
     * 删除标志(0存在 2删除)
     */
    @TableLogic
    private Long delFlag;

}
