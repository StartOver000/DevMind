package com.devmind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核心链路集成测试（复用本地 devmind-postgres 容器的独立 devmind_test 库 + mock 模型 + 开启认证）：
 * 覆盖未登录拦截、登录→建库→上传→异步处理→问答 全链路、越权防护、上传安全校验。
 * 前置条件：devmind-postgres 容器运行于 localhost:5432（devmind/devmind123，superuser）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/devmind_test",
        "spring.datasource.username=devmind",
        "spring.datasource.password=devmind123",
        "devmind.model-mode=mock",
        "devmind.embedding-dimensions=8",
        "devmind.auth-enabled=true",
        "devmind.retrieval-min-score=0.0",
        "devmind.max-file-size-mb=1",
        "devmind.storage-path=./target/test-files",
        "devmind.task-scan-interval-ms=600000",
        "devmind.task-scan-initial-delay-ms=600000",
        "devmind.security.rate-limit-per-minute=10000",
        "devmind.security.rate-limit-login-per-minute=1000"
})
class DevMindIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String demoToken;

    @BeforeAll
    static void prepareTestDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/postgres", "devmind", "devmind123");
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS devmind_test");
            st.execute("CREATE DATABASE devmind_test");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        demoToken = login("demo", "demo123");
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullFlowLoginCreateUploadProcessAndChat() throws Exception {
        long knowledgeBaseId = createKnowledgeBase(demoToken, "集成测试库");

        MvcResult upload = mvc.perform(multipart("/api/knowledge-bases/{id}/documents", knowledgeBaseId)
                .file(new MockMultipartFile("file", "mysql.md", "text/markdown",
                        ("# MySQL 专题\n\n## 深分页为什么慢\n\n深分页使用 LIMIT OFFSET，offset 越大需要扫描丢弃的前置记录越多，耗时持续增加。\n\n"
                                + "## 索引失效\n\n对索引列使用函数、隐式类型转换会导致索引失效。")
                                .getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn();
        long documentId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asLong();

        awaitTaskSucceeded(documentId);

        mvc.perform(get("/api/knowledge-bases/{id}/documents", knowledgeBaseId)
                .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("COMPLETED"));

        MvcResult chat = mvc.perform(post("/api/knowledge-bases/{id}/chat", knowledgeBaseId)
                .header("Authorization", "Bearer " + demoToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"深分页为什么慢\",\"topK\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").isNotEmpty())
                .andExpect(jsonPath("$.references").isArray())
                .andReturn();
        JsonNode chatJson = objectMapper.readTree(chat.getResponse().getContentAsString());
        assertThat(chatJson.get("answer").asText()).isNotBlank();
        assertThat(chatJson.get("references").size()).isGreaterThan(0);
    }

    @Test
    void nonMemberIsForbidden() throws Exception {
        long knowledgeBaseId = createKnowledgeBase(demoToken, "私有库");
        String bobToken = registerAndLogin("bob", "bob123456");

        mvc.perform(get("/api/knowledge-bases/{id}/documents", knowledgeBaseId)
                .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnsupportedFileType() throws Exception {
        long knowledgeBaseId = createKnowledgeBase(demoToken, "文件校验库");

        mvc.perform(multipart("/api/knowledge-bases/{id}/documents", knowledgeBaseId)
                .file(new MockMultipartFile("file", "malware.txt", "text/plain",
                        "hello".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedFile() throws Exception {
        long knowledgeBaseId = createKnowledgeBase(demoToken, "大小校验库");

        byte[] big = new byte[2 * 1024 * 1024]; // 超过 1MB 限制
        mvc.perform(multipart("/api/knowledge-bases/{id}/documents", knowledgeBaseId)
                .file(new MockMultipartFile("file", "big.md", "text/markdown", big))
                .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ----

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String registerAndLogin(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private long createKnowledgeBase(String token, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/knowledge-bases")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void awaitTaskSucceeded(long documentId) throws Exception {
        for (int i = 0; i < 40; i++) {
            MvcResult result = mvc.perform(get("/api/documents/{id}/task", documentId)
                    .header("Authorization", "Bearer " + demoToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String status = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("status").asText();
            if ("SUCCEEDED".equals(status)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("文档处理任务未在预期时间内完成");
    }
}
