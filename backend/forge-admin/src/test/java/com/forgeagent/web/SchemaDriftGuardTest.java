package com.forgeagent.web;

import com.forgeagent.agent.mapper.SchemaIntrospectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构漂移启动自检：覆盖齐备放行、缺失 fail-fast、元数据异常降级三条路径。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SchemaDriftGuardTest {

    @Mock
    private SchemaIntrospectMapper schemaMapper;

    @InjectMocks
    private SchemaDriftGuard guard;

    private final DefaultApplicationArguments args =
        new DefaultApplicationArguments(new String[0]);

    @Test
    @DisplayName("三列齐备时不抛异常，逐项查过 information_schema")
    void allColumnsPresent() {
        when(schemaMapper.countColumn(anyString(), anyString())).thenReturn(1L);
        assertDoesNotThrow(() -> guard.run(args));
        verify(schemaMapper).countColumn("agent_approval", "exec_state");
        verify(schemaMapper).countColumn("agent_cs_channel", "encoding_aes_key");
        verify(schemaMapper).countColumn("agent_workflow_run", "pending_deadline");
    }

    @Test
    @DisplayName("强依赖列缺失时 fail-fast 拒绝启动")
    void missingColumnFailsFast() {
        lenient().when(schemaMapper.countColumn(anyString(), anyString())).thenReturn(1L);
        when(schemaMapper.countColumn("agent_cs_channel", "encoding_aes_key")).thenReturn(0L);
        assertThrows(IllegalStateException.class, () -> guard.run(args));
    }

    @Test
    @DisplayName("information_schema 读取异常时降级放行，不阻断启动")
    void metadataFailureDegrades() {
        when(schemaMapper.countColumn(anyString(), anyString()))
            .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> guard.run(args));
    }
}
