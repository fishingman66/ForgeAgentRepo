package com.forgeagent.callcenter.engine.ivr;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;

/**
 * IVR 节点模型(C3 / 方案 docs/07 P0-1②):多态 JSON,鉴别器 {@code type} 与
 * {@link NodeExecutor#type()} 一致——节点是数据、执行器是 Spring Bean,
 * 对齐 SmartCall NodeGranter 的插件范式。首批 9 种(script/sms/extract 等
 * 二期补,见方案裁剪清单)。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IvrNode.Start.class, name = "start"),
    @JsonSubTypes.Type(value = IvrNode.Say.class, name = "say"),
    @JsonSubTypes.Type(value = IvrNode.Answer.class, name = "answer"),
    @JsonSubTypes.Type(value = IvrNode.Received.class, name = "received"),
    @JsonSubTypes.Type(value = IvrNode.Intention.class, name = "intention"),
    @JsonSubTypes.Type(value = IvrNode.Agent.class, name = "agent"),
    @JsonSubTypes.Type(value = IvrNode.Condition.class, name = "condition"),
    @JsonSubTypes.Type(value = IvrNode.Transfer.class, name = "transfer"),
    @JsonSubTypes.Type(value = IvrNode.Hangup.class, name = "hangup"),
})
public abstract class IvrNode {

    /** 节点唯一标识(流程内)。 */
    @Getter
    @Setter
    private String id;

    /** 下一节点 id;null 表示流程终止(hangup/transfer 等由事件接管的节点)。 */
    @Getter
    @Setter
    private String nextId;

    /** 节点类型(执行器路由键),各子类返回常量。 */
    public abstract String type();

    /** 起点节点:只做跳转,无副作用。 */
    @Getter
    @Setter
    public static class Start extends IvrNode {
        @Override
        public String type() {
            return "start";
        }
    }

    /** 话术播报:text 支持 ${SpEL} 插值。 */
    @Getter
    @Setter
    public static class Say extends IvrNode {
        private String text;

        @Override
        public String type() {
            return "say";
        }
    }

    /**
     * 收音:先播 prompt(可空)再收 caller 语音,终稿或超时(静默)离开;
     * 超时走 silenceNextId(未配则 nextId)。
     */
    @Getter
    @Setter
    public static class Answer extends IvrNode {
        private String prompt;
        private Integer timeoutMs = 8000;
        private String silenceNextId;

        @Override
        public String type() {
            return "answer";
        }
    }

    /** DTMF 收号:endKey 结束符(默认 #),maxDigits 上限,超时/收满离开。 */
    @Getter
    @Setter
    public static class Received extends IvrNode {
        private String prompt;
        private String endKey = "#";
        private Integer timeoutMs = 8000;
        private Integer maxDigits = 8;

        @Override
        public String type() {
            return "received";
        }
    }

    /**
     * 意图分流:text(可空,默认取上一轮 caller 终稿变量)分类后按意图 code 跳分支;
     * 未命中走 otherNextId。
     */
    @Getter
    @Setter
    public static class Intention extends IvrNode {
        private String text;
        private java.util.Map<String, String> branches;
        private String otherNextId;

        @Override
        public String type() {
            return "intention";
        }
    }

    /** 知识库问答:question(可空,默认取 caller 终稿)未命中走 noAnswerNextId。 */
    @Getter
    @Setter
    public static class Agent extends IvrNode {
        private String question;
        private String noAnswerNextId;

        @Override
        public String type() {
            return "agent";
        }
    }

    /** 条件分支:expression 为 SpEL 布尔表达式(如 ${caller == null} / ${intent == 'refund'})。 */
    @Getter
    @Setter
    public static class Condition extends IvrNode {
        private String expression;
        private String trueNextId;
        private String falseNextId;

        @Override
        public String type() {
            return "condition";
        }
    }

    /** 转人工:进指定 ACD 队列;接通/放弃由事件回流处理,节点发完命令即终止循环。 */
    @Getter
    @Setter
    public static class Transfer extends IvrNode {
        private String queue;

        @Override
        public String type() {
            return "transfer";
        }
    }

    /** 挂机:播挂机原因由上游 say 承担;本节点只发命令并终止循环。 */
    @Getter
    @Setter
    public static class Hangup extends IvrNode {
        private String cause = "NORMAL_CLEARING";

        @Override
        public String type() {
            return "hangup";
        }
    }
}
