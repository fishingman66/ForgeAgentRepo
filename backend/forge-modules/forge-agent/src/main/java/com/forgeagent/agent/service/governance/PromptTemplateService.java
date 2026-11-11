package com.forgeagent.agent.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agentcore.domain.AgentInfo;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;

    public PromptTemplateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> listTemplates(Long workspaceId) {
        return jdbc.queryForList("""
                SELECT id,workspace_id,name,description,category,stable_version_id,status,create_time
                FROM agent_prompt_template WHERE del_flag=0 AND (workspace_id=? OR workspace_id=0)
                ORDER BY id DESC
                """, workspaceId == null ? 0L : workspaceId);
    }

    public Map<String, Object> getTemplate(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM agent_prompt_template WHERE id=? AND del_flag=0", id);
        if (rows.isEmpty()) {
            throw new ServiceException("Prompt 模板不存在");
        }
        return rows.getFirst();
    }

    public Long saveTemplate(Map<String, Object> body) {
        Long id = longOf(body.get("id"));
        String name = StringUtils.defaultIfBlank(text(body.get("name")), "未命名模板");
        String content = text(body.get("content"));
        if (StringUtils.isBlank(content)) {
            throw new ServiceException("模板内容不能为空");
        }
        Long workspaceId = longOf(body.get("workspaceId"));
        if (workspaceId == null) {
            throw new ServiceException("工作空间不能为空");
        }
        String schema = text(body.get("variableSchema"));
        validateSchema(schema);
        if (id == null) {
            jdbc.update("""
                    INSERT INTO agent_prompt_template
                      (workspace_id,name,description,category,content,variable_schema,status,create_by,create_time)
                    VALUES (?,?,?,?,?,?,'draft',?,NOW())
                    """, workspaceId, name, text(body.get("description")),
                StringUtils.defaultIfBlank(text(body.get("category")), "system"),
                content, schema, currentUserId());
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        getTemplate(id);
        jdbc.update("""
                UPDATE agent_prompt_template SET name=?,description=?,category=?,content=?,
                  variable_schema=?,update_time=NOW() WHERE id=?
                """, name, text(body.get("description")),
            StringUtils.defaultIfBlank(text(body.get("category")), "system"),
            content, schema, id);
        return id;
    }

    public List<Map<String, Object>> listVersions(Long templateId) {
        return jdbc.queryForList("""
                SELECT id,template_id,version_no,content_hash,remark,create_by,create_time
                FROM agent_prompt_version WHERE template_id=? ORDER BY version_no DESC
                """, templateId);
    }

    public Map<String, Object> getVersion(Long versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM agent_prompt_version WHERE id=?", versionId);
        if (rows.isEmpty()) {
            throw new ServiceException("模板版本不存在");
        }
        return rows.getFirst();
    }

    /** 发布不可变版本;asStable=true 时同步设为模板稳定版 */
    public Long publishVersion(Long templateId, String content, String schema,
                               String remark, boolean asStable) {
        if (StringUtils.isBlank(content)) {
            throw new ServiceException("模板内容不能为空");
        }
        validateSchema(schema);
        Integer next = jdbc.queryForObject(
            "SELECT COALESCE(MAX(version_no),0)+1 FROM agent_prompt_version WHERE template_id=?",
            Integer.class, templateId);
        int versionNo = next == null ? 1 : next;
        jdbc.update("""
                INSERT INTO agent_prompt_version
                  (template_id,version_no,content,variable_schema,content_hash,remark,create_by,create_time)
                VALUES (?,?,?,?,?,?,?,NOW())
                """, templateId, versionNo, content, schema, sha256(content),
            remark, currentUserId());
        Long versionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE agent_prompt_template SET status='published',update_time=NOW() WHERE id=?",
            templateId);
        if (asStable) {
            setStable(templateId, versionId);
        }
        return versionId;
    }

    public void setStable(Long templateId, Long versionId) {
        getTemplate(templateId);
        getVersion(versionId);
        jdbc.update("UPDATE agent_prompt_template SET stable_version_id=?,update_time=NOW() WHERE id=?",
            versionId, templateId);
    }

    /** 编译预览:返回渲染结果、变量契约校验结果与内容摘要 */
    public Map<String, Object> preview(Long versionId, String variablesJson) {
        Map<String, Object> version = getVersion(versionId);
        String rendered = render(text(version.get("content")),
            text(version.get("variable_schema")), variablesJson);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", rendered);
        result.put("contentHash", sha256(rendered));
        result.put("layers", List.of("TEMPLATE"));
        result.put("estimatedTokens", Math.max(1, rendered.length() / 4));
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
            List<Long> stable = jdbc.queryForList(
                "SELECT stable_version_id FROM agent_prompt_template WHERE id=? AND del_flag=0",
                Long.class, templateId);
            versionId = stable.isEmpty() ? null : stable.getFirst();
        }
        if (versionId == null) {
            throw new ServiceException("模板尚未设置稳定版,无法以 FOLLOW_STABLE 模式运行");
        }
        Map<String, Object> version = getVersion(versionId);
        String rendered = render(text(version.get("content")),
            text(version.get("variable_schema")), agent.getPromptVariables());
        List<String> layers = new ArrayList<>();
        layers.add("TEMPLATE:v" + text(version.get("version_no")));
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
