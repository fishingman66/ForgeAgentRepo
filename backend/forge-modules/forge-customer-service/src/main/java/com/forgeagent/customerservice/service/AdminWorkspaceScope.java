package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.common.satoken.utils.LoginHelper;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.domain.CsSeat;
import com.forgeagent.customerservice.mapper.CsSeatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管理界面的空间边界(A1 超管扩展)。
 *
 * <p><b>普通用户</b>:永远用 {@link CsWorkspaceResolver#currentWorkspaceId()} 推导,
 * 前端传的 workspaceId 一律忽略(防越权)。<b>超管</b>:可显式选择空间;
 * 传 null 表示"全部空间"(仅列表口径,写操作必须指定空间)。</p>
 *
 * <p>空间候选来自 {@code agent_cs_seat} 的 distinct workspaceId——有坐席的空间,
 * 即客服/呼叫中心管理界面真正关心的范围;不引入跨模块 workspace 依赖。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class AdminWorkspaceScope {

    public static final Long ALL_WORKSPACES = null;

    private final CsWorkspaceResolver resolver;
    private final CsSeatMapper seatMapper;

    /** 是否超管(沿用系统既有判定,不发明新权限模型)。 */
    public boolean isSuperAdmin() {
        return LoginHelper.isSuperAdmin();
    }

    /**
     * 写操作空间:超管取 requested;普通用户若传了不同空间,明确拒绝并提示
     * (不能静默按自己空间写入,否则用户以为改到了目标空间)。
     */
    public Long resolve(Long requested) {
        if (isSuperAdmin()) {
            return requested;
        }
        Long own = resolver.currentWorkspaceId();
        if (requested != null && !requested.equals(own)) {
            log.warn("[CS] 普通用户跨空间写操作被拒绝 userId={} requested={} own={}",
                LoginHelper.getUserId(), requested, own);
            throw new ServiceException("普通用户只能操作本人坐席所属空间，跨空间参数已被拒绝");
        }
        return own;
    }

    /**
     * 列表空间:超管 requested=null 表示全部空间(返回 null,调用方不加过滤);
     * 普通用户传了不同空间时告警并仍返回本人空间(不打断浏览,但服务端留痕)。
     */
    public Long resolveForList(Long requested) {
        if (isSuperAdmin()) {
            return requested;
        }
        Long own = resolver.currentWorkspaceId();
        if (requested != null && !requested.equals(own)) {
            log.warn("[CS] 普通用户跨空间列表参数已忽略 userId={} requested={} own={}",
                LoginHelper.getUserId(), requested, own);
        }
        return own;
    }

    /** 候选空间列表(仅超管);普通用户返回空。 */
    public List<Long> availableWorkspaces() {
        if (!isSuperAdmin()) {
            return List.of();
        }
        return seatMapper.selectList(new LambdaQueryWrapper<CsSeat>()
                .select(CsSeat::getWorkspaceId)
                .isNotNull(CsSeat::getWorkspaceId)
                .gt(CsSeat::getWorkspaceId, 0)
                .groupBy(CsSeat::getWorkspaceId)
                .orderByAsc(CsSeat::getWorkspaceId))
            .stream()
            .map(CsSeat::getWorkspaceId)
            .toList();
    }
}
