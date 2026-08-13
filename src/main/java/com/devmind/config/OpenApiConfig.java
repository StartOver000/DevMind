package com.devmind.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 平台自身 OpenAPI 文档信息（springdoc /v3/api-docs + /swagger-ui.html）。
 * 作为"通用 AI 接入层"平台，自己的 API 文档即自我证明：接入 OpenAPI 的能力先在自己身上生效。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI devMindOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("DevMind API")
                .version("0.1.0")
                .description("""
                        通用 AI 接入层平台：上传内部文档、登记内部接口 / 接入 MCP，用自然语言驱动 AI Agent 检索知识、
                        诊断 SQL、执行工作流、沉淀技能、编排业务流。

                        接口能力闭环：OpenAPI 导入 → 语义化(pgvector) → 按需注入 Agent/工作流 → 缺失能力反推 → 沉淀为技能复用。
                        """)
                .contact(new Contact().name("DevMind")));
    }
}
