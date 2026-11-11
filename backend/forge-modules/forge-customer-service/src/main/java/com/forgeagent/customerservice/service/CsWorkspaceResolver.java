package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 坐席侧数据边界解析(A1 起):由登录用户反查其坐席行所属空间。
 *
 * <p>工作台所有端点同口径——坐席行缺失返回 -1 空空间,查不到任何业务数据,
 * 而不是抛异常把坐席挡在门外(A5 度量、A3 意图管理都挂这条规则)。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsWorkspaceResolver {

    /** 空空间哨兵:任何真实 workspace 都不会是负数。 */
    public static final Long EMPTY_WORKSPACE = -1L;

    private final CsSeatMapper seatMapper;

    public Long currentWorkspaceId() {
        CsSeat seat = seatMapper.selectOne(new LambdaQueryWrapper<CsSeat>()
            .eq(CsSeat::getUserId, LoginHelper.getUserId()).last("limit 1"));
        return seat == null ? EMPTY_WORKSPACE : seat.getWorkspaceId();
    }
}
