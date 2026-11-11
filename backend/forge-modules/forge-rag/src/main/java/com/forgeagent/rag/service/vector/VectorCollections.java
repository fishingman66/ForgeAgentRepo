package com.forgeagent.rag.service.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 向量集合命名规则(纯函数,便于单测)
 *
 * <p>为什么按维度分集合:Milvus 一个集合只能有一个向量维度。此前维度取自全局配置
 * {@code rag.knowledge.vector-dim},导致「不同知识库用不同维度嵌入模型」根本无法共存——
 * 要么把别的库写挂,要么写失败被吞掉后该库静默失去向量召回。</p>
 *
 * <p>命名策略(兼容存量,不要求迁移):</p>
 * <ul>
 *   <li>维度 == 主集合维度 → 用**无后缀**的 {@code base},存量部署升级后行为不变;</li>
 *   <li>维度 != 主集合维度 → 用 {@code base_1024} 这类带维度后缀的独立集合。</li>
 * </ul>
 *
 * @author ForgeAgent
 */
public final class VectorCollections {

    /**
     * 维度后缀:base + "_" + dim
     */
    private static final Pattern DIM_SUFFIX = Pattern.compile("^_\\d{1,6}$");

    private VectorCollections() {
    }

    /**
     * 解析某维度应使用的集合名
     *
     * @param base       配置的集合名前缀
     * @param dim        目标向量维度
     * @param primaryDim 主集合维度(启动时与配置/实际集合对齐)
     */
    public static String nameFor(String base, int dim, int primaryDim) {
        String safeBase = base == null || base.isBlank() ? "agent_kb_chunk" : base.trim();
        if (dim <= 0 || dim == primaryDim) {
            return safeBase;
        }
        return safeBase + "_" + dim;
    }

    /**
     * 判断集合是否由本模块管理(主集合或带维度后缀的派生集合)
     */
    public static boolean isManaged(String base, String collection) {
        if (collection == null || collection.isBlank()) {
            return false;
        }
        String safeBase = base == null || base.isBlank() ? "agent_kb_chunk" : base.trim();
        if (safeBase.equals(collection)) {
            return true;
        }
        if (!collection.startsWith(safeBase)) {
            return false;
        }
        return DIM_SUFFIX.matcher(collection.substring(safeBase.length())).matches();
    }

    /**
     * 从集合列表中筛出本模块管理的集合(按名称排序,保证删除顺序可复现)
     */
    public static List<String> managedOf(String base, List<String> collections) {
        List<String> managed = new ArrayList<>();
        if (collections == null) {
            return managed;
        }
        for (String name : collections) {
            if (isManaged(base, name)) {
                managed.add(name);
            }
        }
        managed.sort(String::compareTo);
        return managed;
    }
}
