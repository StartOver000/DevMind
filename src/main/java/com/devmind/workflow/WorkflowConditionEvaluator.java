package com.devmind.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工作流条件求值：用 SpEL 评估 if 分支的条件表达式。
 * 条件中 {{var}} 引用上一步输出（如 {{sales}} > 10000、{{status}} == 'success'）。
 */
@Component
@SuppressWarnings("null")
public class WorkflowConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConditionEvaluator.class);

    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** 评估条件；非法表达式/缺变量按 false 处理（分支跳过） */
    public boolean evaluate(String condition, Map<String, Object> vars) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        try {
            // {{var}} → SpEL 变量引用 #var（变量名需为合法标识符）
            String spel = condition.replaceAll("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)}}", "#$1");
            // 自然语言常见写法：{{x}} contains 'y' → #x.contains('y')（SpEL 无 contains 操作符）
            // 只处理右操作数为字符串字面量的最常见形式；not contains → 取反
            spel = spel.replaceAll("(?i)(#?[a-zA-Z_][a-zA-Z0-9_.]*)\\s+not\\s+contains\\s+('[^']*')", "!$1.contains($2)");
            spel = spel.replaceAll("(?i)(#?[a-zA-Z_][a-zA-Z0-9_.]*)\\s+contains\\s+('[^']*')", "$1.contains($2)");
            Expression expression = parser.parseExpression(spel);
            StandardEvaluationContext context = new StandardEvaluationContext();
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                context.setVariable(entry.getKey(), normalize(entry.getValue()));
            }
            Object result = expression.getValue(context);
            return Boolean.TRUE.equals(result);
        } catch (Exception ex) {
            log.warn("工作流条件求值失败 (condition={}): {}", condition, ex.getMessage());
            return false;
        }
    }

    /** 工具输出是字符串，条件比较数字时转成数值（"100" → 100L），字符串保持原样 */
    private Object normalize(Object value) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
            try {
                return Long.parseLong(trimmed);
            } catch (Exception ignored) {
                // not a long
            }
            try {
                return Double.parseDouble(trimmed);
            } catch (Exception ignored) {
                // not a number
            }
        }
        return value;
    }
}
