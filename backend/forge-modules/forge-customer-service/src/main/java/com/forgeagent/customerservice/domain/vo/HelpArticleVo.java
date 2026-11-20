package com.forgeagent.customerservice.domain.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

/** 帮助中心文章(公开响应):只含对外字段,绝不泄漏 workspaceId / 内部 chunk id / 检索分数。 */
@Data
public class HelpArticleVo {
    private String slug;
    private String title;
    private String summary;
    private String locale;
    private String source;
    private Date updateTime;

    /** F1 搜索命中片段:仅 search 端点填充,列表/详情为 null。前端按 hit 插值渲染(不 v-html)。 */
    private List<Segment> segments;

    /** F3:true 表示当前语言无内容,本条来自默认语言兜底,前端需提示"尚未翻译"。 */
    private boolean fallback;

    /** 一段文本是否为命中关键词。 */
    public record Segment(String text, boolean hit) {
    }
}
