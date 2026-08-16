package com.devmind.knowledge;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.document.DocumentService;
import com.devmind.document.dto.DocumentUploadResponse;
import com.devmind.knowledge.dto.CreateKnowledgeBaseRequest;
import com.devmind.knowledge.dto.DemoKnowledgeBaseResponse;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.knowledge.dto.KnowledgeBaseResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 一键示例知识库（产品运营盲区修复：冷启动空库，见 docs/product/产品审视2-运营体验盲区-20260816.md）。
 *
 * 新用户进来没有任何文档，看不到产品价值。此服务提供内置示例：
 * - 创建「示例知识库」；
 * - 上传 classpath 内置示例文档并走标准向量化任务链路（切块→embedding→入库）。
 * 幂等：用户已有同名示例库时直接返回，不重复创建。
 */
@Service
public class DemoKnowledgeBaseService {

    private static final String DEMO_KB_NAME = "示例知识库";
    private static final String DEMO_DESC = "内置示例：上传即问，体验知识库检索（可删除）";
    private static final String DEMO_FILE = "samples/mysql-index-demo.md";
    private static final String DEMO_FILE_NAME = "mysql-index-demo.md";
    private static final String DEMO_TAG = "示例";

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;

    public DemoKnowledgeBaseService(
            KnowledgeBaseService knowledgeBaseService,
            DocumentService documentService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
    }

    public DemoKnowledgeBaseResponse createDemo(Long userId) {
        KnowledgeBaseListResponse list = knowledgeBaseService.list(userId);
        KnowledgeBaseItem existing = list.items().stream()
                .filter(kb -> DEMO_KB_NAME.equals(kb.name()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return new DemoKnowledgeBaseResponse(existing.id(), existing.name(), null, null, true);
        }

        KnowledgeBaseResponse kb = knowledgeBaseService.create(
                new CreateKnowledgeBaseRequest(DEMO_KB_NAME, DEMO_DESC, null),
                userId
        );
        try {
            byte[] content = new ClassPathResource(DEMO_FILE).getInputStream().readAllBytes();
            DocumentUploadResponse doc = documentService.upload(
                    kb.id(),
                    new ByteArrayMultipartFile(DEMO_FILE_NAME, "text/markdown", content),
                    DEMO_TAG,
                    userId
            );
            return new DemoKnowledgeBaseResponse(kb.id(), kb.name(), doc.id(), doc.fileName(), false);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "内置示例文档加载失败");
        }
    }

    /**
     * 轻量内存 MultipartFile：避免引入 spring-test 的 MockMultipartFile 依赖（Maven 离线约束）。
     */
    private static final class ByteArrayMultipartFile implements MultipartFile {

        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        private ByteArrayMultipartFile(String originalFilename, String contentType, byte[] content) {
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException, IllegalStateException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
