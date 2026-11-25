package com.forgeagent.callcenter.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.callcenter.domain.CsNumberPool;
import com.forgeagent.callcenter.mapper.CsNumberPoolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 中继 DB 实现(P2-1,仅元数据):读写号码池的 trunkRef 描述。
 *
 * <p><b>FR 红线</b>:不连接真实 SIP 中继、不注册、不发起任何呼叫;
 * 无资质时保持默认 noop,本实现只让"绑定引用"有处可存。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class DbTrunkProvider implements TrunkProvider {

    private final CsNumberPoolMapper mapper;

    @Override
    public void bind(String number, String trunkRef) {
        if (number == null || number.isBlank()) {
            return;
        }
        CsNumberPool row = mapper.selectOne(new LambdaQueryWrapper<CsNumberPool>()
            .eq(CsNumberPool::getNumber, number).last("limit 1"));
        if (row == null) {
            log.warn("[CC] DbTrunkProvider:号码 {} 不在号码池,忽略绑定", number);
            return;
        }
        row.setTrunkRef(trunkRef);
        mapper.updateById(row);
    }

    @Override
    public String status() {
        Long bound = mapper.selectCount(new LambdaQueryWrapper<CsNumberPool>()
            .isNotNull(CsNumberPool::getTrunkRef));
        return "DB_METADATA_ONLY(bound=" + (bound == null ? 0 : bound) + ",no_sip_registration)";
    }
}
