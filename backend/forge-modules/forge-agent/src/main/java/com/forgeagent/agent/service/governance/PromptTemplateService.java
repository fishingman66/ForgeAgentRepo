package com.forgeagent.agent.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentPromptTemplate;
import com.forgeagent.agent.domain.AgentPromptVersion;
import com.forgeagent.agent.mapper.AgentPromptTemplateMapper;
import com.forgeagent.agent.mapper.AgentPromptVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板中心与编译运行时(P1 / B2)。
 *
 * <p>模板以不可变版本保存,变量契约是强类型的(必填 / 类型 / 枚举 / 默认值),
 * 编译期做 {{name}} 替换并产出 contentHash。Agent 三态绑定:
 * MANUAL 用自己的系统提示词,LOCKED 锁定指定版本,FOLLOW_STABLE 跟随模板稳定版。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@Service
public class PromptTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 编译结果 */
    public record Compiled(String content, String contentHash, Long templateId, Long versionId,
                           List<String> layers) {
    }

    private final AgentPromptTemplateMapper templateMapper;
    private final AgentPromptVersionMapper versionMapper;

    public PromptTemplateService(AgentPromptTemplateMapper templateMapper, AgentPromptVersionMapper versionMapper) {
        this.templateMapper = templateMapper;
        this.versionMapper = versionMapper;
    }

    public List<com.forgeagent.agent.domain.vo.AgentPromptTemplateVo> listTemplates(Long workspaceId) {
        return templateMapper.listTemplates(workspaceId == null ? 0L : workspaceId);
    }

    public com.forgeagent.agent.domain.vo.AgentPromptTemplateVo getTemplate(Long id) {
        AgentPromptTemplate t = templateMapper.selectById(id);
        if (t == null || !"0".equals(String.valueOf(t.getDelFlag()))) {
            throw new ServiceException("Prompt 模板不存在");
        }
        return MAPPER.convertValue(t, com.forgeagent.agent.domain.vo.AgentPromptTemplateVo.class);
    }

    /**
     * B4:按模板名取<b>已发布</b>模板的生效文本(优先工作空间自有,其次平台共享 workspace_id=0)。
     *
     * <p>取值顺序:设了稳定版则取稳定版本内容,否则取模板当前 content(草稿态内容不参与——
     * 调用方约定按已发布语义消费)。找不到或不发布时返回 null,<b>不抛异常</b>:
     * 调用方(RCA 等)以内置兜底提示词为准,模板是可选覆盖。</p>
     *
     * <p>不做变量渲染:本入口面向纯文本系统提示词(无 {{var}} 占位),需要变量渲染走
     * {@link #compileForAgent} / {@link #preview}。</p>
     */
    public String compileByCode(Long workspaceId, String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentPromptTemplate> qw =
            Wrappers.lambdaQuery(AgentPromptTemplate.class)
                .eq(AgentPromptTemplate::getName, name.trim())
                .in(AgentPromptTemplate::getWorkspaceId,
                    workspaceId == null ? List.of(0L) : List.of(workspaceId, 0L))
                .eq(AgentPromptTemplate::getStatus, "published")
                .orderByDesc(AgentPromptTemplate::getWorkspaceId)
                .last("limit 2");
        List<AgentPromptTemplate> rows = templateMapper.selectList(qw);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        // orderByDesc(workspaceId) 后首选行即工作空间自有(ws>0),无自有时才是共享行
        AgentPromptTemplate chosen = rows.get(0);
        if (chosen.getStableVersionId() != null) {
            AgentPromptVersion version = versionMapper.selectById(chosen.getStableVersionId());
            if (version != null && StringUtils.isNotBlank(version.getContent())) {
                return version.getContent();
            }
        }
        return StringUtils.isBlank(chosen.getContent()) ? null : chosen.getContent();
    }

    public Long saveTemplate(com.forgeagent.agent.domain.bo.PromptTemplateSaveBo body) {
        Long id = body.getId();
        String name = StringUtils.defaultIfBlank(body.getName(), "未命名模板");
        String content = body.getContent();
        if (StringUtils.isBlank(content)) {
            throw new ServiceException("模板内容不能为空");
        }
        Long workspaceId = body.getWorkspaceId();
        if (workspaceId == null) {
            throw new ServiceException("工作空间不能为空");
        }
        String schema = body.getVariableSchema();
        validateSchema(schema);
        if (id == null) {
            AgentPromptTemplate entity = new AgentPromptTemplate();
            entity.setWorkspaceId(workspaceId);
            entity.setName(name);
            entity.setDescription(body.getDescription());
            entity.setCategory(StringUtils.defaultIfBlank(body.getCategory(), "system"));
            entity.setContent(content);
            entity.setVariableSchema(schema);
            entity.setStatus("draft");
            entity.setCreateBy(currentUserId());
            templateMapper.insert(entity);
            return entity.getId();
        }
        getTemplate(id);
        AgentPromptTemplate update = new AgentPromptTemplate();
        update.setId(id);
        update.setName(name);
        update.setDescription(body.getDescription());
        update.setCategory(StringUtils.defaultIfBlank(body.getCategory(), "system"));
        update.setContent(content);
        update.setVariableSchema(schema);
        update.setUpdateTime(new java.util.Date());
        templateMapper.updateById(update);
        return id;
    }

    public List<com.forgeagent.agent.domain.vo.AgentPromptVersionVo> listVersions(Long templateId) {
        return versionMapper.listByTemplate(templateId);
    }

    public com.forgeagent.agent.domain.vo.AgentPromptVersionVo getVersion(Long versionId) {
        AgentPromptVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new ServiceException("模板版本不存在");
        }
        return MAPPER.convertValue(v, com.forgeagent.agent.domain.vo.AgentPromptVersionVo.class);
    }

    /** 发布不可变版本;asStable=true 时同步设为模板稳定版 */
    public Long publishVersion(Long templateId, String content, String schema,
                               String remark, boolean asStable) {
        if (StringUtils.isBlank(content)) {
            throw new ServiceException("模板内容不能为空");
        }
        validateSchema(schema);
        Integer next = versionMapper.nextVersionNo(templateId);
        int versionNo = next == null ? 1 : next;
        AgentPromptVersion version = new AgentPromptVersion();
        version.setTemplateId(templateId);
        version.setVersionNo(versionNo);
        version.setContent(content);
        version.setVariableSchema(schema);
        version.setContentHash(sha256(content));
        version.setRemark(remark);
        version.setCreateBy(currentUserId());
        versionMapper.insert(version);
        Long versionId = version.getId();
        AgentPromptTemplate published = new AgentPromptTemplate();
        published.setId(templateId);
        published.setStatus("published");
        published.setUpdateTime(new java.util.Date());
        templateMapper.updateById(published);
        if (asStable) {
            setStable(templateId, versionId);
        }
        return versionId;
    }

    public void setStable(Long templateId, Long versionId) {
        getTemplate(templateId);
        getVersion(versionId);
        AgentPromptTemplate stable = new AgentPromptTemplate();
        stable.setId(templateId);
        stable.setStableVersionId(versionId);
        stable.setUpdateTime(new java.util.Date());
        templateMapper.updateById(stable);
    }

    /** 编译预览:返回渲染结果、变量契约校验结果与内容摘要 */
    public com.forgeagent.agent.domain.vo.PromptPreviewVo preview(Long versionId, String variablesJson) {
        com.forgeagent.agent.domain.vo.AgentPromptVersionVo version = getVersion(versionId);
        String rendered = render(version.getContent(),
            version.getVariableSchema(), variablesJson);
        com.forgeagent.agent.domain.vo.PromptPreviewVo result = new com.forgeagent.agent.domain.vo.PromptPreviewVo();
        result.setContent(rendered);
        result.setContentHash(sha256(rendered));
        result.setLayers(List.of("TEMPLATE"));
        result.setEstimatedTokens(Math.max(1, rendered.length() / 4));
        return result;
    }

    /**
     * C4:草稿内容直接渲染预览(不落版本链),供"生成 → 预览 → 采纳"链路使用。
     */
    public com.forgeagent.agent.domain.vo.PromptPreviewVo previewDraft(String content, String schema,
                                                                       String variablesJson) {
        if (StringUtils.isBlank(content)) {
            throw new ServiceException("草稿内容不能为空");
        }
        String rendered = render(content, schema, variablesJson);
        com.forgeagent.agent.domain.vo.PromptPreviewVo result = new com.forgeagent.agent.domain.vo.PromptPreviewVo();
        result.setContent(rendered);
        result.setContentHash(sha256(rendered));
        result.setLayers(List.of("DRAFT"));
        result.setEstimatedTokens(Math.max(1, rendered.length() / 4));
        return result;
    }

    /**
     * 按 Agent 绑定模式编译系统提示词;MANUAL 或未绑定模板时返回 null,
     * 由调用方沿用 Agent 自带的 systemPrompt。
     */
    public Compiled compileForAgent(AgentInfo agent) {
        Long templateId = agent.getPromptTemplateId();
        if (templateId == null) {
            return null;
        }
        String mode = StringUtils.defaultIfBlank(agent.getPromptBindingMode(), "MANUAL");
        if ("MANUAL".equalsIgnoreCase(mode)) {
            return null;
        }
        Long versionId;
        if ("LOCKED".equalsIgnoreCase(mode)) {
            versionId = agent.getPromptVersionId();
            if (versionId == null) {
                throw new ServiceException("LOCKED 模式必须指定锁定的模板版本");
            }
        } else {
            List<Long> stable = templateMapper.stableVersionId(templateId);
            versionId = stable.isEmpty() ? null : stable.getFirst();
        }
        if (versionId == null) {
            throw new ServiceException("模板尚未设置稳定版,无法以 FOLLOW_STABLE 模式运行");
        }
        com.forgeagent.agent.domain.vo.AgentPromptVersionVo version = getVersion(versionId);
        String rendered = render(version.getContent(),
            version.getVariableSchema(), agent.getPromptVariables());
        List<String> layers = new ArrayList<>();
        layers.add("TEMPLATE:v" + version.getVersionNo());
        if (StringUtils.isNotBlank(agent.getSystemPrompt())) {
            rendered = rendered + "\n\n## 补充指令\n" + agent.getSystemPrompt();
            layers.add("AGENT_SUPPLEMENT");
        }
        return new Compiled(rendered, sha256(rendered), templateId, versionId, layers);
    }

    /** 强类型变量校验与占位符替换 */
    private String render(String content, String schemaJson, String variablesJson) {
        String template = content == null ? "" : content;
        Map<String, Object> values = asMap(variablesJson);
        if (StringUtils.isNotBlank(schemaJson)) {
            Map<String, Object> schema = asMap(schemaJson);
            Object varsRaw = schema.get("vars");
            if (varsRaw instanceof List<?> definitions) {
                for (Object item : definitions) {
                    if (!(item instanceof Map<?, ?> def)) {
                        continue;
                    }
                    String name = text(def.get("name"));
                    if (StringUtils.isBlank(name)) {
                        continue;
                    }
                    Object value = values.get(name);
                    if (value == null) {
                        value = def.get("default");
                    }
                    Object requiredRaw = def.get("required");
                    boolean required = Boolean.parseBoolean(String.valueOf(requiredRaw == null ? false : requiredRaw));
                    if (required && (value == null || String.valueOf(value).isBlank())) {
                        throw new ServiceException("Prompt 变量[" + name + "]为必填项");
                    }
                    String type = StringUtils.defaultIfBlank(text(def.get("type")), "string");
                    Object normalized = normalize(name, type, value, def.get("enum"));
                    template = template.replace("{{" + name + "}}", str(normalized));
                }
            }
        }
        // 无契约时也允许直接按变量 Map 替换
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", str(entry.getValue()));
        }
        return template;
    }

    private Object normalize(String name, String type, Object value, Object enumRaw) {
        if (value == null) {
            return "";
        }
        String text = str(value);
        if ("number".equals(type)) {
            try {
                return new java.math.BigDecimal(text).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                throw new ServiceException("Prompt 变量[" + name + "]需要数字类型");
            }
        }
        if ("boolean".equals(type) && !("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
            throw new ServiceException("Prompt 变量[" + name + "]需要布尔类型");
        }
        if ("enum".equals(type) && enumRaw instanceof List<?> allowed
            && allowed.stream().noneMatch(v -> text.equals(str(v)))) {
            throw new ServiceException("Prompt 变量[" + name + "]取值不在枚举范围内");
        }
        return text;
    }

    private void validateSchema(String schemaJson) {
        if (StringUtils.isBlank(schemaJson)) {
            return;
        }
        try {
            MAPPER.readValue(schemaJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new ServiceException("变量契约不是合法 JSON 对象");
        }
    }

    private Map<String, Object> asMap(String json) {
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ServiceException("变量值不是合法 JSON 对象");
        }
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ServiceException("内容摘要计算失败");
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long currentUserId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
