package com.devmind.tool;

import com.devmind.tool.dto.ToolCreateRequest;
import com.devmind.tool.dto.ToolResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 接口工具管理 API：登记内部接口 → 动态生成 Agent 工具（M1 核心能力） */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final InterfaceToolService toolService;

    public ToolController(InterfaceToolService toolService) {
        this.toolService = toolService;
    }

    @PostMapping
    public ToolResponse create(
            @Valid @RequestBody ToolCreateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return toolService.create(request, userId);
    }

    @GetMapping
    public List<ToolResponse> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return toolService.list(userId);
    }

    @GetMapping("/{id}")
    public ToolResponse get(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return toolService.get(id, userId);
    }

    @PutMapping("/{id}")
    public ToolResponse update(
            @PathVariable Long id,
            @Valid @RequestBody ToolCreateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return toolService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        toolService.delete(id, userId);
        return Map.of("deleted", true);
    }

    /** 连通性测试：按定义实际调用一次，返回是否可连通 */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        boolean ok = toolService.test(id, userId);
        return Map.of("ok", ok, "message", ok ? "接口可连通" : "接口调用失败，请检查地址/鉴权/网络");
    }
}
