package com.forgeagent.agent.api.customerservice;

import com.forgeagent.agent.domain.vo.ChatSendVo;
import com.forgeagent.agent.service.StreamEmitter;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;

import java.util.List;
import java.util.Map;

/**
 * 智能客服 → Agent 执行域的对话契约(S0)。
 *
 * <p>客服访客没有 Sa-Token 登录态,故归属信息经 {@link CsChatRequest} 显式传入,
 * 由 {@code ChatSendBo.callerUserId / callerWorkspaceId} 在对话链内旁路登录态校验;
 * 不使用 ThreadLocal 或 SPI,避免流式执行跨线程时上下文丢失。</p>
 *
 * <p>实现位于 forge-agent 模块, forge-customer-service 只依赖本接口。</p>
 */
public interface CsChatFacade {

    /** 阻塞式一轮对话(客服机器人自助 / 管理端兜底)。 */
    ChatSendVo send(CsChatRequest request);

    /** 流式一轮对话,事件经 StreamEmitter 下发(与控制台聊天页同一套事件语义)。 */
    void stream(CsChatRequest request, StreamEmitter emitter);

    /** 中断指定会话正在进行的生成(写跨实例取消令牌)。 */
    void interrupt(Long sessionId);

    /**
     * 判断客服接待 Agent 是否装配了可执行工具(平台工具或 MCP)。
     *
     * <p>批次 A(A1B 过渡闸门):知识零命中不再是绝对兜底闸门——Agent 配了工具时,
     * 查询类意图应放行模型走工具应答,而不是直接吃兜底话术。未配工具或查询异常时
     * 返回 false,调用方维持原兜底口径(fail-closed 与 D8 同向)。</p>
     */
    boolean hasExecutableTools(Long agentId);

    /**
     * D1 灰度:会话创建时判定该访客是否命中灰度,返回要锁定的配置版本号。
     *
     * <p>判定只做一次并由调用方写入 {@code agent_cs_conversation.pinned_version},
     * 同会话后续轮次用同一版本(会话锁版本,ADR-03)。命中条件
     * {@code hash(visitorId)%100 < rollout_percent};灰度关闭、无发布版本或查询异常
     * 一律返回 null(走现行版),fail-safe 不阻断会话创建。</p>
     */
    Integer decidePinnedVersion(Long agentId, Long visitorId);

    /**
     * 客服对话请求。
     *
     * @param sessionId          客服会话对应的 agent_session.id(新建时为空)
     * @param agentId            客服机器人 AgentID(sessionId 为空时必填)
     * @param content            访客消息
     * @param callerUserId       渠道服务账号用户ID,非空时旁路 LoginHelper
     * @param callerWorkspaceId  渠道归属空间ID,非空时用 ResourceAccessGuard 纯函数校验
     * @param visitorId          访客ID(Trace / 归属留痕)
     * @param channelCode        渠道编码
     * @param ticketId           关联工单ID(可空)
     * @param prefetched         A2 预取检索结果(可空;链路 KB 集合一致时整体复用,免二次检索)
     * @param guardContext       C2 工具护栏上下文(可空;非空时对话链对平台工具启用客服护栏)
     */
    record CsChatRequest(Long sessionId, Long agentId, String content,
                         Long callerUserId, Long callerWorkspaceId,
                         Long visitorId, String channelCode, Long ticketId,
                         PrefetchedRetrieval prefetched, GuardContext guardContext) {

        /** 兼容入口:不带预取检索与护栏上下文,链路自行执行检索(控制台口径不变)。 */
        public CsChatRequest(Long sessionId, Long agentId, String content,
                             Long callerUserId, Long callerWorkspaceId,
                             Long visitorId, String channelCode, Long ticketId) {
            this(sessionId, agentId, content, callerUserId, callerWorkspaceId,
                visitorId, channelCode, ticketId, null, null);
        }

        /** 兼容入口:带预取检索不带护栏上下文。 */
        public CsChatRequest(Long sessionId, Long agentId, String content,
                             Long callerUserId, Long callerWorkspaceId,
                             Long visitorId, String channelCode, Long ticketId,
                             PrefetchedRetrieval prefetched) {
            this(sessionId, agentId, content, callerUserId, callerWorkspaceId,
                visitorId, channelCode, ticketId, prefetched, null);
        }

        public static CsChatRequest of(Long sessionId, Long agentId, String content,
                                      Long callerUserId, Long callerWorkspaceId) {
            return new CsChatRequest(sessionId, agentId, content, callerUserId,
                callerWorkspaceId, null, null, null, null, null);
        }
    }

    /**
     * C2:客服工具护栏上下文(仅客服链路非空,经瞬态字段穿透到工具装饰层)。
     *
     * <p>identityBindingEnabled 由客服侧按渠道 {@code identity_binding} 配置解析——
     * 归属校验下推业务系统(ADR-02):平台只强制传递访客凭证并保证不可绕过,
     * 未配置身份绑定的渠道 WRITE/HIGH_RISK 工具一律 deny(fail-closed)。</p>
     */
    record GuardContext(Long conversationId, Long visitorId, Long workspaceId,
                        String channelCode, boolean identityBindingEnabled,
                        Integer pinnedVersion) {

        /** 兼容入口:不带灰度版本(存量会话/工具护栏单测)。 */
        public GuardContext(Long conversationId, Long visitorId, Long workspaceId,
                            String channelCode, boolean identityBindingEnabled) {
            this(conversationId, visitorId, workspaceId, channelCode, identityBindingEnabled, null);
        }
    }

    /**
     * A2:客服预检产出的链路同构检索结果(批次 A 检索复用)。
     *
     * <p>由 {@code CsKnowledgeGateway.retrieve} 经 ChatRetrievalSupport 完整管线
     * (阈值 / 跨库 RRF / 邻块扩展 / 父块注入 / token 预算)产出,链路侧以
     * {@code knowledgeIds} 与 Agent 绑定 KB 集合做等价性核对,不一致即丢弃预取;
     * {@code query} 是预检实际检索用的问题(含图谱扩展),复用时链路不再改写。</p>
     */
    record PrefetchedRetrieval(String query, List<Long> knowledgeIds,
                               List<KnowledgeSearchHitVo> hits,
                               Map<Long, String> contextByChunk) {
    }
}
