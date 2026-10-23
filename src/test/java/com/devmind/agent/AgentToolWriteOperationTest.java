package com.devmind.agent;

import com.devmind.agent.tool.AiGenerateTool;
import com.devmind.agent.tool.DocListTool;
import com.devmind.agent.tool.DocSearchTool;
import com.devmind.agent.tool.KbInfoTool;
import com.devmind.agent.tool.KbSearchTool;
import com.devmind.agent.tool.SqlDiagnoseTool;
import com.devmind.agent.tool.UsageQueryTool;
import com.devmind.agent.tool.UsageStatsTool;
import com.devmind.mcp.McpAgentTool;
import com.devmind.tool.InterfaceToolAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写操作标注的回归护栏（评审 I2）：默认必须是 fail-closed，
 * 且只读内置工具必须显式覆写，避免「新加一个写工具忘了标注 → 只读任务放行」。
 */
class AgentToolWriteOperationTest {

    /** 未标注的新工具按写处理（fail-closed）：忘了写就会被只读任务拦下，而不是静默放行 */
    @Test
    void unmarkedToolDefaultsToWrite() {
        AgentTool unmarked = new AgentTool() {
            @Override
            public String name() {
                return "unmarked";
            }

            @Override
            public String description() {
                return "未标注的新工具";
            }

            @Override
            public String parametersJsonSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public String execute(String argumentsJson, Long userId) {
                return "ok";
            }
        };

        assertTrue(unmarked.writeOperation(), "未标注的工具必须默认按写操作处理");
    }

    /** 只读内置工具必须显式覆写 writeOperation()，不能依赖接口默认值 */
    @Test
    void readOnlyBuiltinToolsOverrideWriteOperationExplicitly() {
        List<Class<?>> readOnlyTools = List.of(
                KbSearchTool.class,
                KbInfoTool.class,
                DocSearchTool.class,
                DocListTool.class,
                UsageQueryTool.class,
                UsageStatsTool.class,
                SqlDiagnoseTool.class,
                AiGenerateTool.class);

        for (Class<?> toolClass : readOnlyTools) {
            Method method;
            try {
                method = toolClass.getMethod("writeOperation");
            } catch (NoSuchMethodException ex) {
                throw new AssertionError(toolClass.getSimpleName() + " 缺少 writeOperation()", ex);
            }
            assertNotEquals(AgentTool.class, method.getDeclaringClass(),
                    toolClass.getSimpleName() + " 必须显式覆写 writeOperation()，否则默认按写处理");
            assertEquals(toolClass, method.getDeclaringClass());
        }
    }

    /** 接口工具按 HTTP 方法判定、MCP 工具一律视为写：实现必须覆写而不是吃默认值 */
    @Test
    void dynamicToolsOverrideWriteOperation() {
        for (Class<?> toolClass : List.of(InterfaceToolAdapter.class, McpAgentTool.class)) {
            Method method;
            try {
                method = toolClass.getMethod("writeOperation");
            } catch (NoSuchMethodException ex) {
                throw new AssertionError(toolClass.getSimpleName() + " 缺少 writeOperation()", ex);
            }
            assertNotEquals(AgentTool.class, method.getDeclaringClass(),
                    toolClass.getSimpleName() + " 必须显式覆写 writeOperation()");
        }
    }
}
