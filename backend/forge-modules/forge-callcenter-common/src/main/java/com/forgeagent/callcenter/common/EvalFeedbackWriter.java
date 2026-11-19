package com.forgeagent.callcenter.common;

import java.util.List;
import java.util.Map;

/**
 * 客服 → 平台评测域写入契约(方向安全:定义在零依赖 common 层,实现在 forge-agent)。
 *
 * <p>批次 D2 引入:客服回流用例要写平台侧 {@code agent_eval_case}/{@code agent_eval_dataset},
 * 若客服模块直接依赖 agent 的 Mapper 会形成
 * {@code customer-service → agent → callcenter → customer-service} 反向依赖环。
 * 接口签名只用基本类型与 Map,common 不依赖任何业务实体。</p>
 *
 * <p>实现位于 forge-agent 模块(持有评测域 Mapper);客服侧经 Spring 注入本接口,
 * 只传参数、不碰平台表结构。</p>
 */
public interface EvalFeedbackWriter {

    /** 定位或创建该 Agent 的客服回流评测集,返回 datasetId。 */
    Long ensureDataset(Long workspaceId, Long agentId, String datasetName, String description);

    /** 写一条待确认用例;唯一键(source,source_ref_id)冲突时返回已存在的那条 id。 */
    Long insertCase(Long datasetId, String source, Long sourceRefId,
                    String question, String reference, String pendingStatus);

    /** 回流用例列表(带 workspace 归属校验)。 */
    List<Map<String, Object>> listCases(Long workspaceId, String reviewStatus, int limit);

    /** 确认或驳回一条回流用例(带 workspace 归属校验)。 */
    void review(Long caseId, Long workspaceId, boolean approve, String approvedStatus, String rejectedStatus);

    /** 待确认数量(运营页角标)。 */
    int pendingCount(Long workspaceId, String pendingStatus);

    /** 取会话最后一条指定角色的消息内容(agent_message 属 agent 域)。 */
    Map<String, Object> lastMessage(Long sessionId, String role);

    /** 统计访客发言轮数(采样门槛用)。 */
    int countVisitorTurns(Long sessionId);
}
