package com.devmind.metrics;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产品运营指标（产品审视2 盲区 D/E 落地，见 docs/product/产品审视2-运营体验盲区-20260816.md）：
 * - 管理员漏斗：注册 → 建库 → 传文档 → 提问 → 生成工作流 → 用 Agent（激活漏斗，看用户卡在哪一步）；
 * - 当前用户资产快照：知识库/文档/技能/工作流/会话/累计提问（留存钩子，制造"资产成长感"）。
 * 数据源全部复用现有表（app_user / knowledge_base / audit_log / tool_call_log / skill / workflow / chat_conversation），零新增表。
 */
@Service
@SuppressWarnings("null")
public class ProductMetricsService {

    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;

    public ProductMetricsService(JdbcTemplate jdbcTemplate, UserService userService) {
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
    }

    /** 管理员：全租户激活漏斗 + 活跃概览 */
    public Map<String, Object> productMetrics(Long operatorId) {
        if (!userService.isAdmin(operatorId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看产品数据");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        // 漏斗：每个阶段"到达过该动作的独立用户数"（激活路径各环节流失）
        m.put("totalUsers", count("SELECT COUNT(*) FROM app_user"));
        m.put("usersCreatedKb", count("SELECT COUNT(DISTINCT created_by) FROM knowledge_base WHERE status='ENABLED'"));
        m.put("usersUploadedDoc", count("SELECT COUNT(DISTINCT user_id) FROM audit_log WHERE action='UPLOAD_DOCUMENT'"));
        m.put("usersAsked", count("SELECT COUNT(DISTINCT user_id) FROM audit_log WHERE action='CHAT'"));
        m.put("usersGeneratedWorkflow", count("SELECT COUNT(DISTINCT user_id) FROM audit_log WHERE action='CREATE_WORKFLOW'"));
        m.put("usersUsedAgent", count("SELECT COUNT(DISTINCT user_id) FROM tool_call_log WHERE source='agent'"));
        // 活跃
        m.put("activeUsers7d", count("""
                SELECT COUNT(DISTINCT user_id) FROM audit_log
                WHERE created_time >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                """));
        m.put("asks7d", count("""
                SELECT COUNT(*) FROM audit_log
                WHERE action='CHAT' AND created_time >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                """));
        m.put("workflowRuns7d", count("""
                SELECT COUNT(*) FROM workflow_run
                WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                """));
        // 每日活跃（近 7 天）
        m.put("dailyActive", jdbcTemplate.queryForList("""
                SELECT to_char(created_time, 'MM-DD') AS day, COUNT(DISTINCT user_id) AS active_users
                FROM audit_log
                WHERE created_time >= CURRENT_TIMESTAMP - INTERVAL '7 days'
                GROUP BY 1 ORDER BY 1
                """));
        return m;
    }

    /** 当前用户资产快照（留存钩子：让用户看到自己在平台上的积累） */
    public Map<String, Object> mySummary(Long userId) {
        userService.requireUser(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kbCount", count("SELECT COUNT(DISTINCT knowledge_base_id) FROM knowledge_base_member WHERE user_id = ?", userId));
        m.put("docCount", count("""
                SELECT COUNT(*) FROM document d
                JOIN knowledge_base_member m ON m.knowledge_base_id = d.knowledge_base_id
                WHERE m.user_id = ? AND d.status = 'COMPLETED'
                """, userId));
        m.put("skillCount", count("SELECT COUNT(*) FROM skill WHERE created_by = ? AND enabled = TRUE", userId));
        m.put("workflowCount", count("SELECT COUNT(*) FROM workflow WHERE created_by = ? AND status != 'DISABLED'", userId));
        m.put("conversationCount", count("SELECT COUNT(*) FROM chat_conversation WHERE user_id = ?", userId));
        m.put("askCount", count("SELECT COUNT(*) FROM audit_log WHERE user_id = ? AND action = 'CHAT'", userId));
        m.put("toolCallCount", count("SELECT COUNT(*) FROM tool_call_log WHERE user_id = ?", userId));
        return m;
    }

    private long count(String sql, Object... args) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }
}
