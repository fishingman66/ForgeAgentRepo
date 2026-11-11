package com.forgeagent.agent.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.agent.domain.AgentDatasource;
import com.forgeagent.agent.mapper.AgentDatasourceMapper;
import com.forgeagent.agent.service.IAgentDatasourceService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 问数表结构快照定时刷新任务
 *
 * <p>默认每天凌晨 3 点刷新全部启用数据源的表结构快照,
 * 单个数据源失败不影响其他;cron 可通过
 * {@code agent.datasource.schema-refresh-cron} 覆盖。</p>
 *
 * @author ForgeAgent
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class DatasourceSchemaRefreshJob {

    private final AgentDatasourceMapper datasourceMapper;
    private final IAgentDatasourceService datasourceService;

    /**
     * 定时刷新全部启用数据源的表结构快照
     */
    @Scheduled(cron = "${agent.datasource.schema-refresh-cron:0 0 3 * * ?}")
    public void refreshAll() {
        List<AgentDatasource> list = datasourceMapper.selectList(
            new LambdaQueryWrapper<AgentDatasource>().eq(AgentDatasource::getStatus, "0"));
        if (list.isEmpty()) {
            return;
        }
        int success = 0;
        int fail = 0;
        for (AgentDatasource ds : list) {
            try {
                datasourceService.refreshSchema(ds.getId());
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("[SchemaRefresh] 数据源[{}] 快照刷新失败: {}", ds.getName(), e.getMessage());
            }
        }
        log.info("[SchemaRefresh] 快照定时刷新完成,成功 {} 个,失败 {} 个", success, fail);
    }
}
