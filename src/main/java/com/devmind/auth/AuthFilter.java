package com.devmind.auth;

import com.devmind.common.ApiError;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final DevMindProperties properties;
    private final ObjectMapper objectMapper;

    public AuthFilter(AuthService authService, DevMindProperties properties, ObjectMapper objectMapper) {
        this.authService = authService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.authEnabled() || isPublicPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : null;
        try {
            User user = authService.resolveUser(token);
            filterChain.doFilter(new UserHeaderRequestWrapper(request, user.id()), response);
        } catch (Exception ex) {
            response.setStatus(ErrorCode.FORBIDDEN.getStatus().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(new ApiError(
                    ErrorCode.FORBIDDEN.name(),
                    "请先登录",
                    null,
                    OffsetDateTime.now(ZoneOffset.UTC)
            )));
        }
    }

    private boolean isPublicPath(String uri) {
        return uri.startsWith("/api/auth")
                || uri.startsWith("/actuator")
                || !uri.startsWith("/api/")
                || "/".equals(uri);
    }
}
