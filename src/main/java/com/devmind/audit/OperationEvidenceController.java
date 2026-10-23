package com.devmind.audit;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作证据 API（G4）：按工作流运行 id 取证据包，含链校验结果与五要素摘要。
 *
 * <p>为什么按 runId 而不是审批 id：一次运行可能先后经过多个审批点，证据链是 run 维度的；
 * 演示时从「审批列表 → 该 run 的证据」跳进来即可。</p>
 */
@RestController
public class OperationEvidenceController {

    private final OperationEvidenceService evidenceService;
    private final UserService userService;

    public OperationEvidenceController(OperationEvidenceService evidenceService, UserService userService) {
        this.evidenceService = evidenceService;
        this.userService = userService;
    }

    /**
     * 管理员：某次运行的操作证据包。
     *
     * @return runId / valid / recordCount / brokenIndex / reason / approved / rejected / executed /
     *         decidedBy / lastRecordHash / records[]
     */
    @GetMapping("/api/audit/operations/{runId}")
    public OperationEvidenceService.EvidencePackage operationEvidence(
            @PathVariable Long runId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        if (!userService.isAdmin(operatorId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看操作证据");
        }
        return evidenceService.evidencePackage(userService.tenantIdOf(operatorId), runId);
    }
}
