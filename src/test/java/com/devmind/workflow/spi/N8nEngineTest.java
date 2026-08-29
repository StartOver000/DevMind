package com.devmind.workflow.spi;

import com.devmind.config.N8nProperties;
import com.devmind.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N8n 工作流引擎契约测试。
 *
 * <p>验证引擎 ID、能力声明、未配置时的降级行为。
 * 端到端 MCP 传输测试见 {@link N8nEngineIntegrationTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
class N8nEngineTest {

    private final N8nProperties disabledProps = new N8nProperties("", 30000);
    private final N8nProperties enabledProps = new N8nProperties("http://localhost:5678/mcp-sse", 30000);

    @Test
    void idIsN8n() {
        N8nEngine engine = new N8nEngine(disabledProps);
        assertThat(engine.id()).isEqualTo("n8n");
    }

    @Test
    void supportsN8nEngine() {
        N8nEngine engine = new N8nEngine(disabledProps);
        assertThat(engine.supports("n8n")).isTrue();
        assertThat(engine.supports("n8n-engine")).isTrue();
        assertThat(engine.supports("builtin")).isFalse();
        assertThat(engine.supports(null)).isFalse();
        assertThat(engine.supports("")).isFalse();
    }

    @Test
    void runReturnsFailedWhenNotConfigured() {
        N8nEngine engine = new N8nEngine(disabledProps);
        Workflow workflow = new Workflow(1L, 1L, "测试", null, "[]", "manual", null, "private", "ENABLED", "n8n", 1L, null);

        WorkflowHandle handle = engine.run(workflow, 1L, "manual", null);

        assertThat(handle.engineId()).isEqualTo("n8n");
        assertThat(handle.status()).isEqualTo("FAILED");
        assertThat(handle.error()).contains("未配置");
    }

    @Test
    void runWithEmptyWorkflowNameReturnsFailedWhenNotConfigured() {
        N8nEngine engine = new N8nEngine(disabledProps);
        Workflow workflow = new Workflow(2L, 1L, null, null, "[]", "manual", null, "private", "ENABLED", "n8n", 1L, null);

        WorkflowHandle handle = engine.run(workflow, 1L, "manual", Map.of("param1", "value1"));

        assertThat(handle.status()).isEqualTo("FAILED");
        assertThat(handle.error()).contains("未配置");
    }
}