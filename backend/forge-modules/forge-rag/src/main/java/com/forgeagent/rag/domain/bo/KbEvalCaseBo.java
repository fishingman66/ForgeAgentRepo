package com.forgeagent.rag.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.common.core.validate.EditGroup;
import com.forgeagent.rag.domain.AgentKbEvalCase;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库评测用例业务对象。
 *
 * <p>刻意不含 createTime/updateTime:落库时间由服务端统一赋值。
 * 字段与 {@link AgentKbEvalCase} 一一对应,转换在 Controller 层手写完成
 * (不走 MapstructUtils:其静态初始化依赖 Spring 容器,纯 Mockito 场景下类加载即失败)。</p>
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbEvalCase.class, reverseConvertGenerate = false)
public class KbEvalCaseBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键(编辑必填;新增留空)
     */
    @NotNull(message = "用例ID不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 所属知识库ID
     */
    @NotNull(message = "知识库ID不能为空", groups = {AddGroup.class, EditGroup.class})
    private Long knowledgeId;

    /**
     * 评测问题
     */
    @NotBlank(message = "评测问题不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 1000, message = "评测问题不能超过 1000 字符", groups = {AddGroup.class, EditGroup.class})
    private String question;

    /**
     * 期望命中文档名(legacy 口径,模糊包含;空=不校验)
     */
    @Size(max = 300, message = "期望文档名不能超过 300 字符", groups = {AddGroup.class, EditGroup.class})
    private String expectedDocName;

    /**
     * 期望关键词(legacy 口径,逗号分隔任一命中即可;空=不校验)
     */
    @Size(max = 500, message = "期望关键词不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String expectedKeywords;

    /**
     * 证据标注 JSON:[{"documentId":123,"span":"原文片段"}];空=legacy 口径
     */
    private String evidences;

    /**
     * 用例状态:ACTIVE / EVIDENCE_STALE / DEPRECATED;新增留空按 ACTIVE
     */
    private String status;

    /**
     * 用例来源:MANUAL / DEBUG_PAGE / CASE_FAIL;新增留空按 MANUAL
     */
    private String source;

    /**
     * 来源记录ID(质量问题/调试命中)
     */
    private Long sourceId;

    /**
     * 数据集修订号
     */
    @Size(max = 64, message = "数据集修订号不能超过 64 字符", groups = {AddGroup.class, EditGroup.class})
    private String datasetRevision;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注不能超过 500 字符", groups = {AddGroup.class, EditGroup.class})
    private String remark;

}
