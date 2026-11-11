package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsSlotProgress;
import com.forgeagent.customerservice.mapper.CsSlotProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多轮槽位收集(P1,差距分析 5.2):把「要退款」这类模糊诉求收敛成结构化信息。
 *
 * <p>抽取规则是确定性的,可离线单测:</p>
 * <ol>
 *   <li>上一轮追问的是<b>自由文本</b>槽位 → 本轮整句作为它的值;</li>
 *   <li>所有<b>带正则</b>且未填的槽位,从本轮文本里抽取(订单号这类);</li>
 *   <li>取第一个 required 未填的槽位追问;全填则结束并让调用方转人工。</li>
 * </ol>
 *
 * <p>默认关闭(`slot-filling-enabled`):开启后意图命中同名表单才启动,
 * 收集期间不跑检索与模型——客户在填信息,不需要再被模型反问一遍。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsSlotFillingService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CsSlotProgressMapper progressMapper;
    private final CsIntentService intentService;
    private final CsModuleProperties properties;

    /** 单轮结果:非空即由调用方直接回复,不再走检索/模型。 */
    public record SlotOutcome(String formName, String prompt, boolean done, Map<String, String> collected) {
    }

    /**
     * 推进一步。返回 null 表示与填槽无关(未开启、无进行中的表单、意图不匹配),调用方走正常对话。
     */
    public SlotOutcome advance(Long conversationId, Long workspaceId, String text) {
        if (!Boolean.TRUE.equals(properties.getSlotFillingEnabled())
            || conversationId == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            CsSlotProgress progress = progressMapper.selectOne(new LambdaQueryWrapper<CsSlotProgress>()
                .eq(CsSlotProgress::getConversationId, conversationId)
                .isNotNull(CsSlotProgress::getAsking)
                .orderByDesc(CsSlotProgress::getId)
                .last("limit 1"));
            if (progress == null) {
                CsIntentService.Match intent = intentService.classify(workspaceId, text, List.of());
                if (intent == null) {
                    return null;
                }
                List<CsModuleProperties.Slot> form = formOf(intent.code());
                if (form == null || form.isEmpty()) {
                    return null;
                }
                progress = new CsSlotProgress();
                progress.setConversationId(conversationId);
                progress.setFormName(intent.code());
                progress.setCollectedJson("{}");
                progress.setCreateTime(new Date());
                progress.setUpdateTime(new Date());
                progressMapper.insert(progress);
            }
            List<CsModuleProperties.Slot> form = formOf(progress.getFormName());
            if (form == null || form.isEmpty()) {
                return null;
            }
            return step(progress, form, text);
        } catch (Exception e) {
            // 填槽失败降级为正常对话,别让一个辅助能力打断访客问答
            log.warn("[CS] 槽位收集失败 conversationId={}: {}", conversationId, e.getMessage());
            return null;
        }
    }

    /** 单步抽取 + 追问(内部方法,便于以后换存储实现)。 */
    private SlotOutcome step(CsSlotProgress progress, List<CsModuleProperties.Slot> form,
                             String text) throws Exception {
        Map<String, String> collected = parse(progress.getCollectedJson());

        // 1) 上一轮追问的自由文本槽位:整句作为其值
        String asking = progress.getAsking();
        if (asking != null) {
            CsModuleProperties.Slot slot = findSlot(form, asking);
            if (slot != null && isFreeText(slot)) {
                collected.put(slot.getName(), text.trim());
            }
        }

        // 2) 带正则的未填槽位:从本轮文本抽取(订单号可能和诉求一起发过来)
        for (CsModuleProperties.Slot slot : form) {
            if (collected.containsKey(slot.getName()) || isFreeText(slot)) {
                continue;
            }
            Matcher matcher = Pattern.compile(slot.getPattern()).matcher(text);
            if (matcher.find()) {
                collected.put(slot.getName(), matcher.group());
            }
        }

        // 3) 找下一个必填未填项
        CsModuleProperties.Slot next = null;
        for (CsModuleProperties.Slot slot : form) {
            if (Boolean.TRUE.equals(slot.getRequired()) && !collected.containsKey(slot.getName())) {
                next = slot;
                break;
            }
        }
        boolean done = next == null;
        progress.setCollectedJson(MAPPER.writeValueAsString(collected));
        progress.setAsking(done ? null : next.getName());
        progress.setUpdateTime(new Date());
        progressMapper.updateById(progress);

        String prompt = done
            ? "信息已收集完整（" + summarize(form, collected) + "），正在为你转接人工处理。"
            : "请提供" + next.getLabel() + "。";
        return new SlotOutcome(progress.getFormName(), prompt, done, collected);
    }

    /** 进行中的表单名(坐席侧可据此提示"客户正在填退款表单")。 */
    public String currentForm(Long conversationId) {
        CsSlotProgress progress = progressMapper.selectOne(new LambdaQueryWrapper<CsSlotProgress>()
            .eq(CsSlotProgress::getConversationId, conversationId)
            .isNotNull(CsSlotProgress::getAsking)
            .orderByDesc(CsSlotProgress::getId).last("limit 1"));
        return progress == null ? null : progress.getFormName();
    }

    /** 收集完成或放弃后清理进度(重置会话状态)。 */
    public void reset(Long conversationId) {
        progressMapper.delete(new LambdaQueryWrapper<CsSlotProgress>()
            .eq(CsSlotProgress::getConversationId, conversationId));
    }

    /** 抽取判定(纯函数,单测锁定):上一轮追问自由文本槽位时整句赋值。 */
    public static Map<String, String> mergeFreeText(Map<String, String> collected,
                                                    List<CsModuleProperties.Slot> form,
                                                    String asking, String text) {
        Map<String, String> merged = new LinkedHashMap<>(collected == null ? Map.of() : collected);
        if (asking == null || text == null) {
            return merged;
        }
        for (CsModuleProperties.Slot slot : form) {
            if (asking.equals(slot.getName()) && isFreeText(slot)) {
                merged.put(slot.getName(), text.trim());
                break;
            }
        }
        return merged;
    }

    private static boolean isFreeText(CsModuleProperties.Slot slot) {
        return slot.getPattern() == null || slot.getPattern().isBlank();
    }

    private CsModuleProperties.Slot findSlot(List<CsModuleProperties.Slot> form, String name) {
        for (CsModuleProperties.Slot slot : form) {
            if (slot.getName().equals(name)) {
                return slot;
            }
        }
        return null;
    }

    private List<CsModuleProperties.Slot> formOf(String formName) {
        if (formName == null || properties.getSlotForms() == null) {
            return null;
        }
        return properties.getSlotForms().get(formName);
    }

    private String summarize(List<CsModuleProperties.Slot> form, Map<String, String> collected) {
        StringBuilder sb = new StringBuilder();
        for (CsModuleProperties.Slot slot : form) {
            String value = collected.get(slot.getName());
            if (value == null || value.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('，');
            }
            sb.append(slot.getLabel()).append(' ').append(value);
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    private Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(MAPPER.readValue(json, new TypeReference<Map<String, String>>() {
            }));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
