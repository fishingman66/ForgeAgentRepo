package com.forgeagent.rag.service.retrieve;

import com.forgeagent.rag.service.document.TextChunker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 父块内子块偏移定位。
 *
 * <p>除了构造用例,还用**真实分块器**跑一遍:偏移算错只在真实文本上暴露,
 * 手写样例永远是对的。</p>
 */
@Tag("dev")
class ParentOffsetsTest {

    @Test
    void locateSequentialChildrenWithOverlap() {
        String parent = "abcdefghij";
        assertArrayEquals(new int[]{2, 6}, ParentOffsets.locate(parent, "cdef", 2, 0));
        assertArrayEquals(new int[]{4, 8}, ParentOffsets.locate(parent, "efgh", 2, 6));
        assertArrayEquals(new int[]{8, 10}, ParentOffsets.locate(parent, "ij", 2, 8));
    }

    @Test
    void fallsBackToWholeParentWhenCursorMisses() {
        assertArrayEquals(new int[]{0, 3}, ParentOffsets.locate("abcdef", "abc", 0, 5));
    }

    @Test
    void notFoundReturnsMinusOne() {
        assertArrayEquals(ParentOffsets.NOT_FOUND, ParentOffsets.locate("abcdef", "xyz", 2, 0));
        assertArrayEquals(ParentOffsets.NOT_FOUND, ParentOffsets.locate("abcdef", "", 2, 0));
        assertArrayEquals(ParentOffsets.NOT_FOUND, ParentOffsets.locate(null, "abc", 2, 0));
    }

    @Test
    void everyRealChunkIsLocatableInItsParent() {
        // 真实分块器的输出:带 overlap 的连续切分
        TextChunker chunker = new TextChunker();
        String parent = sampleParent();
        List<String> subs = chunker.chunk(parent, 200, 20);
        assertAllLocatable(parent, subs, 20);
    }

    @Test
    void everyRealChunkIsLocatableWithProductionParams() {
        // 与线上一致的参数(父 400 / 子 200 / 重叠 20),用多段长文本
        TextChunker chunker = new TextChunker();
        String text = sampleParent() + sampleParent() + sampleParent() + sampleParent();
        List<String> parents = chunker.chunk(text, 400, 0);
        for (String parent : parents) {
            List<String> subs = chunker.chunk(parent, 200, 20);
            assertAllLocatable(parent, subs, 20);
        }
    }

    @Test
    void everyRealChunkIsLocatableWhenParentHasHeadings() {
        // 带标题的父块:子块会被加上「【标题路径】」前缀,不再是父块的连续子串,
        // 定位必须能退化到"按开头定位",否则父子禁用裁剪会因缺偏移全部失效
        TextChunker chunker = new TextChunker();
        String parent = "# 部署\n\n## 数据库\n\n" + sampleParent() + "\n\n## 缓存\n\n" + sampleParent();
        List<String> subs = chunker.chunk(parent, 200, 20);
        assertAllLocatable(parent, subs, 20);
    }

    private void assertAllLocatable(String parent, List<String> subs, int overlap) {
        int cursor = 0;
        int located = 0;
        for (String sub : subs) {
            int[] offset = ParentOffsets.locate(parent, sub, overlap, cursor);
            if (offset[0] >= 0) {
                located++;
                // 偏移必须真正框住该子块的正文:去掉空白后逐字相等(分块器会归一化空行)
                String probe = ParentOffsets.stripHeadingPrefix(sub);
                assertEquals(stripWhitespace(probe),
                    stripWhitespace(parent.substring(offset[0], offset[1])),
                    "偏移区间应覆盖子块正文");
                cursor = offset[1];
            }
        }
        assertEquals(subs.size(), located, "每个子块都应能定位到父块内偏移");
    }

    private String stripWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }

    private String sampleParent() {
        return "第一章 代次切换\n"
            + "重建索引时应当先写入新一代分块,全部成功之后再切换指针,最后清理旧一代。"
            + "如果反过来先删后建,重建期间检索结果会整体为空,中途失败更是让知识库彻底不可用。\n"
            + "第二章 向量补偿\n"
            + "分块先落数据库,再写向量库。中间任何一次网络抖动都会让两边不一致。"
            + "写入失败的分块必须被标记出来定时重试,而不是打一行日志就当作成功。\n";
    }
}
