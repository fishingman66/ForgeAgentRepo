package com.forgeagent.agent.service;

import com.forgeagent.agentcore.domain.AgentChatAttachment;
import com.forgeagent.agentcore.domain.vo.ChatAttachmentVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collection;
import java.util.List;

/**
 * 会话图片附件 Service
 *
 * @author ForgeAgent
 */
public interface AgentChatAttachmentService {

    /**
     * 上传附件(图片落盘生成缩略图,文档解析并建立临时索引)
     */
    ChatAttachmentVo upload(MultipartFile file, Long agentId, Long sessionId);

    /**
     * 查询附件元信息(含解析状态/分块数/token数)
     */
    ChatAttachmentVo getVo(Long id);

    /**
     * 按ID获取可用附件实体
     */
    AgentChatAttachment getEntity(Long id);

    /**
     * 按ID批量查询可用附件
     */
    List<AgentChatAttachment> listByIds(Collection<Long> ids);

    /**
     * 读取附件磁盘字节(供模型图片输入)
     */
    byte[] readBytes(AgentChatAttachment att);

    /**
     * 回填附件所属会话与消息
     */
    void bindMessages(List<AgentChatAttachment> atts, Long sessionId, Long messageId);

    /**
     * 清理会话下全部附件与临时索引
     */
    void cleanupBySession(Long sessionId);
}
