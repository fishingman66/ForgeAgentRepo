package com.forgeagent.callcenter.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.domain.CsNumberPool;
import com.forgeagent.callcenter.mapper.CsNumberPoolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 号码池 DB 实现(P2-1,仅元数据):从 agent_cc_number_pool 分配/归还号码。
 *
 * <p><b>FR 红线</b>:本实现只做 DB 状态流转,不接 SIP/PSTN、不发起 originate。
 * 号码是否真正可用于呼叫,取决于资质到位后的外部系统,本类不伪装该能力。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class DbNumberProvider implements NumberProvider {

    private final CsNumberPoolMapper mapper;

    @Override
    public String allocate(String channelCode) {
        // 仅查空闲呼入服务号(type=1),不做真实呼叫下发
        CsNumberPool row = mapper.selectOne(new LambdaQueryWrapper<CsNumberPool>()
            .eq(CsNumberPool::getStatus, "0")
            .eq(CsNumberPool::getType, 1)
            .and(w -> w.isNull(CsNumberPool::getChannelCode)
                .or().eq(CsNumberPool::getChannelCode, channelCode))
            .orderByAsc(CsNumberPool::getId)
            .last("limit 1"));
        if (row == null) {
            return null;
        }
        row.setStatus("1"); // 已分配(仅元数据)
        mapper.updateById(row);
        return row.getNumber();
    }

    @Override
    public void release(String number) {
        if (number == null || number.isBlank()) {
            return;
        }
        List<CsNumberPool> rows = mapper.selectList(new LambdaQueryWrapper<CsNumberPool>()
            .eq(CsNumberPool::getNumber, number)
            .eq(CsNumberPool::getStatus, "1"));
        for (CsNumberPool row : rows) {
            row.setStatus("0");
            mapper.updateById(row);
        }
    }

    @Override
    public String describe(String number) {
        if (number == null || number.isBlank()) {
            return "";
        }
        CsNumberPool row = mapper.selectOne(new LambdaQueryWrapper<CsNumberPool>()
            .eq(CsNumberPool::getNumber, number).last("limit 1"));
        return row == null ? "" : ("type=" + row.getType() + ",status=" + row.getStatus());
    }
}
