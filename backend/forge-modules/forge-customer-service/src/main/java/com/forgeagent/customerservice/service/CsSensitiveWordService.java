package com.forgeagent.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.forgeagent.common.core.exception.ServiceException;
import com.forgeagent.customerservice.config.ConditionalOnCustomerServiceEnabled;
import com.forgeagent.customerservice.config.CsModuleProperties;
import com.forgeagent.customerservice.domain.CsSensitiveWord;
import com.forgeagent.customerservice.mapper.CsSensitiveWordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 敏感词过滤(P0,差距分析 4.4)。
 *
 * <p>平台自带的 {@code forge-common-sensitive} 是注解式字段脱敏（按角色决定是否打码），
 * 不是词库过滤——客服需要的恰恰是"哪些词不能出现"的词库，所以这里自建一层。</p>
 *
 * <p>三档处置:BLOCK 整条拦截（入站转人工 / 出站替换兜底）、MASK 打码后放行、WARN 只记审计。
 * 词库按空间隔离（平台级 + 空间级），本地 60 秒快照避免每条消息都查库；运营改词后最多延迟一分钟生效，
 * 这个延迟换的是主链路零额外查询。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnCustomerServiceEnabled
public class CsSensitiveWordService {

    /** 快照有效期(毫秒)。 */
    private static final long SNAPSHOT_TTL_MS = 60_000L;
    /** 打码占位符。 */
    private static final String MASK_TOKEN = "***";

    public enum Level {
        NONE, WARN, MASK, BLOCK;

        /** 两档取高:BLOCK > MASK > WARN > NONE。 */
        public Level max(Level other) {
            return other != null && other.ordinal() > this.ordinal() ? other : this;
        }
    }

    /** 命中结果:最高级别 + 命中词(用于打码与审计)。 */
    public record Hit(Level level, List<String> words) {
        public boolean blocked() {
            return level == Level.BLOCK;
        }

        public boolean masked() {
            return level == Level.MASK;
        }
    }

    private final CsSensitiveWordMapper wordMapper;
    private final CsModuleProperties properties;

    private volatile Snapshot snapshot;

    private record Snapshot(Long workspaceId, long expireAt, List<CsSensitiveWord> words) {
    }

    /** 判定一条文本;命中不区分大小写。 */
    public Hit check(Long workspaceId, String text) {
        if (!Boolean.TRUE.equals(properties.getSensitiveWordEnabled()) || text == null || text.isBlank()) {
            return new Hit(Level.NONE, List.of());
        }
        String lower = text.toLowerCase();
        Level level = Level.NONE;
        List<String> hits = new ArrayList<>();
        for (CsSensitiveWord entry : words(workspaceId)) {
            String word = entry.getWord();
            if (word == null || word.isBlank()) {
                continue;
            }
            if (lower.contains(word.toLowerCase())) {
                hits.add(word);
                level = level.max(severityOf(entry.getSeverity()));
            }
        }
        return new Hit(level, hits);
    }

    /** 把命中词替换为占位符，实例入口（保留注入依赖的调用习惯）。 */
    public String mask(String text, List<String> words) {
        return maskText(text, words);
    }

    /** 打码纯函数：大小写不敏感、长度不保留，避免按占位符长度反推原词。 */
    public static String maskText(String text, List<String> words) {
        if (text == null || words == null || words.isEmpty()) {
            return text;
        }
        String masked = text;
        for (String word : words) {
            if (word != null && !word.isBlank()) {
                masked = masked.replaceAll("(?i)" + java.util.regex.Pattern.quote(word), MASK_TOKEN);
            }
        }
        return masked;
    }

    /** 管理页:平台级 + 本空间词条。 */
    public List<CsSensitiveWord> list(Long workspaceId) {
        return wordMapper.selectList(new LambdaQueryWrapper<CsSensitiveWord>()
            .and(w -> w.isNull(CsSensitiveWord::getWorkspaceId)
                .or().eq(CsSensitiveWord::getWorkspaceId, workspaceId))
            .orderByDesc(CsSensitiveWord::getId));
    }

    /** 新增/编辑空间级词条;平台级词条只读（与意图同口径）。 */
    public CsSensitiveWord save(CsSensitiveWord word, Long workspaceId) {
        if (word == null || word.getWord() == null || word.getWord().isBlank()) {
            throw new ServiceException("词条不能为空");
        }
        word.setWord(word.getWord().trim());
        word.setSeverity(normalize(word.getSeverity()));
        word.setEnabled(word.getEnabled() == null ? "1" : word.getEnabled());
        if (word.getId() == null) {
            word.setWorkspaceId(workspaceId);
            word.setCreateTime(new Date());
            wordMapper.insert(word);
        } else {
            CsSensitiveWord existing = wordMapper.selectById(word.getId());
            if (existing == null) {
                throw new ServiceException("词条不存在");
            }
            if (existing.getWorkspaceId() == null) {
                throw new ServiceException("平台级词条不可修改");
            }
            word.setWorkspaceId(existing.getWorkspaceId());
            word.setCreateTime(existing.getCreateTime());
            wordMapper.updateById(word);
        }
        invalidate();
        return word;
    }

    /** 删除空间级词条;不存在按幂等成功处理。 */
    public boolean remove(Long id) {
        CsSensitiveWord existing = id == null ? null : wordMapper.selectById(id);
        if (existing == null) {
            return true;
        }
        if (existing.getWorkspaceId() == null) {
            throw new ServiceException("平台级词条不可删除");
        }
        boolean removed = wordMapper.deleteById(id) > 0;
        invalidate();
        return removed;
    }

    /** 词库变更后主动失效快照,让运营改完立刻生效(定时任务里不需要)。 */
    public void invalidate() {
        snapshot = null;
    }

    private List<CsSensitiveWord> words(Long workspaceId) {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && current.expireAt() > now
            && java.util.Objects.equals(current.workspaceId(), workspaceId)) {
            return current.words();
        }
        List<CsSensitiveWord> loaded = wordMapper.selectList(new LambdaQueryWrapper<CsSensitiveWord>()
            .eq(CsSensitiveWord::getEnabled, "1")
            .and(w -> w.isNull(CsSensitiveWord::getWorkspaceId)
                .or().eq(CsSensitiveWord::getWorkspaceId, workspaceId)));
        snapshot = new Snapshot(workspaceId, now + SNAPSHOT_TTL_MS, loaded);
        return loaded;
    }

    private static Level severityOf(String severity) {
        if (severity == null) {
            return Level.BLOCK;
        }
        return switch (severity.toUpperCase()) {
            case "MASK" -> Level.MASK;
            case "WARN" -> Level.WARN;
            default -> Level.BLOCK;
        };
    }

    private static String normalize(String severity) {
        if (severity == null || severity.isBlank()) {
            return "BLOCK";
        }
        String upper = severity.toUpperCase();
        return switch (upper) {
            case "MASK", "WARN", "BLOCK" -> upper;
            default -> "BLOCK";
        };
    }
}
