package com.devmind.user;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.dto.CreateUserRequest;
import com.devmind.user.dto.UserListResponse;
import com.devmind.user.dto.UserResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

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

    public User requireUser(Long userId) {
        return repository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "用户不存在"));
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
