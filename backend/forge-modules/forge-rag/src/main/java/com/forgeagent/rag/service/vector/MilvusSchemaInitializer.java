package com.forgeagent.rag.service.vector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
/**
 * 启动时初始化向量存储(Milvus 建集合 / MySQL 只做可用性提示);
 * 任何异常仅告警,不阻断应用启动——知识库不可用不该拖垮整个平台。
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class MilvusSchemaInitializer implements ApplicationRunner {

    private final VectorStore vectorStore;

    @Override
    public void run(ApplicationArguments args) {
        try {
            vectorStore.ensureCollection();
        } catch (Exception e) {
            log.warn("[Vector] 向量存储初始化失败(知识库向量化/检索功能暂不可用): {}", e.getMessage());
        }
    }
}
