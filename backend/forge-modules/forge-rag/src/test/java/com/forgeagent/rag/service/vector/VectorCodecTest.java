package com.forgeagent.rag.service.vector;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地向量编解码与余弦口径(与 Milvus COSINE 对齐)。 */
@Tag("dev")
class VectorCodecTest {

    @Test
    void encodeDecodeRoundTrip() {
        float[] vector = {0.1f, -0.25f, 0.5f, 1.0f};
        byte[] bytes = VectorCodec.encode(vector);
        assertEquals(16, bytes.length, "4 维 float32 应为 16 字节");
        float[] decoded = VectorCodec.decode(bytes);
        assertEquals(vector.length, decoded.length);
        for (int i = 0; i < vector.length; i++) {
            assertEquals(vector[i], decoded[i], 1e-6);
        }
    }

    @Test
    void dirtyBytesReturnEmptyInsteadOfThrowing() {
        assertEquals(0, VectorCodec.decode(null).length);
        assertEquals(0, VectorCodec.decode(new byte[0]).length);
        assertEquals(0, VectorCodec.decode(new byte[5]).length, "非 4 倍数视为脏数据");
    }

    @Test
    void cosineMatchesMilvusSemantics() {
        float[] a = {1, 0, 0};
        float[] b = {1, 0, 0};
        assertEquals(1.0, VectorCodec.cosine(a, b), 1e-6);
        // 正交为 0
        assertEquals(0.0, VectorCodec.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-6);
        // 反向为 -1
        assertEquals(-1.0, VectorCodec.cosine(new float[]{1, 0}, new float[]{-1, 0}), 1e-6);
        // 相似方向大于不相似方向
        assertTrue(VectorCodec.cosine(new float[]{1, 1}, new float[]{1, 0.9f})
            > VectorCodec.cosine(new float[]{1, 1}, new float[]{0.1f, 1}));
    }

    @Test
    void dimensionMismatchScoresZero() {
        assertEquals(0.0, VectorCodec.cosine(new float[]{1, 0, 0}, new float[]{1, 0}), 1e-6);
        assertEquals(0.0, VectorCodec.cosine(new float[0], new float[]{1}), 1e-6);
    }

    @Test
    void zeroVectorScoresZero() {
        assertEquals(0.0, VectorCodec.cosine(new float[]{0, 0}, new float[]{1, 1}), 1e-6);
    }
}
