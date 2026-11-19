package com.forgeagent.rag.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.forgeagent.common.core.validate.AddGroup;
import com.forgeagent.rag.domain.AgentKbExtSource;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库外部源登记业务对象(R5-4)。
 *
 * <p>只收登记所需的四项:status/lastSyncAt/lastError 由同步流程回写,不由前端设置。</p>
 *
 * @author ForgeAgent
 */
@Data
@AutoMapper(target = AgentKbExtSource.class, reverseConvertGenerate = false)
public class KbExtSourceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属知识库ID
     */
    @NotNull(message = "知识库ID不能为空", groups = {AddGroup.class})
    private Long knowledgeId;

    /**
     * 来源类型:URL / S3 / CONFLUENCE(按类型匹配连接器)
     */
    @NotBlank(message = "来源类型不能为空", groups = {AddGroup.class})
    @Size(max = 32, message = "来源类型不能超过 32 字符", groups = {AddGroup.class})
    private String sourceType;

    /**
     * 外部源名称(留空按「类型-来源」生成)
     */
    @Size(max = 100, message = "外部源名称不能超过 100 字符", groups = {AddGroup.class})
    private String name;

    /**
     * 来源配置 JSON(URL 为 {"url":...});凭据不入库明文
     */
    private String configJson;

}
