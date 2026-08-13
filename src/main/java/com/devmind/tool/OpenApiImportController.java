package com.devmind.tool;

import com.devmind.tool.OpenApiImportService.EnhanceResult;
import com.devmind.tool.OpenApiImportService.ImportResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * OpenAPI 导入与接口语义检索 API（P1 接口语义化）。
 * 演示闭环：上传 OpenAPI 3.0 → 批量登记接口工具 + 语义向量化 → 自然语言检索命中接口。
 */
@RestController
@RequestMapping("/api/tools")
public class OpenApiImportController {

    private final OpenApiImportService importService;

    public OpenApiImportController(OpenApiImportService importService) {
        this.importService = importService;
    }

    /** 上传 OpenAPI 3.0 文档（JSON/YAML）批量登记接口工具并生成语义档案（仅管理员） */
    @PostMapping("/import")
    public ImportResult importOpenApi(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return importService.importOpenApi(file, userId);
    }

    /** 语义检索：自然语言查询命中对应接口（关键词 + 向量双通道，自动降级） */
    @GetMapping("/search")
    public List<ToolSemanticRepository.SemanticHit> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "8") int limit,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return importService.semanticSearch(query, userId, limit);
    }

    /** LLM 语义增强：为单个接口生成业务用途/调用场景并重新向量化（仅管理员） */
    @PostMapping("/{id}/semantic")
    public EnhanceResult enhance(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return importService.enhanceSemantic(id, userId);
    }
}
