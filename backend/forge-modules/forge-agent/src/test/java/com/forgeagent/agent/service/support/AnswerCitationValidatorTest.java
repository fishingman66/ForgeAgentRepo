package com.forgeagent.agent.service.support;

import com.forgeagent.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4-2 引用校验器对抗用例:越界引用、链接/图片/代码不算、零召回禁引用
 *
 * @author ForgeAgent
 */
@Tag("dev")
class AnswerCitationValidatorTest {

    @Test
    void inRangeCitationsPass() {
        AnswerCitationValidator.validate("根据资料 [1] 与 [2] 的说明……", 2);
        assertEquals(List.of(1, 2), AnswerCitationValidator.extract("根据资料 [1] 与 [2] 的说明"));
    }

    @Test
    void outOfRangeCitationRejected() {
        // 只有 2 条资料却引用 [5]:硬门禁拒绝
        ServiceException e = assertThrows(ServiceException.class,
            () -> AnswerCitationValidator.validate("结论见 [5]。", 2));
        assertTrue(e.getMessage().contains("[5]"), e.getMessage());
        assertTrue(e.getMessage().contains("1~2"), e.getMessage());
    }

    @Test
    void zeroRecusalForbidsAnyCitation() {
        ServiceException e = assertThrows(ServiceException.class,
            () -> AnswerCitationValidator.validate("知识库里没有,通用说法见 [1]。", 0));
        assertTrue(e.getMessage().contains("不得出现任何"), e.getMessage());
        // 未绑定知识库(referenceCount<0)跳过校验
        AnswerCitationValidator.validate("随便说 [99]", -1);
    }

    @Test
    void linksImagesAndCodeDoNotCount() {
        String text = "链接 [5](http://x/a) 与图片 ![9](x.png) 不算;代码 `[7]` 也不算;正文 [3] 算";
        assertEquals(List.of(3), AnswerCitationValidator.extract(text));
        // 因此校验不会因链接里的 [5] 误伤
        AnswerCitationValidator.validate(text, 3);
    }

    @Test
    void codeFenceContentIgnored() {
        String text = "正文引用 [1]。\n```\n示例 [8]\n```\n结束";
        assertEquals(List.of(1), AnswerCitationValidator.extract(text));
        AnswerCitationValidator.validate(text, 2);
        assertThrows(ServiceException.class,
            () -> AnswerCitationValidator.validate("```\n[1]\n```\n正常 [2]", 1));
    }

    @Test
    void noCitationPassesEverything() {
        AnswerCitationValidator.validate("知识库中暂无相关资料。", 0);
        AnswerCitationValidator.validate(null, 2);
        AnswerCitationValidator.validate("", 2);
    }
}
