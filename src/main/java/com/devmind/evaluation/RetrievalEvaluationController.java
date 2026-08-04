package com.devmind.evaluation;

import com.devmind.evaluation.dto.EvaluationRequest;
import com.devmind.evaluation.dto.RetrievalEvaluationResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluations/retrieval")
public class RetrievalEvaluationController {

    private final RetrievalEvaluationService service;

    public RetrievalEvaluationController(RetrievalEvaluationService service) {
        this.service = service;
    }

    @PostMapping
    public RetrievalEvaluationResponse evaluate(
            @Valid @RequestBody EvaluationRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.evaluate(request, userId);
    }
}
