package com.forgeagent.agent.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 运营看板概览视图对象
 * <p>单接口按角色裁剪 section:平台管理员得到全平台口径,
 * 空间管理员得到其管辖空间口径;无权限的 section 为 null,前端按有无渲染。</p>
 *
 * @author ForgeAgent
 */
@Data
public class DashboardOverviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 视角(platform 平台 / workspace 空间)
     */
    private String scope;

    /**
     * 数据截止时间
     */
    private String dataEndTime;

    /**
     * KPI 指标区
     */
    private KpiSection kpi;

    /**
     * Token 趋势(近 14 天)
     */
    private List<TrendPoint> trend;

    /**
     * Agent 用量排行(近 14 天 Top10)
     */
    private List<RankItem> agentRank;

    /**
     * 待办事项区
     */
    private TodoSection todo;

    /**
     * 消息反馈区(点赞/点踩与满意度)
     */
    private FeedbackSection feedback;

    /**
     * KPI 指标区
     */
    @Data
    public static class KpiSection implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 工作空间数(平台视角)
         */
        private Long workspaceCount;

        /**
         * 成员数
         */
        private Long memberCount;

        /**
         * Agent 总数
         */
        private Long agentCount;

        /**
         * 草稿 Agent 数
         */
        private Long draftCount;

        /**
         * 已发布 Agent 数
         */
        private Long publishedCount;

        /**
         * 已停用 Agent 数
         */
        private Long disabledCount;

        /**
         * 会话数
         */
        private Long sessionCount;

        /**
         * 消息数
         */
        private Long messageCount;

        /**
         * 累计 Token
         */
        private Long totalTokens;

        /**
         * 累计调用次数
         */
        private Long callCount;
    }

    /**
     * 趋势点
     */
    @Data
    public static class TrendPoint implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String statDate;
        private Long totalTokens;
        private Long callCount;
    }

    /**
     * 排行项
     */
    @Data
    public static class RankItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String name;
        private Long totalTokens;
        private Long callCount;
        private Double ratio;
    }

    /**
     * 待办事项区
     */
    @Data
    public static class TodoSection implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 待审核市场条目数(平台视角)
         */
        private Long reviewPendingCount;

        /**
         * 待处理审批单数
         */
        private Long approvalPendingCount;

        /**
         * 工作流失败运行数
         */
        private Long workflowFailCount;

        /**
         * 触发器失败记录数
         */
        private Long triggerFailCount;
    }

    /**
     * 消息反馈区(助手消息点赞/点踩,运营看板聚合)
     */
    @Data
    public static class FeedbackSection implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 累计点赞数
         */
        private Long likeCount;

        /**
         * 累计点踩数
         */
        private Long dislikeCount;

        /**
         * 今日点赞数
         */
        private Long likeCountToday;

        /**
         * 今日点踩数
         */
        private Long dislikeCountToday;

        /**
         * 满意度(%,点赞/(点赞+点踩),无反馈时为 null)
         */
        private Double satisfaction;
    }
}
