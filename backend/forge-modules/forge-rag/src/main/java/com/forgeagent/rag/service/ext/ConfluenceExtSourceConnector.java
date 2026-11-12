package com.forgeagent.rag.service.ext;

import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R5-4 Confluence 来源(SPI 骨架)。
 *
 * <p>本轮不接真实站点:实现留空并明确报错。接入时替换本类实现
 * (按 space/page 列页面 → 拉 storage 格式正文 → 转为纯文本),业务层零改动。</p>
 *
 * @author ForgeAgent
 */
@Component
public class ConfluenceExtSourceConnector implements ExtSourceConnector {

    @Override
    public String type() {
        return "CONFLUENCE";
    }

    @Override
    public List<ExtDocument> fetch(String configJson) {
        throw new ServiceException("Confluence 来源尚未接入(SPI 已就绪:实现本类 fetch 即可启用)");
    }
}
