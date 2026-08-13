package com.devmind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2 越权专项测试（同租户内跨用户资源隔离）：
 * 覆盖工作流（private/team scope）、技能（personal/team scope）的可见性与可管理性边界。
 * 前置条件：devmind-postgres 容器运行于 localhost:5432（devmind/devmind123，superuser）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/devmind_test_auth",
        "spring.datasource.username=devmind",
        "spring.datasource.password=devmind123",
        "devmind.model-mode=mock",
        "devmind.embedding-dimensions=8",
        "devmind.auth-enabled=true",
        "devmind.retrieval-min-score=0.0",
        "devmind.storage-path=./target/test-files-auth",
        "devmind.task-scan-interval-ms=600000",
        "devmind.task-scan-initial-delay-ms=600000",
        "devmind.security.rate-limit-per-minute=10000",
        "devmind.security.rate-limit-login-per-minute=1000"
})
class AuthorizationIsolationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String aliceToken;
    private String bobToken;

    @BeforeAll
    static void prepareTestDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/postgres", "devmind", "devmind123");
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS devmind_test_auth");
            st.execute("CREATE DATABASE devmind_test_auth");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        aliceToken = registerOrLogin("alice_auth", "alice12345");
        bobToken = registerOrLogin("bob_auth", "bob123456");
    }

    // ---------- 工作流：private 隔离 ----------

    @Test
    void privateWorkflowIsHiddenFromOtherUsers() throws Exception {
        long workflowId = createWorkflow(aliceToken, "私有流程", "private");

        // B 的列表不应包含 A 的私有工作流
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + workflowId + ")]").doesNotExist());

        // B 读取 / 修改 / 删除 / 运行 / webhook 均被拒绝
        mvc.perform(get("/api/workflows/{id}", workflowId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/workflows/{id}", workflowId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody("私有流程-篡改", "private")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/workflows/{id}", workflowId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workflows/{id}/run", workflowId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/workflows/{id}/webhook", workflowId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // A（创建者）正常可见
        mvc.perform(get("/api/workflows/{id}", workflowId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(workflowId));
    }

    @Test
    void teamWorkflowVisibleToTenantButNotManageableByOthers() throws Exception {
        long workflowId = createWorkflow(aliceToken, "团队流程", "team");

        // B 可见（同租户）
        mvc.perform(get("/api/workflows").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + workflowId + ")]").exists());
        mvc.perform(get("/api/workflows/{id}", workflowId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        // B 不可管理（非创建者非 admin）
        mvc.perform(put("/api/workflows/{id}", workflowId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody("团队流程-篡改", "team")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/workflows/{id}", workflowId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // A 正常管理
        mvc.perform(delete("/api/workflows/{id}", workflowId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    // ---------- 技能：personal 隔离 ----------

    @Test
    void personalSkillIsIsolatedFromOtherUsers() throws Exception {
        long skillId = createSkill(aliceToken, "personal", "我的私有规范");

        mvc.perform(get("/api/skills").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + skillId + ")]").doesNotExist());

        mvc.perform(get("/api/skills/{id}", skillId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/skills/{id}", skillId)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"篡改\",\"content\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/skills/{id}", skillId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());

        // 创建者可见可管
        mvc.perform(get("/api/skills/{id}", skillId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/skills/{id}", skillId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    @Test
    void teamSkillVisibleToTenant() throws Exception {
        long skillId = createSkill(aliceToken, "team", "团队规范");

        mvc.perform(get("/api/skills/{id}", skillId).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    /** 注册，若用户名已存在（跨测试复用）则直接登录 */
    private String registerOrLogin(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        if (result.getResponse().getStatus() == 200) {
            return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        }
        return login(username, password);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String workflowBody(String name, String scope) {
        return "{\"name\":\"" + name + "\",\"description\":\"越权测试\","
                + "\"stepsJson\":\"[{\\\"tool\\\":\\\"ai_generate\\\",\\\"args\\\":{\\\"prompt\\\":\\\"hi\\\"}}]\","
                + "\"triggerType\":\"manual\",\"scope\":\"" + scope + "\"}";
    }

    private long createWorkflow(String token, String name, String scope) throws Exception {
        MvcResult result = mvc.perform(post("/api/workflows")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(workflowBody(name, scope)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long createSkill(String token, String scope, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/skills")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"" + scope + "\",\"name\":\"" + name + "\",\"description\":\"越权测试\",\"content\":\"规范内容\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
