package com.forgeagent.agent.service.governance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.agent.domain.bo.PromptGenerateBo;
import com.forgeagent.agent.domain.vo.PromptGenerateVo;
import com.forgeagent.agentcore.domain.AgentModel;
import com.forgeagent.agentcore.mapper.AgentModelMapper;
import com.forgeagent.agentcore.service.ResourceAccessGuard;
import com.forgeagent.agentcore.service.UtilityAgents;
import com.forgeagent.common.core.utils.StringUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * C4 指令自动生成:业务描述 → 结构化 system prompt 草稿。
 *
 * <p>复刻项目既有的"一次 LLM 调用 + JSON 截取解析 + 失败降级"范式:
 * 输出不可解析时返回 {@code ok=false},由人继续手工编写,不阻断页面。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptAuthorService {

    /** 生成约束:只输出 JSON,避免模型返回 Markdown 代码块 */
    private static final String SYSTEM_PROMPT = """
        你是资深 AI Agent 提示词工程师。根据业务信息生成一份 system prompt 草稿。
        只输出一个 JSON 对象,不要任何解释、前后缀或 Markdown 代码块。格式:
        {"role":"角色定位(不超过80字)",
         "task":"核心任务与工作流程(不超过400字,写清步骤)",
         "constraints":["约束1","约束2","约束3"],
         "outputFormat":"输出格式要求(不超过200字)"}
        constraints 给 3~6 条,必须具体可执行,禁止"保持专业""注意礼貌"这类空话。
        """;

    private static final int DEFAULT_MAX_CHARS = 3000;
    private static final int HARD_MAX_CHARS = 5000;

    private final AgentModelMapper modelMapper;
    private final ResourceAccessGuard resourceGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromptGenerateVo generate(PromptGenerateBo bo) {
        PromptGenerateVo vo = new PromptGenerateVo();
        if (bo == null || bo.getWorkspaceId() == null) {
            vo.setOk(false);
            vo.setMessage("缺少 workspaceId");
            return vo;
        }
        if (bo.getModelId() == null) {
            vo.setOk(false);
            vo.setMessage("请先选择生成所用模型");
            return vo;
        }
        if (StringUtils.isBlank(bo.getPurpose()) || bo.getPurpose().trim().length() < 5) {
            vo.setOk(false);
            vo.setMessage("请用至少 5 个字描述这个 Agent 要做什么");
            return vo;
        }
        try {
            AgentModel model = modelMapper.selectById(bo.getModelId());
            if (model == null) {
                vo.setOk(false);
                vo.setMessage("模型不存在或已删除");
                return vo;
            }
            // 与聊天/评测同口径:私有模型须属于该空间,平台共享模型可读
            resourceGuard.assertReadableBy(model.getWorkspaceId(), bo.getWorkspaceId());
            if (StringUtils.isBlank(model.getBaseUrl()) || StringUtils.isBlank(model.getApiKey())
                || StringUtils.isBlank(model.getModelName())) {
                vo.setOk(false);
                vo.setMessage("模型配置不完整(缺少地址/密钥/模型标识)");
                return vo;
            }
            String raw = UtilityAgents.build("prompt-author", SYSTEM_PROMPT, model)
                .call(List.of(Msg.builder().name("user").role(MsgRole.USER)
                    .textContent(buildUserPrompt(bo)).build()))
                .block(Duration.ofSeconds(60))
                .getTextContent();
            Map<String, Object> sections = parseSections(raw);
            if (sections == null) {
                vo.setOk(false);
                vo.setMessage("模型返回内容无法解析为结构化提示词,请重试或手工编写");
                return vo;
            }
            String content = assemble(sections, bo.getMaxChars());
            vo.setOk(true);
            vo.setSections(sections);
            vo.setContent(content);
            vo.setEstimatedTokens(Math.max(1, content.length() / 4));
            vo.setModelName(model.getModelName());
            return vo;
        } catch (Exception e) {
            log.warn("[Prompt生成] 失败 modelId={}: {}", bo.getModelId(), e.getMessage());
            vo.setOk(false);
            vo.setMessage("生成失败:" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return vo;
        }
    }

    private String buildUserPrompt(PromptGenerateBo bo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Agent 名称:").append(StringUtils.defaultIfBlank(bo.getAgentName(), "未命名")).append('\n');
        sb.append("业务用途:").append(bo.getPurpose().trim()).append('\n');
        if (StringUtils.isNotBlank(bo.getTone())) {
            sb.append("语气风格:").append(bo.getTone().trim()).append('\n');
        }
        if (StringUtils.isNotBlank(bo.getBoundaries())) {
            sb.append("边界与禁止事项:").append(bo.getBoundaries().trim()).append('\n');
        }
        sb.append("请输出 JSON。");
        return sb.toString();
    }

    /** 截取首个 { 到末个 } 解析,失败返回 null(与评测/FAQ 生成同一范式) */
    private Map<String, Object> parseSections(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw.substring(start, end + 1),
                new TypeReference<Map<String, Object>>() {
                });
            String role = str(parsed.get("role"));
            String task = str(parsed.get("task"));
            if (StringUtils.isBlank(role) || StringUtils.isBlank(task)) {
                return null;
            }
            Object constraints = parsed.get("constraints");
            if (!(constraints instanceof List<?>) || ((List<?>) constraints).isEmpty()) {
                parsed.put("constraints", List.of("严格依据已知信息回答,不确定时明确说明"));
            }
            if (StringUtils.isBlank(str(parsed.get("outputFormat")))) {
                parsed.put("outputFormat", "中文回答,先结论后依据,必要时分点。");
            }
            return parsed;
        } catch (Exception e) {
            log.debug("[Prompt生成] JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 拼装为可直接使用的 system prompt;超长按上限截断并留提示 */
    @SuppressWarnings("unchecked")
    private String assemble(Map<String, Object> sections, Integer maxChars) {
        int limit = maxChars == null || maxChars <= 0 ? DEFAULT_MAX_CHARS : Math.min(maxChars, HARD_MAX_CHARS);
        StringBuilder sb = new StringBuilder();
        sb.append("## 角色\n").append(str(sections.get("role"))).append("\n\n");
        sb.append("## 任务\n").append(str(sections.get("task"))).append("\n\n");
        sb.append("## 约束\n");
        Object constraints = sections.get("constraints");
        if (constraints instanceof List<?> list) {
            for (Object item : list) {
                String text = str(item);
                if (StringUtils.isNotBlank(text)) {
                    sb.append("- ").append(text).append('\n');
                }
            }
        }
        sb.append("\n## 输出格式\n").append(str(sections.get("outputFormat")));
        String content = sb.toString().trim();
        if (content.length() > limit) {
            content = content.substring(0, limit) + "\n(内容超出长度上限已截断,建议精简后使用)";
        }
        return content;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
