package com.forgeagent.callcenter.engine;

import com.forgeagent.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音引擎注册表(C2):按 code 路由 ASR/TTS 实现,范式对齐 SmartCall 的
 * VoiceModelEnum + 工厂(Bean 自动发现),但路由键改为配置字符串——
 * 引擎实现按条件装配(如 DashScope 仅在 API key 配置时加载),注册表天然容纳。
 */
@Component
public class VoiceEngineRegistry {

    private final Map<String, AsrEngine> asrByCode = new HashMap<>();
    private final Map<String, TtsEngine> ttsByCode = new HashMap<>();

    public VoiceEngineRegistry(List<AsrEngine> asrEngines, List<TtsEngine> ttsEngines) {
        for (AsrEngine engine : asrEngines) {
            if (asrByCode.put(engine.code(), engine) != null) {
                throw new ServiceException("ASR 引擎标识重复: " + engine.code());
            }
        }
        for (TtsEngine engine : ttsEngines) {
            if (ttsByCode.put(engine.code(), engine) != null) {
                throw new ServiceException("TTS 引擎标识重复: " + engine.code());
            }
        }
    }

    /** 按配置取 ASR 引擎;缺失时报出已装配清单,排障不猜。 */
    public AsrEngine asr(String code) {
        AsrEngine engine = code == null ? null : asrByCode.get(code.toLowerCase());
        if (engine == null) {
            throw new ServiceException("ASR 引擎未装配: " + code + ",已装配: " + asrByCode.keySet());
        }
        return engine;
    }

    public TtsEngine tts(String code) {
        TtsEngine engine = code == null ? null : ttsByCode.get(code.toLowerCase());
        if (engine == null) {
            throw new ServiceException("TTS 引擎未装配: " + code + ",已装配: " + ttsByCode.keySet());
        }
        return engine;
    }
}
