package com.devmind.auth;

import com.devmind.auth.dto.LoginResponse;
import com.devmind.auth.dto.MeResponse;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindSecurityProperties;
import com.devmind.security.LoginAttemptService;
import com.devmind.user.User;
import com.devmind.user.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final DevMindSecurityProperties security;

    public AuthService(
            UserRepository userRepository,
            AuthTokenRepository tokenRepository,
            PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService,
            DevMindSecurityProperties security
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.security = security;
    }

    public LoginResponse register(String username, String password, String displayName) {
        try {
            Long userId = userRepository.createWithPassword(
                    username.trim(),
                    displayName == null ? null : displayName.trim(),
                    passwordEncoder.encode(password)
            );
            return login(username.trim(), password);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "用户名已存在");
        }
    }

    public LoginResponse login(String username, String password) {
        String name = username.trim();
        if (loginAttemptService.isLocked(name)) {
            long seconds = loginAttemptService.remainingLockSeconds(name);
            throw new ApiException(
                    ErrorCode.ACCOUNT_LOCKED,
                    "登录失败次数过多，请 " + seconds + " 秒后重试"
            );
        }
        User user = userRepository.findByUsername(name)
                .orElseThrow(() -> {
                    loginAttemptService.recordFailure(name);
                    return new ApiException(ErrorCode.INVALID_ARGUMENT, "用户名或密码错误");
                });
        String hash = userRepository.findPasswordHash(user.id());
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            loginAttemptService.recordFailure(name);
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "用户名或密码错误");
        }
        loginAttemptService.reset(name);
        String token = UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(security.tokenTtlDays());
        tokenRepository.save(user.id(), token, expiresAt);
        tokenRepository.deleteExpired(OffsetDateTime.now(ZoneOffset.UTC));
        return new LoginResponse(user.id(), user.username(), user.displayName(), token);
    }

    public MeResponse me(String token) {
        User user = resolveUser(token);
        return new MeResponse(user.id(), user.username(), user.displayName());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            tokenRepository.deleteByToken(token);
        }
    }

    public User resolveUser(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "未登录");
        }
        AuthToken authToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "登录已失效"));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (authToken.expiresAt().isBefore(now)) {
            tokenRepository.deleteByToken(token);
            throw new ApiException(ErrorCode.FORBIDDEN, "登录已过期");
        }
        // 滑动续期：剩余有效期低于阈值时自动延长，用户无需重新登录
        long remaining = Duration.between(now, authToken.expiresAt()).toMinutes();
        long threshold = Duration.ofDays(security.tokenRefreshThresholdDays()).toMinutes();
        if (remaining < threshold) {
            tokenRepository.extendExpiry(token, now.plusDays(security.tokenTtlDays()));
        }
        return userRepository.findById(authToken.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
    }
}
