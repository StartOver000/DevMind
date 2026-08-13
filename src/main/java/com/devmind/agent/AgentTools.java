package com.devmind.agent;

/**
 * Agent 内部工具定义与共享工具方法（P2 拆分：常量与杂项工具方法从 AgentService 抽出）。
 * 6 个内部工具不注册到 ToolRegistry，由 AgentService/AgentToolExecutor 特判处理。
 */
public final class AgentTools {

    private AgentTools() {
    }

    /** 单工具执行超时（秒）：AgentService 并行等待与 AgentToolExecutor 超时共用 */
    public static final int TOOL_TIMEOUT_SECONDS = 20;

    /** Plan-Execute：模型为多步任务提交计划的内部工具名。 */
    public static final String PLAN_TOOL_NAME = "plan";
    public static final String PLAN_TOOL_DESC = "为多步任务制定执行计划：当任务需要多个步骤（如先检索再诊断再总结）时，按执行顺序提交 steps；每个 step 调用一个工具并说明目标。单步任务无需使用本工具。";
    public static final String PLAN_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "goal": { "type": "string", "description": "任务目标" },
                "steps": {
                  "type": "array",
                  "description": "有序执行步骤，每步调用一个工具",
                  "items": {
                    "type": "object",
                    "properties": {
                      "tool": { "type": "string", "description": "要调用的工具名，如 kb_search" },
                      "args": { "type": "object", "description": "工具参数" },
                      "goal": { "type": "string", "description": "本步骤目标" }
                    },
                    "required": ["tool", "goal"]
                  }
                }
              },
              "required": ["goal", "steps"]
            }
            """;

    /** update_skill：对话式修正技能的内部工具名。 */
    public static final String UPDATE_SKILL_TOOL_NAME = "update_skill";
    public static final String UPDATE_SKILL_TOOL_DESC = "修正一条技能规范：当用户指出某条技能（system 中带【技能 ID x：名称】）有问题/需要调整时，传入该技能的 ID 和用户的修改要求，本工具会更新技能内容。";
    public static final String UPDATE_SKILL_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "skillId": { "type": "integer", "description": "要修改的技能 ID" },
                "instruction": { "type": "string", "description": "用户的修改意见/要求（原话即可）" }
              },
              "required": ["skillId", "instruction"]
            }
            """;

    /** load_skill：按需加载技能全文的内部工具名（渐进披露）。 */
    public static final String LOAD_SKILL_TOOL_NAME = "load_skill";
    public static final String LOAD_SKILL_TOOL_DESC = "加载一项技能（Skill）的完整规范文本：当 system 中的【可参考技能清单】里某项技能与当前任务相关时，传入其 ID 获取完整规范并遵循。";
    public static final String LOAD_SKILL_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "skillId": { "type": "integer", "description": "要加载的技能 ID" }
              },
              "required": ["skillId"]
            }
            """;

    /** delete_memory：对话式删除长期记忆的内部工具名。 */
    public static final String DELETE_MEMORY_TOOL_NAME = "delete_memory";
    public static final String DELETE_MEMORY_TOOL_DESC = "删除一条用户长期记忆：当用户要求忘记/删除某条已记录的用户偏好或记忆（system 中【用户长期记忆】里的条目）时，传入其 ID 删除该条记忆。";
    public static final String DELETE_MEMORY_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "memoryId": { "type": "integer", "description": "要删除的记忆条目 ID" }
              },
              "required": ["memoryId"]
            }
            """;

    /** run_workflow：按 ID 执行工作流的内部工具名（技能引用资源联动）。 */
    public static final String RUN_WORKFLOW_TOOL_NAME = "run_workflow";
    public static final String RUN_WORKFLOW_TOOL_DESC = "执行一个已保存的工作流（Workflow）：当技能规范中【可联动资源：工作流「名称」(ID x)】指明需执行工作流时，传入其 ID 执行并返回运行结果。";
    public static final String RUN_WORKFLOW_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "workflowId": { "type": "integer", "description": "要执行的工作流 ID" }
              },
              "required": ["workflowId"]
            }
            """;

    /** 截断长文本（Agent 各处共用：工具结果回填、计划参数、会话标题等） */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
