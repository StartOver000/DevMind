package com.devmind.workflow.spi;

import com.devmind.workflow.Workflow;
import com.devmind.workflow.WorkflowExecutor;
import com.devmind.workflow.WorkflowRun;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内置引擎的 SPI 适配器：直接委托 {@link WorkflowExecutor}，
 * 闸门（per-workflow Semaphore）、分级重试、30s 单步超时、断点恢复、
 * 错误输出识别、轨迹审计全部继承，行为零变化（由既有 32 个执行器测试与契约测试共同保证）。
 */
@Component
public class BuiltinWorkflowEngine implements WorkflowEngine {

    private final WorkflowExecutor executor;

    public BuiltinWorkflowEngine(WorkflowExecutor executor) {
        this.executor = executor;
    }

    @Override
    public String id() {
        return "builtin";
    }

    @Override
    public boolean supports(String engine) {
        return engine == null || engine.isBlank() || "builtin".equals(engine.trim());
    }

    @Override
    public WorkflowHandle run(Workflow workflow, Long userId, String triggerType, Map<String, Object> initialVars) {
        WorkflowRun run = (initialVars == null || initialVars.isEmpty())
                ? executor.execute(workflow, userId, triggerType)
                : executor.execute(workflow, userId, triggerType, initialVars);
        return WorkflowHandle.builtin(run.id(), run.status(), run.error());
    }
}
