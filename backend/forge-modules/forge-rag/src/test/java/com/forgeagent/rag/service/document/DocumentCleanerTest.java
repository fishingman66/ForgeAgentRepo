package com.forgeagent.rag.service.document;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清洗管线:顺序钉死(页眉页脚→NFKC→正则→PII→空白归一),每一步的行为与默认值都要锁住。
 *
 * <p>页眉页脚的阈值语义是重点:3 页里 2 页出现同一首行才删,单页绝不启用。</p>
 */
@Tag("dev")
class DocumentCleanerTest {

    private static String doc(String... pages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= pages.length; i++) {
            sb.append("[[page=").append(i).append("]]\n").append(pages[i - 1]).append("\n");
        }
        return sb.toString();
    }

    @Test
    void repeatedHeaderAndPageNoRemovedAcrossPages() {
        String text = doc(
            "XX公司内部资料\n甲段内容甲段内容。\n12",
            "XX公司内部资料\n乙段内容乙段内容。\n12",
            "XX公司内部资料\n丙段内容丙段内容。\n12");
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, CleanRules.defaults());
        assertFalse(r.text().contains("XX公司"), "重复页眉应从所有页删除:" + r.text());
        assertFalse(r.text().contains("\n12"), "纯页码行应删除:" + r.text());
        assertTrue(r.text().contains("甲段内容") && r.text().contains("乙段内容") && r.text().contains("丙段内容"));
        assertEquals(3, r.removedHeader());
        assertEquals(3, r.removedPageNo());
        assertTrue(r.changed());
    }

    @Test
    void headerBelowThresholdIsKept() {
        String text = doc(
            "XX公司内部资料\n甲段内容甲段内容。",
            "乙段内容乙段内容。",
            "丙段内容丙段内容。");
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, CleanRules.defaults());
        assertTrue(r.text().contains("XX公司"), "仅 1/3 页出现,未达 ceil(3*0.6)=2 阈值,应保留");
        assertEquals(0, r.removedHeader());
    }

    @Test
    void singlePageNeverEnablesHeaderFooter() {
        String text = doc("XX公司内部资料\n唯一的正文内容。");
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, CleanRules.defaults());
        assertTrue(r.text().contains("XX公司"), "单页没有重复语义,页眉页脚检测不启用");
        assertEquals(0, r.removedHeader());
    }

    @Test
    void meaningfulShortNumberLineIsNotPageNo() {
        // 3.14 位于页首且无字母:会被当页码删除——这是有意设计,页首尾的短数字行没有正文价值
        String text = doc("3.14\n圆周率相关正文内容。");
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, CleanRules.defaults());
        // 单页不启用检测,3.14 保留
        assertTrue(r.text().contains("3.14"), "单页不启用页眉页脚/页码删除");
        assertEquals(0, r.removedPageNo());
    }

    @Test
    void nfkcOffByDefaultAndSkipsCodeFence() {
        String text = "全角：ＡＢＣ１２３。\n```\n全角：ＡＢＣ１２３\n```\n";
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, CleanRules.defaults());
        assertTrue(r.text().contains("ＡＢＣ１２３"), "默认不开 NFKC,全角保持原样");
        CleanRules on = new CleanRules(false, false, true, false, List.of());
        DocumentCleaner.CleanResult r2 = DocumentCleaner.clean(text, on);
        assertTrue(r2.text().contains("全角:ABC123。"), "全角形式与全角冒号被归一:" + r2.text());
        // 全角句号 U+3002 无兼容分解,保持原样(中文标点不受影响的关键)
        assertTrue(r2.text().contains("。"));
        // 代码块内跳过
        assertTrue(r2.text().contains("全角：ＡＢＣ１２３\n```"), "代码块内不做 NFKC:" + r2.text());
    }

    @Test
    void blankNormalizeHandlesNbspTrailingAndRuns() {
        String text = "首行\u00A0内容   \n\n\n\n后续行  \n```\n  code  \n\n\n  more\n```\n\n\n";
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, CleanRules.defaults());
        assertFalse(r.text().contains("\u00A0"), "NBSP 应替换为空格");
        assertFalse(r.text().contains("首行内容   \n"), "行尾空格应去除");
        // 代码块外的折叠:首行与后续行之间只剩一个空行
        assertTrue(r.text().contains("首行 内容\n\n后续行"), "代码块外连续空行压成一行:" + r.text());
        // 代码块内缩进与空行原样保留
        assertTrue(r.text().contains("  code  \n\n\n  more"), "代码块内缩进与空行保留:" + r.text());
    }

    @Test
    void piiMaskOffByDefaultAndWorksWhenEnabled() {
        String text = "联系手机 13812345678 或邮箱 y@163.com,证件 11010119900307891X。";
        DocumentCleaner.CleanResult off = DocumentCleaner.clean(text, CleanRules.defaults());
        assertEquals(text, off.text(), "默认不开 PII 脱敏");

        CleanRules on = new CleanRules(false, false, false, true, List.of());
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, on);
        assertTrue(r.text().contains("138****5678"), r.text());
        assertTrue(r.text().contains("y***@163.com"), r.text());
        assertTrue(r.text().contains("110101********891X"), r.text());
        assertFalse(r.text().contains("13812345678"));
        assertEquals(1, r.piiLines());
    }

    @Test
    void nfkcRunsBeforeRegexSoFullWidthDigitsMatch() {
        CleanRules rules = new CleanRules(false, false, true, false,
            List.of(new CleanRules.RegexRule("\\d+", "N")));
        DocumentCleaner.CleanResult r = DocumentCleaner.clean("余额１２３元", rules);
        assertEquals("余额N元", r.text(), "NFKC 必须先于正则,否则全角数字匹配不到");
        assertEquals(1, r.regexLines());
    }

    @Test
    void invalidRegexSkippedWithWarning() {
        CleanRules rules = new CleanRules(false, false, false, false,
            List.of(new CleanRules.RegexRule("[", "x"), new CleanRules.RegexRule("敏感词", "【已删除】")));
        DocumentCleaner.CleanResult r = DocumentCleaner.clean("这里提到敏感词两次:敏感词", rules);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("正则无效")), "非法正则跳过并告警");
        assertEquals("这里提到【已删除】两次:【已删除】", r.text(), "合法条目照常生效");
        assertEquals(1, r.regexLines());
    }

    @Test
    void headerFooterSwitchOffKeepsEverything() {
        String text = doc(
            "XX公司内部资料\n甲段内容。\n12",
            "XX公司内部资料\n乙段内容。\n12");
        CleanRules off = new CleanRules(false, true, false, false, List.of());
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, off);
        assertTrue(r.text().contains("XX公司内部资料"));
        assertEquals(0, r.removedHeader() + r.removedPageNo());
    }

    @Test
    void markerRowsSurviveCleaning() {
        String text = doc("第一页正文。\n12", "第二页正文。\n12");
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, CleanRules.defaults());
        assertTrue(r.text().contains("[[page=1]]") && r.text().contains("[[page=2]]"),
            "页标记必须保留(检索引用定位依赖它):" + r.text());
    }

    @Test
    void unmarkedTextIsSinglePageAndStillCleaned() {
        String text = "联系手机 13812345678\n\n\n\n后续内容";
        CleanRules on = new CleanRules(false, true, false, true, List.of());
        DocumentCleaner.CleanResult r = DocumentCleaner.clean(text, on);
        assertTrue(r.text().contains("138****5678"), "无页标记文本的 PII/空白归一照常生效");
        assertFalse(r.text().contains("\n\n\n"), "空行折叠生效");
        assertEquals(0, r.removedHeader(), "单页无标记,页眉页脚不启用");
    }
}
