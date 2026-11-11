package com.forgeagent.callcenter.provider;

/**
 * 号码扩展接口(V1,D8 暂无号码,方案 v2 第十二章)。
 *
 * <p>接真实 AICC 号码池或运营商号码资源时新增实现,业务层只依赖本接口。</p>
 */
public interface NumberProvider {

    /** 分配一个可用服务号码;无可用号码时返回 null,由调用方决定降级。 */
    String allocate(String channelCode);

    /** 归还号码。 */
    void release(String number);

    /** 号码描述信息,无则返回空串。 */
    String describe(String number);
}
