package com.devmind.agent;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.document.parser.DocumentParserRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 对话文件上传（文件处理一等公民）：
 * 上传文件 → 提取文本 → 返回 fileId，Agent 对话携带 fileIds 时注入为分析上下文。
 * 支持 txt/md/pdf/docx 等（复用知识库解析器，Tika 自动检测）。
 */
@RestController
@SuppressWarnings("null")
public class ChatFileController {

    private static final Logger log = LoggerFactory.getLogger(ChatFileController.class);
    /** 单文件大小上限（10MB） */
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024L;

    private final DocumentParserRegistry parserRegistry;
    private final ChatFileStore fileStore;

    public ChatFileController(DocumentParserRegistry parserRegistry, ChatFileStore fileStore) {
        this.parserRegistry = parserRegistry;
        this.fileStore = fileStore;
    }

    @PostMapping(value = "/api/chat/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        String fileName = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "文件为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "文件超过 10MB 限制");
        }
        try {
            byte[] bytes = file.getBytes();
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
            String text = parserRegistry.parse(fileName, normalizeFileType(ext), bytes);
            String fileId = fileStore.put(userId, fileName, text);
            log.info("对话文件上传成功 (user={}, file={}, textLength={})", userId, fileName, text.length());
            return Map.of(
                    "fileId", fileId,
                    "fileName", fileName,
                    "textLength", text.length(),
                    "truncated", text.length() > ChatFileStore.MAX_TEXT_CHARS
            );
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("对话文件解析失败 (file={}): {}", fileName, ex.getMessage());
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "文件解析失败，请确认格式受支持（txt/md/pdf/docx 等）");
        }
    }

    /** 扩展名 → 解析器类型（与知识库归一化一致；txt 也按文本解析） */
    private String normalizeFileType(String extension) {
        if ("md".equals(extension) || "markdown".equals(extension) || "txt".equals(extension)) {
            return "markdown";
        }
        if ("pdf".equals(extension)) {
            return "pdf";
        }
        if ("doc".equals(extension) || "docx".equals(extension)) {
            return "docx";
        }
        if ("xls".equals(extension) || "xlsx".equals(extension)) {
            return "xlsx";
        }
        if ("ppt".equals(extension) || "pptx".equals(extension)) {
            return "pptx";
        }
        return extension;
    }
}
