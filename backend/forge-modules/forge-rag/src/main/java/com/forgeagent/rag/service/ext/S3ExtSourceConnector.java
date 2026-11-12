package com.forgeagent.rag.service.ext;

import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R5-4 S3 兼容对象存储来源(SPI 骨架)。
 *
 * <p>本轮不接真实凭据:实现留空并明确报错,避免"配了但静默不入库"。
 * 接入时替换本类实现(列对象 → 下载 → 抽文本),业务层与服务层零改动。</p>
 *
 * @author ForgeAgent
 */
@Component
public class S3ExtSourceConnector implements ExtSourceConnector {

    @Override
    public String type() {
        return "S3";
    }

    @Override
    public List<ExtDocument> fetch(String configJson) {
        throw new ServiceException("S3 来源尚未接入(SPI 已就绪:实现本类 fetch 即可启用)");
    }
}
