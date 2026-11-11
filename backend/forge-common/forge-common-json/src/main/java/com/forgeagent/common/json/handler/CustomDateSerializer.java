package com.forgeagent.common.json.handler;

import cn.hutool.core.date.DateUtil;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.util.Date;

/**
 * 自定义 Date 类型序列化处理器:统一输出 yyyy-MM-dd HH:mm:ss 格式,
 * 避免默认 ISO-8601(带时区偏移)格式对前端不友好。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
public class CustomDateSerializer extends ValueSerializer<Date> {

    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Override
    public void serialize(Date value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeString(DateUtil.format(value, PATTERN));
    }
}
