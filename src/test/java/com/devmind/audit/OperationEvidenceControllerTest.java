package com.devmind.audit;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** G4：证据包端点——管理员可查，非管理员 403，租户取自操作者。 */
class OperationEvidenceControllerTest {

    private OperationEvidenceService evidenceService;
    private UserService userService;
    private OperationEvidenceController controller;

    @BeforeEach
    void setUp() {
        evidenceService = mock(OperationEvidenceService.class);
        userService = mock(UserService.class);
        controller = new OperationEvidenceController(evidenceService, userService);
    }

    @Test
    void adminGetsEvidencePackageForOwnTenant() {
        OperationEvidenceService.EvidencePackage pkg = new OperationEvidenceService.EvidencePackage(
                100L, true, 2, -1, null, true, false, true, "7", "abc", List.of());
        when(userService.isAdmin(1L)).thenReturn(true);
        when(userService.tenantIdOf(1L)).thenReturn(2L);
        when(evidenceService.evidencePackage(2L, 100L)).thenReturn(pkg);

        OperationEvidenceService.EvidencePackage result = controller.operationEvidence(100L, 1L);

        assertThat(result).isSameAs(pkg);
        verify(evidenceService).evidencePackage(2L, 100L);
    }

    @Test
    void nonAdminIsForbiddenAndNeverReachesEvidence() {
        when(userService.isAdmin(9L)).thenReturn(false);

        assertThatThrownBy(() -> controller.operationEvidence(100L, 9L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.FORBIDDEN);
        verifyNoInteractions(evidenceService);
    }
}
