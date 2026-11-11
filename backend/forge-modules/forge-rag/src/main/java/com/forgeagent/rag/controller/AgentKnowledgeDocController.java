package com.forgeagent.rag.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.forgeagent.rag.domain.bo.KnowledgeChunkEditBo;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import com.forgeagent.rag.domain.vo.AgentKbChunkVo;
import com.forgeagent.rag.domain.vo.AgentKnowledgeDocumentVo;
import com.forgeagent.rag.service.IAgentKnowledgeDocumentService;
import com.forgeagent.common.core.domain.PageResult;
import com.forgeagent.common.core.domain.R;
import com.forgeagent.common.log.annotation.Log;
import com.forgeagent.common.log.enums.BusinessType;
import com.forgeagent.common.mybatis.core.page.PageQuery;
import com.forgeagent.common.web.core.BaseController;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
/**
 * 知识库文档管理 Controller
 *
 * @author ForgeAgent
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent/knowledge/doc")
public class AgentKnowledgeDocController extends BaseController {

    private final IAgentKnowledgeDocumentService documentService;

    /**
     * 分页查询知识库文档
     */
    @SaCheckPermission("agent:knowledgeDoc:list")
    @GetMapping("/list")
    public R<PageResult<AgentKnowledgeDocumentVo>> list(@Validated PageQuery pageQuery,
                                                        @NotNull(message = "知识库ID不能为空") Long knowledgeId) {
        return R.ok(documentService.queryPageList(knowledgeId, pageQuery));
    }

    /**
     * 上传文档(异步解析向量化)
     */
    @SaCheckPermission("agent:knowledgeDoc:upload")
    @Log(title = "知识库文档", businessType = BusinessType.INSERT)
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Long> upload(@NotNull(message = "知识库ID不能为空") Long knowledgeId,
                          @RequestPart("file") MultipartFile file) {
        return R.ok(documentService.upload(knowledgeId, file));
    }

    /**
     * 失败文档重试
     */
    @SaCheckPermission("agent:knowledgeDoc:retry")
    @Log(title = "知识库文档", businessType = BusinessType.UPDATE)
    @PostMapping("/retry/{id}")
    public R<Void> retry(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        return toAjax(documentService.retry(id));
    }

    /**
     * 删除文档(级联删除分块与向量)
     */
    @SaCheckPermission("agent:knowledgeDoc:remove")
    @Log(title = "知识库文档", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public R<Void> remove(@NotEmpty(message = "主键不能为空") @PathVariable Long[] ids) {
        return toAjax(documentService.deleteByIds(List.of(ids)));
    }

    /**
     * 查询文档分块列表
     */
    @SaCheckPermission("agent:knowledgeDoc:query")
    @GetMapping("/chunks/{documentId}")
    public R<List<AgentKbChunkVo>> chunks(@NotNull(message = "文档ID不能为空") @PathVariable Long documentId) {
        return R.ok(documentService.listChunks(documentId));
    }

    /**
     * 编辑分块内容(自动重新向量化)
     */
    @SaCheckPermission("agent:knowledgeDoc:chunk")
    @Log(title = "知识库分块", businessType = BusinessType.UPDATE)
    @PutMapping("/chunk")
    public R<Void> editChunk(@Validated @RequestBody KnowledgeChunkEditBo bo) {
        return toAjax(documentService.updateChunkContent(bo));
    }

    /**
     * 启用/禁用分块(禁用不参与检索)
     */
    @SaCheckPermission("agent:knowledgeDoc:chunk")
    @Log(title = "知识库分块", businessType = BusinessType.UPDATE)
    @PutMapping("/chunk/enabled")
    public R<Void> changeChunkEnabled(@RequestParam @NotNull Long id,
                                      @RequestParam @NotNull Boolean enabled) {
        return toAjax(documentService.changeChunkEnabled(id, enabled));
    }

    /**
     * 下载文档原文件
     */
    @SaCheckPermission("agent:knowledgeDoc:query")
    @GetMapping("/download/{id}")
    public ResponseEntity<FileSystemResource> download(@NotNull(message = "主键不能为空") @PathVariable Long id) {
        AgentKnowledgeDocumentVo doc = documentService.queryById(id);
        Path path = documentService.resolveFilePath(id);
        FileSystemResource resource = new FileSystemResource(path);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        String encoded = URLEncoder.encode(doc.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
            .contentLength(doc.getFileSize())
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(resource);
    }
}
