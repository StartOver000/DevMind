package com.devmind.performance;

import com.devmind.performance.dto.RetrievalBenchmarkRequest;
import com.devmind.performance.dto.RetrievalBenchmarkResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/performance/retrieval")
public class RetrievalBenchmarkController {

    private final RetrievalBenchmarkService service;

    public RetrievalBenchmarkController(RetrievalBenchmarkService service) {
        this.service = service;
    }

    @PostMapping
    public RetrievalBenchmarkResponse benchmark(
            @Valid @RequestBody RetrievalBenchmarkRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.benchmark(request.knowledgeBaseId(), request.question(), request.iterations(), userId);
    }
}
