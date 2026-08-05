package com.devmind.evaluation;

import com.devmind.evaluation.dto.AgentEvaluationResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-evaluations")
public class AgentEvaluationController {

    private final AgentEvaluationService service;

    public AgentEvaluationController(AgentEvaluationService service) {
        this.service = service;
    }

    @PostMapping
    public AgentEvaluationResponse evaluate(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.evaluate(userId);
    }
}
