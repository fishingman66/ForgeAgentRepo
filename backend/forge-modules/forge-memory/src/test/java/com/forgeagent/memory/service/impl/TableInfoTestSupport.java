package com.forgeagent.memory.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.forgeagent.memory.domain.AgentMemory;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 单测环境缺少 Spring 启动过程,MyBatis-Plus 的 lambda 列名缓存不会被填充。
 * 需要断言 wrapper SQL 片段时先初始化一次 TableInfo。
 *
 * @author ForgeAgent
 */
final class TableInfoTestSupport {

    private static boolean initialized;

    private TableInfoTestSupport() {
    }

    static synchronized void initAgentMemory() {
        if (initialized) {
            return;
        }
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentMemory.class);
        initialized = true;
    }
}
