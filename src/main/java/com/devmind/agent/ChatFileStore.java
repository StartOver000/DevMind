package com.devmind.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上传文件的文本缓存（MVP）：
 * 上传文件后提取文本存入内存，供 Agent 对话注入上下文。
 * - 按用户隔离，限制每用户文件数与单文件文本长度，防内存膨胀；
 * - 进程重启后 fileId 失效（前端重新上传即可），符合"临时分析用文件"的定位。
 */
@Component
public class ChatFileStore {

    /** 每用户最多缓存文件数 */
    static final int MAX_FILES_PER_USER = 20;
    /** 单文件注入模型的文本上限（字符） */
    public static final int MAX_TEXT_CHARS = 20000;

    public record ChatFile(Long userId, String fileName, String text) {
    }

    private final Map<String, ChatFile> files = new ConcurrentHashMap<>();
    private final Map<Long, java.util.Deque<String>> userFileIds = new ConcurrentHashMap<>();

    public String put(Long userId, String fileName, String text) {
        String id = UUID.randomUUID().toString().replace("-", "");
        String truncated = text == null ? "" : text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        files.put(id, new ChatFile(userId, fileName, truncated));
        java.util.Deque<String> deque = userFileIds.computeIfAbsent(userId, k -> new java.util.ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(id);
            while (deque.size() > MAX_FILES_PER_USER) {
                String oldest = deque.removeFirst();
                files.remove(oldest);
            }
        }
        return id;
    }

    /** 取文件内容；校验归属用户 */
    public ChatFile get(String fileId, Long userId) {
        ChatFile file = files.get(fileId);
        if (file == null || !file.userId().equals(userId)) {
            return null;
        }
        return file;
    }

    public void remove(String fileId) {
        files.remove(fileId);
    }
}
