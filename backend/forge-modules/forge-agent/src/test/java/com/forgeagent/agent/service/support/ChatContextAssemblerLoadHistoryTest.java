package com.forgeagent.agent.service.support;

import com.forgeagent.agent.domain.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * M1 / L2 闭环回归:摘要水位过滤 + token 预算裁剪 + 条数兜底。
 *
 * <p>只测 {@code loadHistory} 这条纯装配逻辑,其余构造器依赖传 null。</p>
 *
 * @author ForgeAgent
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ChatContextAssemblerLoadHistoryTest {

    /** 构造器注入:字段增减由 Mockito 适配,不手写参数列表 */
    @Mock
    private ChatRetrievalSupport retrievalSupport;

    @InjectMocks
    private ChatContextAssembler assembler;

    @BeforeEach
    void stubTruncate() {
        lenient().when(retrievalSupport.truncateToTokens(anyString(), anyInt())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            int max = inv.getArgument(1);
            return s.substring(0, Math.min(s.length(), Math.max(1, max)));
        });
    }

    private static AgentMessage msg(long id, String role, String content) {
        AgentMessage m = new AgentMessage();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static List<AgentMessage> messages(int count, int charsPerMessage) {
        List<AgentMessage> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(msg(i, i % 2 == 0 ? "assistant" : "user", "内".repeat(charsPerMessage) + i));
        }
        return list;
    }

    @Test
    void summaryWatermarkFiltersOlderMessages() {
        ChatContextAssembler.HistoryResult r = assembler.loadHistory(
            messages(6, 10), 99L, 3L, 10000, 40);
        assertEquals(3, r.turns().size());
        assertEquals(3, r.totalTurns());
        assertEquals(0, r.droppedTurns());
        assertTrueTail(r, "内".repeat(10) + "4", "内".repeat(10) + "5", "内".repeat(10) + "6");
    }

    @Test
    void tokenBudgetDropsOldestFirstAndAlwaysKeepsNewest() {
        ChatContextAssembler.HistoryResult r = assembler.loadHistory(
            messages(5, 100), 99L, 0L, 250, 40);
        assertEquals(2, r.turns().size(), "每条约 104 tokens,250 预算只放得下最近 2 条");
        assertEquals(3, r.droppedTurns());
        assertEquals("内".repeat(100) + "5", r.turns().getLast().content());
    }

    @Test
    void excludesCurrentRoundUserMessageAndBlankContent() {
        List<AgentMessage> list = new ArrayList<>(messages(4, 10));
        list.add(msg(5, "user", "本轮问题"));
        list.add(msg(6, "assistant", "   "));
        ChatContextAssembler.HistoryResult r = assembler.loadHistory(list, 5L, 0L, 10000, 40);
        assertEquals(4, r.turns().size());
        assertEquals(4, r.totalTurns());
    }

    @Test
    void maxTurnsIsOnlyAFallbackCap() {
        ChatContextAssembler.HistoryResult r = assembler.loadHistory(
            messages(10, 2), 99L, 0L, 10000, 3);
        assertEquals(3, r.turns().size());
        assertEquals(7, r.droppedTurns());
    }

    private static void assertTrueTail(ChatContextAssembler.HistoryResult r, String... expected) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], r.turns().get(i).content());
        }
    }
}
