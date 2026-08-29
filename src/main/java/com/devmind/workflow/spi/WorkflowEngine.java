package com.devmind.workflow.spi;

import com.devmind.workflow.Workflow;

import java.util.Map;

/**
 * 二期·双向互联（docs/architecture/05 §3）：工作流引擎 SPI。
 *
 * <p>内置引擎与外部引擎（如 n8n）平权可插拔；所有实现共享同一治理链路
 * （校验 / 超时 / 分级重试 / 断点 / 审计），外部引擎不得成为治理盲区。</p>
 *
 * <p>契约 v1 刻意小：一次性启动 + 终态回传。n8n 等异步引擎接入时，
 * 通过扩展 poll 语义（轮询外部执行状态）演进，契约变更需更新契约测试。</p>
 */
public interface WorkflowEngine {

    /** 引擎标识（workflow.engine 字段的取值，缺省 "builtin"） */
    String id();

    /** 能力声明：该引擎是否承接此 engine 标记的工作流定义 */
    boolean supports(String engine);

    /**
     * 执行一次工作流。
     *
     * @param workflow    工作流定义（步骤/变量/作用域）
     * @param userId      触发用户（审计/授权归属）
     * @param triggerType 触发来源（manual / schedule / webhook / api）
     * @param initialVars 初始变量，可空
     * @return 执行句柄（含 runId、终态与错误；异步引擎的 runId 可为 null，用 externalRef 定位）
     */
    WorkflowHandle run(Workflow workflow, Long userId, String triggerType, Map<String, Object> initialVars);
}
