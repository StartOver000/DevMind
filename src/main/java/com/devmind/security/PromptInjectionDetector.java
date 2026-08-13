package com.devmind.security;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt 注入启发式检测器（P2 安全专项）。
 *
 * 威胁模型：外部系统通过 webhook 把请求体 JSON 注入为工作流初始变量，若工作流步骤
 * 将变量拼入 LLM prompt（如 ai_generate 的 {{var}}），恶意 payload 可操控 AI 行为
 * （诱导泄露系统提示、越权指令、越狱等）。检测器在变量注入前扫描全部字符串值，
 * 命中常见注入模式即拒绝本次触发。
 *
 * 采用启发式正则（中英文常见模式），刻意保守——宁可误报拒绝，不让注入内容进入 LLM。
 */
@Component
public class PromptInjectionDetector {

    /** 检测命中结果 */
    public record Detection(boolean hit, List<String> matches) {
        public static final Detection CLEAN = new Detection(false, List.of());
    }

    /** 常见注入/越狱模式（小写匹配） */
    private static final List<Pattern> PATTERNS = List.of(
            // 中文：忽略/无视指令
            Pattern.compile("忽略(之前|以上|上面|上述|前述)?(的|所有|全部)?(指令|命令|提示|要求|规则|内容)"),
            Pattern.compile("无视(之前|以上|上面|上述|所有|前述)?(的|所有|全部)?(指令|命令|提示|要求|规则|内容)"),
            Pattern.compile("不要(管|理会|遵守|遵循)(之前|以上|上面|上述|所有)?(的|所有)?(指令|命令|提示|要求|规则)"),
            Pattern.compile("从现在起|你现在是|你的角色是|重新定义(你的|自己的)?角色"),
            Pattern.compile("透露|输出|展示(你的|系统)?(系统)?(提示词|提示|system\\s*prompt)"),
            Pattern.compile("你是(一个|个)?\\s*(ai|人工智能|助手)"),
            Pattern.compile("越狱|突破限制|绕过(限制|安全)"),
            // 英文：ignore/disregard/role-play 等
            Pattern.compile("ignore (all |any |every |the )?(previous|above|prior|earlier|system)"),
            Pattern.compile("disregard (all |any |the )?(previous|above|prior|earlier|system)"),
            Pattern.compile("forget (everything|all (the )?instructions|your instructions)"),
            Pattern.compile("you are now|act as|your new role|from now on"),
            Pattern.compile("reveal (your|the) (system )?(prompt|instructions)"),
            Pattern.compile("jailbreak|bypass (the |your )?(restrictions|safety)"),
            Pattern.compile("system\\s*prompt", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 扫描一个值（String/List/Map 递归）是否含注入模式。
     *
     * @param value 待扫描的值（webhook payload 中的任意嵌套值）
     * @return 命中时返回 hit=true 及命中的原始文本片段
     */
    public Detection inspect(Object value) {
        if (value == null) {
            return Detection.CLEAN;
        }
        List<String> matches = new ArrayList<>();
        scan(value, matches);
        return matches.isEmpty() ? Detection.CLEAN : new Detection(true, matches);
    }

    private void scan(Object value, List<String> matches) {
        if (value instanceof String s) {
            checkString(s, matches);
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                scan(v, matches);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                scan(item, matches);
            }
        }
    }

    private void checkString(String s, List<String> matches) {
        String lower = s.toLowerCase();
        for (Pattern p : PATTERNS) {
            var matcher = p.matcher(lower);
            if (matcher.find()) {
                int start = Math.max(0, matcher.start() - 12);
                int end = Math.min(s.length(), matcher.end() + 12);
                String snippet = s.substring(start, end).replaceAll("\\s+", " ").trim();
                if (snippet.length() > 40) {
                    snippet = snippet.substring(0, 40) + "…";
                }
                matches.add(snippet);
            }
        }
    }
}
