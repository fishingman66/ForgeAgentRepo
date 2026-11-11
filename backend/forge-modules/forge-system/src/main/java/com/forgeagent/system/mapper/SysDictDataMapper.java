package com.forgeagent.system.mapper;

import com.forgeagent.common.mybatis.core.mapper.BaseMapperPlus;
import com.forgeagent.system.domain.SysDictData;
import com.forgeagent.system.domain.vo.SysDictDataVo;

import java.util.List;

/**
 * 字典表 数据层
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public interface SysDictDataMapper extends BaseMapperPlus<SysDictData, SysDictDataVo> {

    /**
     * 根据字典类型查询字典数据列表
     *
     * @param dictType 字典类型
     * @return 符合条件的字典数据列表
     */
    default List<SysDictDataVo> selectDictDataByType(String dictType) {
        return this.lambda()
            .eq(SysDictData::getDictType, dictType)
            .orderByAsc(SysDictData::getDictSort)
            .voList();
    }
}
