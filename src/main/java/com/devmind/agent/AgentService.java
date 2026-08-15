package com.devmind.agent;

import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.AgentMessage;
import com.devmind.agent.dto.MemoryUpdateRequest;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.chat.dto.Reference;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.LocalRagAnswerer;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.skill.SkillMatcher;
import com.devmind.tool.ToolAccessService;
import com.devmind.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 研发问答 Agent：ReAct 式执行循环（编排层）。
 * 模型自主调用工具（kb_search / sql_diagnose 等）→ 工具结果回填 → 再决策，直到输出最终回答。
 * 韧性：模型调用走 {@link ChatRouter}（超时/熔断/降级）；单工具失败回填错误不中断；
 * 全链路失败降级为本地 RAG 回答。
 *
 * <p>P2 拆分（职责单一）：本类只做编排（对话循环 + 技能/记忆注入 + 工具构建 + 降级），
 * 具体职责委托给三个组合组件：
 * <ul>
 *   <li>{@link AgentToolExecutor}——工具校验/执行/超时 + Plan-Execute + 4 个内部特判工具</li>
 *   <li>{@link AgentMemoryManager}——长期记忆提取与 CRUD</li>
 *   <li>{@link AgentConversationStore}——会话/轨迹持久化</li>
 * </ul>
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 最大工具调用轮数（防死循环；多步任务 + 重试后仍应在此内收尾） */
    private static final int MAX_TOOL_ROUNDS = 5;
    /** 接口工具全量注入上限：授权的接口工具数超过此值后，改为按语义命中注入（防上下文膨胀） */
    private static final int MAX_INTERFACE_TOOLS_FULL_INJECT = 20;
    /** 语义命中注入的接口工具数（P1 接口语义化闭环） */
    private static final int MAX_INTERFACE_TOOLS_INJECT = 8;

    private static final String SYSTEM_PROMPT = """
            你是 DevMind 研发助手 Agent。根据用户问题自主决定调用哪些工具获取信息，再给出最终回答。

            可调用工具：
            - plan：多步任务的执行计划（≥3 个独立步骤时提交 plan，含 goal 与有序 steps，每步一个工具）。
            - update_skill：用户指出某条技能规范不对/需要调整时，调用本工具修改技能内容。
            - load_skill：按需加载技能完整规范。system 中【可参考技能清单】只列了名称/描述；
              当当前任务确实涉及清单中某项时，先调用 load_skill（skillId 取清单中的 ID）拿到完整规范再执行。
            - delete_memory：用户要求忘记/删除某条长期记忆（system 中【用户长期记忆】里带【记忆 ID x】的条目）时，调用本工具删除该条记忆。
            - run_workflow：执行一个已保存的工作流。当技能规范中【可联动资源：工作流「名称」(ID x)】指明需执行工作流时，传入其 ID 执行。
            - kb_search：检索研发知识库，获取与问题相关的文档片段（含相似度分数）。
            - kb_info：查询当前用户可访问的知识库列表。
            - doc_list：查询指定知识库内的文档清单（文件名、状态、文本块数）。
            - sql_diagnose：诊断 SQL 性能问题。当用户给出具体 SQL（涉及慢查询、执行计划、
              全表扫描、索引缺失/失效、深分页、隐式转换等性能优化场景）时调用本工具，
              传入待诊断的 SQL（sql 参数必填）；不要仅凭常识直接回答 SQL 性能问题，
              先调用本工具获取执行计划分析与规则识别结果再作答。
            - usage_query：查询当前用户的模型用量与估算费用。

            规则：
            1. 先调用需要的工具，拿到结果后再回答；不要编造工具结果。
            2. 多维度问题（如 SQL 性能 + 优化方案）可依次调用多个工具；
               需要 ≥3 个独立步骤时，先提交 plan 计划，再按步骤执行。
            3. 工具结果不足以回答时，明确说明，可换关键词再次检索。
            4. 最终回答需引用来源文件名，格式 [来源: 文件名]。
            5. 已执行过工具的轮次：基于工具返回结果直接总结，不要重复调用相同工具。
            6. 若当前任务参考了技能规范（system 中带【技能 ID x：名称】），且用户指出该规范有
               问题/要修改（如"这个技能不对"、"把第 2 步改成先查 A"），调用 update_skill
               （skillId 取规范中的 ID，instruction 为用户原话）。修改后必须向用户展示
               "修改前 → 修改后"对比，并询问是否符合预期；用户仍不满意则继续调整。
            7. 若 system 中只有【可参考技能清单】（未给全文），而当前任务确实与清单中某项
               技能相关（如用户明确要求按某项规范执行），必须先用 load_skill 加载其全文并遵循，
               不要凭名称猜测技能内容。
            8. 若用户要求忘记/删除某条长期记忆（如"忘掉这条""删掉刚才那个偏好"，对应
               system 中【用户长期记忆】里带【记忆 ID x】的条目），调用 delete_memory
               （memoryId 取该条目的 ID），删除后告知用户。
            9. 若命中的技能规范中带【可联动资源】标注（工作流/知识库），说明该技能关联了
               这些资源：涉及工作流时调用 run_workflow（workflowId 取标注中的 ID）；
               涉及知识库时调用 kb_search 并指定该知识库。联动执行后再给出最终回答。
            """;

    // ---- 内部工具名（P2 拆分：定义移至 AgentTools，此处保留 public 转发常量供外部/测试引用）----
    public static final String PLAN_TOOL_NAME = AgentTools.PLAN_TOOL_NAME;
    public static final String UPDATE_SKILL_TOOL_NAME = AgentTools.UPDATE_SKILL_TOOL_NAME;
    public static final String LOAD_SKILL_TOOL_NAME = AgentTools.LOAD_SKILL_TOOL_NAME;
    public static final String DELETE_MEMORY_TOOL_NAME = AgentTools.DELETE_MEMORY_TOOL_NAME;
    public static final String RUN_WORKFLOW_TOOL_NAME = AgentTools.RUN_WORKFLOW_TOOL_NAME;

    private final ChatRouter chatRouter;
    private final ToolRegistry toolRegistry;
    private final AgentConversationRepository conversationRepository;
    private final AgentMemoryRepository memoryRepository;
    private final UserService userService;
    private final ModelUsageService modelUsageService;
    private final AiModelGateway modelGateway;
    private final RetrievalService retrievalService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DevMindProperties properties;
    private final MeterRegistry meterRegistry;
    private final ToolAccessService toolAccessService;
    private final ChatFileStore chatFileStore;
    /** LLM 输入统一防护（八股反推 P1-1）：对话注入检测 */
    private final com.devmind.security.LlmInputGuard llmInputGuard;
    /** 技能匹配器（Guide-51）：可选注入，测试/无技能时不启用 */
    private SkillMatcher skillMatcher;
    /** 接口语义化服务（P1 工具发现）：可选注入，生产环境自动注入；测试/未启用时接口工具全量注入 */
    private com.devmind.tool.OpenApiImportService openApiImportService;
    // P2 拆分：组合组件（构造器内创建，职责单一）
    private final AgentToolExecutor toolExecutor;
    private final AgentMemoryManager memoryManager;
    private final AgentConversationStore conversationStore;

    public AgentService(
            ChatRouter chatRouter,
            ToolRegistry toolRegistry,
            AgentConversationRepository conversationRepository,
            AgentMemoryRepository memoryRepository,
            UserService userService,
            ModelUsageService modelUsageService,
            AiModelGateway modelGateway,
            RetrievalService retrievalService,
            KnowledgeBaseService knowledgeBaseService,
            DevMindProperties properties,
            MeterRegistry meterRegistry,
            ToolCallValidator toolCallValidator,
            ToolAccessService toolAccessService,
            ChatFileStore chatFileStore,
            com.devmind.security.LlmInputGuard llmInputGuard
    ) {
        this.chatRouter = chatRouter;
        this.toolRegistry = toolRegistry;
        this.conversationRepository = conversationRepository;
        this.memoryRepository = memoryRepository;
        this.userService = userService;
        this.modelUsageService = modelUsageService;
        this.modelGateway = modelGateway;
        this.retrievalService = retrievalService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.toolAccessService = toolAccessService;
        this.chatFileStore = chatFileStore;
        this.llmInputGuard = llmInputGuard;
        // 组合组件：共用本类已注入的依赖，职责单一化
        this.conversationStore = new AgentConversationStore(conversationRepository, userService);
        this.memoryManager = new AgentMemoryManager(memoryRepository, chatRouter, userService);
        this.toolExecutor = new AgentToolExecutor(
                toolRegistry, toolCallValidator, meterRegistry, conversationStore, memoryRepository, userService);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        toolExecutor.shutdown();
    }

    /** 技能匹配器可选注入（避免破坏既有测试构造器；生产环境由 Spring 自动注入） */
    @Autowired(required = false)
    public void setSkillMatcher(SkillMatcher skillMatcher) {
        this.skillMatcher = skillMatcher;
        // load_skill 命中计数也在执行器内统计
        this.toolExecutor.setSkillMatcher(skillMatcher);
    }

    /** 技能服务可选注入（对话式修正 update_skill / load_skill 用） */
    @Autowired(required = false)
    public void setSkillService(com.devmind.skill.SkillService skillService) {
        this.toolExecutor.setSkillService(skillService);
    }

    /** 工作流服务可选注入（技能引用工作流联动执行 run_workflow 用） */
    @Autowired(required = false)
    public void setWorkflowService(com.devmind.workflow.WorkflowService workflowService) {
        this.toolExecutor.setWorkflowService(workflowService);
    }

    /** 接口语义化服务可选注入（工具发现用）：接口多时按自然语言语义命中注入候选接口 */
    @Autowired(required = false)
    public void setOpenApiImportService(com.devmind.tool.OpenApiImportService openApiImportService) {
        this.openApiImportService = openApiImportService;
    }

    /** 匹配当前请求命中的技能规范（Guide-51 P1）：未注入 matcher 或未命中时返回空 */
    private com.devmind.skill.SkillMatcher.MatchResult matchSkills(String question, Long userId) {
        if (skillMatcher == null) {
            return new com.devmind.skill.SkillMatcher.MatchResult(List.of(), List.of());
        }
        try {
            Long tenantId = userService.tenantIdOf(userId);
            return skillMatcher.match(question, tenantId, userId);
        } catch (Exception ex) {
            log.warn("技能匹配失败: {}", ex.getMessage());
            return new com.devmind.skill.SkillMatcher.MatchResult(List.of(), List.of());
        }
    }

    public AgentChatResponse chat(AgentChatRequest request, Long userId) {
        return doChat(request, userId, null, null);
    }

    /**
     * Agent 流式入口：与 {@link #chat} 逻辑一致，但每次工具执行完成时通过
     * {@code onTrace} 实时回调（供 SSE 推送工具轨迹），模型原生思考过程通过
     * {@code onThinking} 实时回调（供 SSE 推送 reasoning），最终答案由调用方分片推送。
     */
    public AgentChatResponse chatStream(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace
    ) {
        return doChat(request, userId, onTrace, null);
    }

    /** 流式入口 + 思考过程回调（reasoning 实时推送） */
    public AgentChatResponse chatStream(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace,
            Consumer<String> onThinking
    ) {
        return doChat(request, userId, onTrace, onThinking);
    }

    private AgentChatResponse doChat(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace,
            Consumer<String> onThinking
    ) {
        userService.requireUser(userId);
        String rawQuestion = request.question() == null ? "" : request.question().trim();
        if (rawQuestion.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "问题不能为空");
        }
        // LLM 输入统一防护（P1-1）：对话内容命中 Prompt 注入模式即拒绝，防止注入指令进入模型
        llmInputGuard.checkText(rawQuestion);
        // 上传文件注入：fileIds 对应的文本作为分析上下文拼到问题前
        String question = enrichWithFiles(rawQuestion, request.fileIds(), userId);

        Long conversationId = conversationStore.resolveConversation(request.conversationId(), question, userId);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        // 技能注入（Guide-51 渐进披露）：关键词命中注入全文；其余仅给清单，按需 load_skill
        com.devmind.skill.SkillMatcher.MatchResult skillMatch = matchSkills(question, userId);
        List<String> skillParts = new ArrayList<>();
        if (skillMatch.injectFull() != null && !skillMatch.injectFull().isEmpty()) {
            skillParts.add("【相关技能规范】以下技能与当前任务直接相关，请遵循（与通用规则冲突以本条为准）：\n"
                    + String.join("\n---\n", skillMatch.injectFull()));
        }
        if (skillMatch.catalog() != null && !skillMatch.catalog().isEmpty()) {
            skillParts.add("【可参考技能清单】以下技能可能相关。若当前任务确实涉及某项，调用 load_skill 加载其完整规范后遵循：\n"
                    + String.join("\n", skillMatch.catalog()));
        }
        if (!skillParts.isEmpty()) {
            messages.add(Map.of("role", "system", "content", String.join("\n\n", skillParts)));
        }
        // 长期记忆：分层注入（P2-1）——核心记忆常驻 + 场景记忆按问题关键词筛选；
        // 带 ID 供 delete_memory 定位
        List<com.devmind.agent.dto.MemoryItem> memory =
                memoryManager.selectForQuestion(memoryRepository.listByUser(userId), question);
        if (memory != null && !memory.isEmpty()) {
            String memoryText = memory.stream()
                    .map(m -> "【记忆 ID " + m.id() + "】" + m.key() + ": " + m.value())
                    .collect(java.util.stream.Collectors.joining("；"));
            messages.add(Map.of("role", "system", "content", "【用户长期记忆】" + memoryText + "（回答时可参考这些用户偏好；用户要求删除某条时用 delete_memory）"));
        }
        // 多轮记忆：加载该会话最近的历史消息作为上下文
        if (conversationId != null && conversationId > 0) {
            List<AgentMessage> history = conversationRepository.listMessages(conversationId);
            if (history != null && !history.isEmpty()) {
                int from = Math.max(0, history.size() - 6);
                for (int i = from; i < history.size(); i++) {
                    AgentMessage historyItem = history.get(i);
                    messages.add(Map.of("role", historyItem.role(), "content", historyItem.content()));
                }
            }
        }
        if (request.history() != null) {
            for (AgentChatRequest.HistoryItem item : request.history()) {
                messages.add(Map.of("role", item.role(), "content", item.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", question));

        List<ToolTraceItem> trace = new ArrayList<>();
        // 工具可见性：按用户授权过滤（内置工具全部可用，动态接口工具需被授权）
        Long tenantId = userService.tenantIdOf(userId);
        Set<String> accessible = toolAccessService.accessibleToolNames(tenantId, userId);
        // 接口工具发现（P1 接口语义化闭环 / guide-57 M2）：
        // 授权的接口工具数量超过阈值时，改用"自然语言 → 语义检索命中"注入最相关的接口，
        // 避免几百个接口全量注入导致模型上下文膨胀；命中为空/检索失败时回退全量（保持接口可用）。
        List<com.devmind.tool.ToolDefinition> interfaceTools =
                toolAccessService.accessibleDynamicTools(tenantId, userId);
        Set<String> interfaceNames = interfaceTools.stream()
                .map(com.devmind.tool.ToolDefinition::name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> injectedInterfaceNames = interfaceNames.size() > MAX_INTERFACE_TOOLS_FULL_INJECT
                ? resolveInterfaceInjection(question, userId, interfaceNames)
                : interfaceNames;
        List<AiModelGateway.ToolSpec> tools = new ArrayList<>(toolRegistry.all().stream()
                .filter(tool -> accessible.contains(tool.name()))
                // 接口工具按语义命中集注入；内置/MCP 工具不受影响
                .filter(tool -> !interfaceNames.contains(tool.name()) || injectedInterfaceNames.contains(tool.name()))
                .map(tool -> new AiModelGateway.ToolSpec(tool.name(), tool.description(), tool.parametersJsonSchema()))
                .toList());
        // 注入内部工具（P2 拆分：定义集中在 AgentTools）
        tools.add(new AiModelGateway.ToolSpec(AgentTools.PLAN_TOOL_NAME, AgentTools.PLAN_TOOL_DESC, AgentTools.PLAN_TOOL_SCHEMA));
        tools.add(new AiModelGateway.ToolSpec(AgentTools.UPDATE_SKILL_TOOL_NAME, AgentTools.UPDATE_SKILL_TOOL_DESC, AgentTools.UPDATE_SKILL_TOOL_SCHEMA));
        tools.add(new AiModelGateway.ToolSpec(AgentTools.LOAD_SKILL_TOOL_NAME, AgentTools.LOAD_SKILL_TOOL_DESC, AgentTools.LOAD_SKILL_TOOL_SCHEMA));
        tools.add(new AiModelGateway.ToolSpec(AgentTools.DELETE_MEMORY_TOOL_NAME, AgentTools.DELETE_MEMORY_TOOL_DESC, AgentTools.DELETE_MEMORY_TOOL_SCHEMA));
        tools.add(new AiModelGateway.ToolSpec(AgentTools.RUN_WORKFLOW_TOOL_NAME, AgentTools.RUN_WORKFLOW_TOOL_DESC, AgentTools.RUN_WORKFLOW_TOOL_SCHEMA));
        // M4 沉淀复用：命中技能 references 声明的接口工具直接注入（技能明确依赖，不依赖语义检索）
        if (skillMatch.linkedInterfaceTools() != null && !skillMatch.linkedInterfaceTools().isEmpty()) {
            for (String toolName : skillMatch.linkedInterfaceTools()) {
                AgentTool declared = toolRegistry.get(toolName);
                boolean already = tools.stream().anyMatch(s -> s.name().equals(toolName));
                if (declared != null && accessible.contains(toolName) && !already) {
                    tools.add(new AiModelGateway.ToolSpec(
                            declared.name(), declared.description(), declared.parametersJsonSchema()));
                    log.info("技能声明接口注入：{}（技能依赖，直接可用）", toolName);
                }
            }
        }

        try {
            // 计划失败后是否还能引导模型重规划（限 1 次，避免死循环）
            boolean replanAllowed = true;
            // 上一轮工具名（重复调用收敛检测：真实模型下防止兜圈子耗尽轮数）
            List<String> lastToolNames = List.of();
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                AiModelGateway.ChatResult result = chatRouter.chatWithTools(SYSTEM_PROMPT, messages, tools);
                if (result == null) {
                    throw new IllegalStateException("模型网关返回空结果");
                }
                recordUsage(userId, result, question);
                // 模型原生思考过程（reasoning）：实时透传给调用方（SSE thinking 事件）
                String reasoning = result.reasoning();
                if (reasoning != null && !reasoning.isBlank() && onThinking != null) {
                    onThinking.accept(reasoning);
                }
                List<AiModelGateway.ToolCall> toolCalls = result.toolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String answer = result.content() == null ? "" : result.content();
                    conversationStore.saveMessages(conversationId, rawQuestion, answer);
                    memoryManager.extractMemory(userId, rawQuestion, answer);
                    return new AgentChatResponse(conversationId, answer, List.of(), trace);
                }
                // 回填 assistant（含 tool_calls）
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", result.content() == null ? "" : result.content());
                assistantMsg.put("tool_calls", toolCalls.stream()
                        .map(tc -> Map.of(
                                "id", tc.id(),
                                "type", "function",
                                "function", Map.of("name", tc.name(), "arguments", tc.argumentsJson())
                        ))
                        .toList());
                messages.add(assistantMsg);
                // 工具执行：plan 走计划执行器（顺序执行）；普通工具并发执行（结果按原顺序回填）
                boolean hasPlan = false;
                boolean planAllOk = true;
                List<AiModelGateway.ToolCall> parallelCalls = new ArrayList<>();
                Map<AiModelGateway.ToolCall, java.util.concurrent.Future<AgentToolExecutor.ToolExecOutcome>> futures = new LinkedHashMap<>();
                for (AiModelGateway.ToolCall tc : toolCalls) {
                    if (AgentTools.PLAN_TOOL_NAME.equals(tc.name())) {
                        hasPlan = true;
                        if (!toolExecutor.executePlan(tc, userId, messages, trace, onTrace, conversationId)) {
                            planAllOk = false;
                        }
                    } else if (AgentTools.UPDATE_SKILL_TOOL_NAME.equals(tc.name())) {
                        // 对话式修正技能：特判执行，结果回填
                        AgentToolExecutor.ToolExecOutcome outcome = toolExecutor.executeUpdateSkill(tc, userId);
                        trace.add(toolExecutor.backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } else if (AgentTools.LOAD_SKILL_TOOL_NAME.equals(tc.name())) {
                        // 按需加载技能全文：特判执行，结果回填
                        AgentToolExecutor.ToolExecOutcome outcome = toolExecutor.executeLoadSkill(tc, userId);
                        trace.add(toolExecutor.backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } else if (AgentTools.DELETE_MEMORY_TOOL_NAME.equals(tc.name())) {
                        // 对话式删除长期记忆：特判执行，结果回填
                        AgentToolExecutor.ToolExecOutcome outcome = toolExecutor.executeDeleteMemory(tc, userId);
                        trace.add(toolExecutor.backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } else if (AgentTools.RUN_WORKFLOW_TOOL_NAME.equals(tc.name())) {
                        // 技能引用工作流联动执行：特判执行，结果回填
                        AgentToolExecutor.ToolExecOutcome outcome = toolExecutor.executeRunWorkflow(tc, userId);
                        trace.add(toolExecutor.backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } else {
                        parallelCalls.add(tc);
                        futures.put(tc, toolExecutor.submitExecute(tc, userId));
                    }
                }
                // 并发工具：按 tool_calls 原顺序等待结果并回填，保证 tool 消息与调用一一对应
                for (AiModelGateway.ToolCall tc : parallelCalls) {
                    try {
                        AgentToolExecutor.ToolExecOutcome outcome = futures.get(tc)
                                .get(AgentTools.TOOL_TIMEOUT_SECONDS + 5L, java.util.concurrent.TimeUnit.SECONDS);
                        trace.add(toolExecutor.backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } catch (Exception ex) {
                        // 理论不可达：executeToolCore 内部已捕获所有异常并返回失败结果
                        AgentToolExecutor.ToolExecOutcome outcome = new AgentToolExecutor.ToolExecOutcome(
                                "{\"error\": \"工具执行异常: " + ex.getMessage() + "\"}", false, 0);
                        trace.add(toolExecutor.backfillTool(tc, outcome, messages, conversationId, onTrace));
                    }
                }
                // 重复工具调用收敛：连续调用相同工具时注入提示（真实模型下防止兜圈子耗尽轮数）
                if (isRepeatToolCall(toolCalls, lastToolNames)) {
                    meterRegistry.counter("devmind.agent.repeat_hint").increment();
                    log.warn("agent 重复调用工具 {}，注入收敛提示", lastToolNames);
                    messages.add(Map.of(
                            "role", "system",
                            "content", "【提示】你已经调用过相同工具并拿到了结果（见上方工具返回）。请直接基于已有结果回答用户问题，不要重复调用同一工具。"
                    ));
                }
                lastToolNames = toolCalls.stream().map(AiModelGateway.ToolCall::name).toList();
                // 计划中有步骤失败：提示模型重新规划（仅一次），下一轮模型可提交新计划或直接回答
                if (hasPlan && !planAllOk && replanAllowed) {
                    meterRegistry.counter("devmind.agent.replan_total").increment();
                    log.warn("agent 计划执行有步骤失败，提示模型重新规划");
                    messages.add(Map.of(
                            "role", "system",
                            "content", "【提示】上一步计划中有步骤执行失败（见工具返回的错误信息）。请重新规划一个更合理的计划，或直接基于已有信息回答。"
                    ));
                    replanAllowed = false;
                }
            }
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "Agent 工具调用轮数超限");
        } catch (Exception ex) {
            log.warn("agent 链路失败，降级本地 RAG: {}", ex.getMessage());
            AgentChatResponse fallback = fallbackToLocalRag(conversationId, rawQuestion, userId, trace);
            // 携带上传文件时降级：明确告知文件未参与分析，避免误导
            if (request.fileIds() != null && !request.fileIds().isEmpty()) {
                fallback = new AgentChatResponse(
                        fallback.conversationId(),
                        "（提示：大模型暂不可用，上传的文件未参与分析，以下为知识库兜底回答）\n\n" + fallback.answer(),
                        fallback.references(),
                        fallback.toolTrace()
                );
            }
            conversationStore.saveMessages(conversationId, rawQuestion, fallback.answer());
            return fallback;
        }
    }

    /**
     * 接口工具语义发现：按自然语言问题对接口语义档案做向量检索，返回可注入的接口名集合。
     * 命中为空或检索失败时回退全量（保持接口可用，语义注入是增强而非必需）。
     */
    private Set<String> resolveInterfaceInjection(String question, Long userId, Set<String> interfaceNames) {
        if (openApiImportService == null) {
            return interfaceNames;
        }
        try {
            List<com.devmind.tool.ToolSemanticRepository.SemanticHit> hits =
                    openApiImportService.semanticSearch(question, userId, MAX_INTERFACE_TOOLS_INJECT);
            Set<String> hitNames = hits.stream()
                    .map(com.devmind.tool.ToolSemanticRepository.SemanticHit::name)
                    .filter(interfaceNames::contains)
                    .collect(java.util.stream.Collectors.toSet());
            if (!hitNames.isEmpty()) {
                log.info("接口工具语义发现：授权 {} 个接口，按问题命中注入 {} 个",
                        interfaceNames.size(), hitNames.size());
                return hitNames;
            }
        } catch (Exception e) {
            log.warn("接口工具语义发现失败，回退全量注入: {}", e.getMessage());
        }
        return interfaceNames;
    }

    /** 把上传文件的文本注入为问题上下文（文件分析场景） */
    private String enrichWithFiles(String question, List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder();
        for (String fileId : fileIds) {
            ChatFileStore.ChatFile file = chatFileStore.get(fileId, userId);
            if (file != null) {
                sb.append("【上传文件：").append(file.fileName()).append("】\n")
                        .append(file.text()).append("\n\n");
            }
        }
        if (sb.isEmpty()) {
            return question;
        }
        return sb + "用户问题：" + question;
    }

    /** 查询会话消息（历史展示，P2：委托会话存储） */
    public List<AgentMessage> messages(Long conversationId, Long userId) {
        return conversationStore.messages(conversationId, userId);
    }

    /** 查询会话工具调用轨迹（历史展示，P2：委托会话存储） */
    public List<ToolTraceItem> trace(Long conversationId, Long userId) {
        return conversationStore.trace(conversationId, userId);
    }

    /** 查询长期记忆（P2：委托记忆管理） */
    public List<com.devmind.agent.dto.MemoryItem> memory(Long userId) {
        return memoryManager.memory(userId);
    }

    /** 更新长期记忆（全量覆盖，P2：委托记忆管理） */
    public void updateMemory(MemoryUpdateRequest request, Long userId) {
        memoryManager.updateMemory(request, userId);
    }

    /** 删除单条长期记忆（P2：委托记忆管理） */
    public void deleteMemory(Long id, Long userId) {
        memoryManager.deleteMemory(id, userId);
    }

    /**
     * 重复工具调用检测（收敛）：本轮工具名集合与上一轮相同（未引入新工具）且非空 → 判定重复。
     * 用于注入"不要重复调用同一工具"提示，给模型一次收敛机会，避免真实模型兜圈子耗尽轮数。
     */
    private boolean isRepeatToolCall(List<AiModelGateway.ToolCall> toolCalls, List<String> lastToolNames) {
        if (toolCalls == null || toolCalls.isEmpty() || lastToolNames.isEmpty()) {
            return false;
        }
        for (AiModelGateway.ToolCall tc : toolCalls) {
            if (!lastToolNames.contains(tc.name())) {
                return false; // 本轮引入新工具，不算重复
            }
        }
        return true;
    }

    private AgentChatResponse fallbackToLocalRag(
            Long conversationId,
            String question,
            Long userId,
            List<ToolTraceItem> trace
    ) {
        meterRegistry.counter("devmind.rag.degraded").increment();
        try {
            Long kbId = firstAccessibleKnowledgeBase(userId);
            if (kbId == null) {
                return new AgentChatResponse(conversationId, "Agent 暂不可用，且没有可访问的知识库。", List.of(), trace);
            }
            List<RetrievalResult> results = searchWithFallback(kbId, question);
            if (results.isEmpty()) {
                return new AgentChatResponse(conversationId, "知识库中没有找到足够相关内容。", List.of(), trace);
            }
            String answer = LocalRagAnswerer.answer(question, results) + "\n\n（本地降级模式）";
            List<Reference> refs = results.stream()
                    .map(r -> new Reference(
                            r.documentId(),
                            r.documentName(),
                            r.chunkId(),
                            AgentTools.truncate(r.content(), 300),
                            round(r.similarityScore()),
                            r.metadata()
                    ))
                    .toList();
            return new AgentChatResponse(conversationId, answer, refs, trace);
        } catch (Exception ex) {
            log.error("agent 本地降级失败", ex);
            return new AgentChatResponse(conversationId, "Agent 暂不可用，请稍后重试。", List.of(), trace);
        }
    }

    private Long firstAccessibleKnowledgeBase(Long userId) {
        KnowledgeBaseListResponse list = knowledgeBaseService.list(userId);
        if (list.items() == null || list.items().isEmpty()) {
            return null;
        }
        // 优先选有文档的库，避免检索空库
        return list.items().stream()
                .filter(item -> "ENABLED".equals(item.status()) && item.documentCount() != null && item.documentCount() > 0)
                .findFirst()
                .map(item -> item.id())
                .orElseGet(() -> list.items().stream()
                        .filter(item -> "ENABLED".equals(item.status()))
                        .findFirst()
                        .map(item -> item.id())
                        .orElse(null));
    }

    private List<RetrievalResult> searchWithFallback(Long kbId, String question) {
        try {
            List<Double> vector = modelGateway.embed(List.of(question)).get(0);
            return retrievalService.searchHybrid(
                    kbId,
                    vector,
                    question,
                    properties.retrievalTopK(),
                    properties.retrievalMinScore(),
                    properties.retrievalVectorWeight(),
                    properties.retrievalKeywordWeight(),
                    properties.retrievalHybridEnabled()
            );
        } catch (Exception ex) {
            log.warn("agent 降级检索 embedding 不可用，走关键词检索: {}", ex.getMessage());
            return retrievalService.searchByKeywords(kbId, question, properties.retrievalTopK(), Map.of());
        }
    }

    private void recordUsage(Long userId, AiModelGateway.ChatResult result, String question) {
        try {
            modelUsageService.record(
                    userId,
                    "agent",
                    result.model(),
                    result.promptTokens(),
                    result.completionTokens(),
                    question,
                    result.content() == null ? "" : result.content()
            );
        } catch (Exception ex) {
            log.warn("agent 用量记录失败: {}", ex.getMessage());
        }
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
