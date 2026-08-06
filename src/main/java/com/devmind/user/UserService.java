package com.devmind.user;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.dto.CreateUserRequest;
import com.devmind.user.dto.UserListResponse;
import com.devmind.user.dto.UserResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public UserResponse create(CreateUserRequest request) {
        String username = request.username().trim();
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        try {
            Long id = repository.create(username, displayName);
            return toResponse(requireUser(id));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "用户名已存在");
        }
    }

    public UserListResponse list() {
        List<UserResponse> items = repository.list().stream().map(this::toResponse).toList();
        return new UserListResponse(items);
    }

    /** 当前登录用户信息（含角色/租户，供前端判断权限） */
    public Map<String, Object> me(Long userId) {
        User user = requireUser(userId);
        return Map.of(
                "id", user.id(),
                "username", user.username(),
                "displayName", user.displayName() == null ? "" : user.displayName(),
                "role", user.role(),
                "tenantId", user.tenantId()
        );
    }

    public User requireUser(Long userId) {
        return repository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
    }

    /** 用户所属租户（多租户隔离） */
    public Long tenantIdOf(Long userId) {
        return requireUser(userId).tenantId();
    }

    public boolean isAdmin(Long userId) {
        return repository.findById(userId).map(u -> "ADMIN".equals(u.role())).orElse(false);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.id(),
                user.username(),
                user.displayName(),
                user.createdTime()
        );
    }
}
