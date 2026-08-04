package com.devmind.security;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.security.dto.EncryptRequest;
import com.devmind.security.dto.EncryptResponse;
import com.devmind.user.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员专用：生成敏感配置的加密值（enc: 前缀），
 * 便于把 API Key 以非明文形式放入配置。
 */
@RestController
@RequestMapping("/api/admin/secrets")
public class SecretAdminController {

    private final SecretCipher secretCipher;
    private final UserService userService;

    public SecretAdminController(SecretCipher secretCipher, UserService userService) {
        this.secretCipher = secretCipher;
        this.userService = userService;
    }

    @PostMapping("/encrypt")
    public EncryptResponse encrypt(
            @Valid @RequestBody EncryptRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        userService.requireUser(userId);
        if (!userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可以执行该操作");
        }
        return new EncryptResponse(secretCipher.encrypt(request.value()));
    }
}
