package com.devmind.knowledge;

import com.devmind.knowledge.dto.DemoKnowledgeBaseResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一键示例知识库：POST /api/knowledge-bases/demo
 * 冷启动体验（产品运营盲区修复）——新用户一键获得可问的示例知识库。
 */
@RestController
public class DemoKnowledgeBaseController {

    private final DemoKnowledgeBaseService demoService;

    public DemoKnowledgeBaseController(DemoKnowledgeBaseService demoService) {
        this.demoService = demoService;
    }

    @PostMapping("/api/knowledge-bases/demo")
    public DemoKnowledgeBaseResponse createDemo(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return demoService.createDemo(userId);
    }
}
