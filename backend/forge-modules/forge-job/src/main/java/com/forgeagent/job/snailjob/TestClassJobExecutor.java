package com.forgeagent.job.snailjob;

import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.executor.AbstractJobExecutor;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import org.springframework.stereotype.Component;

/**
 * @author opensnail; 改造: 智造 ForgeAgent 2026-11
 * @date 2024-05-17
 */
@Component
public class TestClassJobExecutor extends AbstractJobExecutor {

    /**
     * 执行测试类任务。
     *
     * @param jobArgs 任务参数
     * @return 任务执行结果
     */
    @Override
    protected ExecuteResult doJobExecute(JobArgs jobArgs) {
        return ExecuteResult.success("TestJobExecutor测试成功");
    }
}
