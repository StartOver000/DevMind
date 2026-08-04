package com.devmind.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;

public class UserHeaderRequestWrapper extends HttpServletRequestWrapper {

    private final Long userId;

    public UserHeaderRequestWrapper(HttpServletRequest request, Long userId) {
        super(request);
        this.userId = userId;
    }

    @Override
    public String getHeader(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return String.valueOf(userId);
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return Collections.enumeration(Collections.singletonList(String.valueOf(userId)));
        }
        return super.getHeaders(name);
    }
}
