package com.forgeagent.rag.service.ext;

import java.util.List;

/**
 * R5-4 外部源连接器 SPI:每种来源实现一个 Bean,服务按 {@link #type()} 自动选中。
 *
 * <p>新增 S3 / Confluence 时只需实现本接口并注册 Bean,业务层零改动。</p>
 *
 * @author ForgeAgent
 */
public interface ExtSourceConnector {

    /**
     * 来源类型:URL / S3 / CONFLUENCE
     */
    String type();

    /**
     * 该连接器是否已可用。
     *
     * <p>P3 / B2:骨架实现必须返回 {@code false},让"登记阶段"就拒绝,
     * 而不是让运营配完、点同步才看到"尚未接入"。默认 {@code true}。</p>
     */
    default boolean available() {
        return true;
    }

    /**
     * 拉取条目(同步执行;实现需自行控制超时与体积)
     *
     * @param configJson 来源配置 JSON(凭据不入库明文,由部署侧注入)
     */
    List<ExtDocument> fetch(String configJson);

    /**
     * 一条外部文档
     *
     * @param externalId 外部唯一标识(URL/对象键),用于去重与增量同步
     * @param title      标题(入库文件名)
     * @param content    正文纯文本
     */
    record ExtDocument(String externalId, String title, String content) {
    }
}
