package com.forgeagent.callcenter.storage;

import com.forgeagent.common.oss.client.OssClient;
import com.forgeagent.common.oss.factory.OssFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;

/**
 * 生产实现(V1):转发 forge-common-oss(sys_oss_config)。
 * OSS 未配置或不可达时抛出原异常,由调用方按降级口径处理。
 */
public class OssRecordingStorage implements RecordingStorage {

    @Override
    public void save(String objectKey, byte[] data) {
        OssClient oss = OssFactory.instance();
        try (InputStream in = new ByteArrayInputStream(data)) {
            oss.upload(objectKey, in, data.length);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("OSS 上传失败: " + objectKey, e);
        }
    }

    @Override
    public boolean delete(String objectKey) {
        return OssFactory.instance().delete(objectKey);
    }

    @Override
    public String presignReadUrl(String objectKey, Duration ttl) {
        return OssFactory.instance().presignGetUrl(objectKey, ttl);
    }
}
