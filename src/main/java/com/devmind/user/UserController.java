package com.devmind.user;

import com.devmind.user.dto.CreateUserRequest;
import com.devmind.user.dto.UserListResponse;
import com.devmind.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @GetMapping
    public UserListResponse list() {
        return userService.list();
    }

    /** 当前用户信息（含角色，供前端控制界面权限） */
    @GetMapping("/me")
    public Map<String, Object> me(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return userService.me(userId);
    }
}
