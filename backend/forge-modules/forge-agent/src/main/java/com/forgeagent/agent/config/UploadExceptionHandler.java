package com.forgeagent.agent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.domain.R;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 上传大小超限异常处理:Servlet 在进入 Controller 前即拒绝请求,需单独给出可读提示
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class UploadExceptionHandler {

    private final AgentChatProperties chatProperties;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("[上传] 文件大小超出限制: {}", e.getMessage());
        return R.fail("上传文件大小超出限制,单个文件最大 " + chatProperties.getFileMaxSizeMb() + "MB");
    }
}
