package com.forgeagent.rag.service.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量编解码与相似度计算(本地开发向量存储用)。
 *
 * <p>用 float32 小端存 BLOB:384 维只占 1536 字节,而 JSON 存法约 3.5KB 且解析要逐字符扫。
 * 小端是 x86 与 ARM 的默认序,跨机器读同一份数据不会错位。</p>
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    /** float[] → BLOB(4 字节/维,小端)。 */
    public static byte[] encode(float[] vector) {
        if (vector == null) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** BLOB → float[];长度不是 4 的倍数视为脏数据,返回空数组而不是抛异常。 */
    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /**
     * 余弦相似度(-1~1,越大越相关),与 Milvus 的 COSINE 口径一致。
     *
     * <p>维度不等或零向量返回 0——这种情况说明数据有问题,给 0 分让它排不进结果,
     * 而不是抛异常打断整次检索。</p>
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
