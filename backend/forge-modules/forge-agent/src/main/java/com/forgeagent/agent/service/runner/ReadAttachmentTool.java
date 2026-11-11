package com.forgeagent.agent.service.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.rag.domain.AgentKbChunk;
import com.forgeagent.rag.domain.bo.KnowledgeSearchBo;
import com.forgeagent.rag.domain.vo.KnowledgeSearchHitVo;
import com.forgeagent.rag.mapper.AgentKbChunkMapper;
import com.forgeagent.agent.service.AgentRunner;
import com.forgeagent.rag.service.IAgentKnowledgeService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话文件按需读取工具:大文件不整体注入上下文,由模型按大纲/页/工作表/关键词按需取片段
 *
 * @author ForgeAgent
 */
@Slf4j
record ReadAttachmentTool(List<AgentRunner.RunnerContext.AttachmentRef> attachments,
                          AgentKbChunkMapper chunkMapper,
                          IAgentKnowledgeService knowledgeService) implements AgentTool {

    private static final int MAX_OUTPUT_TOKENS = 1200;

    @Override
    public String getName() {
        return "read_attachment";
    }

    @Override
    public String getDescription() {
        return "读取用户本轮上传文件的内容。文件较大时上下文只注入了摘要与少量片段,"
            + "需要查看完整内容时按 mode 读取:outline(大纲/页数)、page(按PDF页码)、"
            + "sheet(按Excel工作表)、search(按关键词检索片段)。一次只读一个文件的一部分,避免重复读取";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("attachmentId", Map.of("type", "string",
            "description", "附件ID,可省略;仅一个文件时自动使用该文件"));
        props.put("mode", Map.of("type", "string", "enum", List.of("outline", "page", "sheet", "search"),
            "description", "读取模式"));
        props.put("page", Map.of("type", "integer", "description", "页码,从1开始,mode=page 时使用"));
        props.put("sheet", Map.of("type", "string", "description", "工作表名称,mode=sheet 时使用"));
        props.put("query", Map.of("type", "string", "description", "检索关键词,mode=search 时使用"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("mode"));
        return schema;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
        return Mono.fromCallable(() -> execute(input)).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(Map<String, Object> input) {
        AgentRunner.RunnerContext.AttachmentRef ref = resolve(input.get("attachmentId"));
        if (ref == null) {
            return ToolResultBlock.error("未找到对应附件,本轮无可用文件");
        }
        String mode = String.valueOf(input.getOrDefault("mode", "outline"));
        try {
            return switch (mode) {
                case "page" -> readPage(ref, parseInt(input.get("page")));
                case "sheet" -> readSheet(ref, String.valueOf(input.getOrDefault("sheet", "")));
                case "search" -> search(ref, String.valueOf(input.getOrDefault("query", "")));
                default -> outline(ref);
            };
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[ReadAttachmentTool] 读取附件 {} 失败: {}", ref.attachmentId(), message);
            return ToolResultBlock.error("读取附件失败: " + message);
        }
    }

    private AgentRunner.RunnerContext.AttachmentRef resolve(Object rawId) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        if (rawId == null || String.valueOf(rawId).isBlank()) {
            return attachments.size() == 1 ? attachments.get(0) : null;
        }
        String id = String.valueOf(rawId).trim();
        return attachments.stream()
            .filter(a -> a.attachmentId() != null && String.valueOf(a.attachmentId()).equals(id))
            .findFirst().orElse(null);
    }

    private ToolResultBlock outline(AgentRunner.RunnerContext.AttachmentRef ref) {
        StringBuilder sb = new StringBuilder();
        sb.append("文件:").append(ref.name()).append('\n');
        if (ref.pageCount() != null && ref.pageCount() > 0) {
            sb.append("页数/工作表数:").append(ref.pageCount()).append('\n');
        }
        sb.append("大纲/摘要:\n").append(ref.summary() == null ? "（无）" : ref.summary());
        return ToolResultBlock.text(sb.toString());
    }

    private ToolResultBlock search(AgentRunner.RunnerContext.AttachmentRef ref, String query) {
        if (query == null || query.isBlank()) {
            return ToolResultBlock.error("mode=search 时 query 不能为空");
        }
        KnowledgeSearchBo bo = new KnowledgeSearchBo();
        bo.setKnowledgeId(ref.knowledgeId());
        bo.setQuery(query);
        bo.setTopK(5);
        bo.setCacheable(Boolean.FALSE);
        List<KnowledgeSearchHitVo> hits = knowledgeService.search(bo);
        if (hits == null || hits.isEmpty()) {
            return ToolResultBlock.text("文件[" + ref.name() + "]中未检索到与「" + query + "」相关的内容");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("文件[").append(ref.name()).append("]中与「").append(query).append("」相关的片段:\n");
        int used = 0;
        for (KnowledgeSearchHitVo hit : hits) {
            int cost = com.forgeagent.rag.service.document.TokenEstimator.estimate(hit.getContent());
            if (used > 0 && used + cost > MAX_OUTPUT_TOKENS) {
                break;
            }
            used += cost;
            sb.append(location(hit.getContent())).append(hit.getContent()).append('\n');
        }
        return ToolResultBlock.text(sb.toString());
    }

    private ToolResultBlock readPage(AgentRunner.RunnerContext.AttachmentRef ref, int page) {
        if (page <= 0) {
            return ToolResultBlock.error("page 必须从 1 开始");
        }
        String marker = "[[page=" + page + "]]";
        return readMarkedSection(ref, marker, "第 " + page + " 页");
    }

    private ToolResultBlock readSheet(AgentRunner.RunnerContext.AttachmentRef ref, String sheet) {
        if (sheet == null || sheet.isBlank()) {
            return ToolResultBlock.error("mode=sheet 时 sheet 不能为空");
        }
        String marker = "[[sheet=" + sheet + "]]";
        return readMarkedSection(ref, marker, "工作表 " + sheet);
    }

    /**
     * 从包含指定标记的分块开始拼接,直到出现同类下一个标记,输出受 token 上限约束
     */
    private ToolResultBlock readMarkedSection(AgentRunner.RunnerContext.AttachmentRef ref,
                                              String marker, String label) {
        List<AgentKbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AgentKbChunk>()
            .eq(AgentKbChunk::getKnowledgeId, ref.knowledgeId())
            .eq(AgentKbChunk::getEnabled, 1)
            .orderByAsc(AgentKbChunk::getChunkIndex));
        int start = -1;
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).getContent() != null && chunks.get(i).getContent().contains(marker)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return ToolResultBlock.text("文件[" + ref.name() + "]中未找到" + label + ",可用 outline 查看可用页码/工作表");
        }
        String prefix = marker.substring(0, marker.indexOf('=') + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("文件[").append(ref.name()).append("] ").append(label).append(":\n");
        int used = 0;
        for (int i = start; i < chunks.size(); i++) {
            String content = chunks.get(i).getContent();
            if (i > start && content != null && content.contains(prefix)) {
                break;
            }
            int cost = com.forgeagent.rag.service.document.TokenEstimator.estimate(content);
            if (used > 0 && used + cost > MAX_OUTPUT_TOKENS) {
                sb.append("（内容较长,已截断;可继续按关键词 search 查看后续片段）\n");
                break;
            }
            used += cost;
            sb.append(content).append('\n');
        }
        return ToolResultBlock.text(sb.toString());
    }

    private String location(String content) {
        if (content == null) {
            return "";
        }
        int page = content.lastIndexOf("[[page=");
        if (page >= 0) {
            int end = content.indexOf("]]", page);
            if (end > page) {
                return "[" + content.substring(page + 2, end) + "] ";
            }
        }
        int sheet = content.lastIndexOf("[[sheet=");
        if (sheet >= 0) {
            int end = content.indexOf("]]", sheet);
            if (end > sheet) {
                return "[" + content.substring(sheet + 2, end) + "] ";
            }
        }
        return "";
    }

    private int parseInt(Object raw) {
        if (raw instanceof Number num) {
            return num.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
