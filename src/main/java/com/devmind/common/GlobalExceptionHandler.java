package com.devmind.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity
                .status(ex.getCode().getStatus())
                .body(new ApiError(ex.getCode().name(), ex.getMessage(), traceId(request), now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("请求参数错误");
        return badRequest(message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return badRequest("请求体格式错误", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return ResponseEntity
                .status(ErrorCode.FILE_TOO_LARGE.getStatus())
                .body(new ApiError(ErrorCode.FILE_TOO_LARGE.name(), "文件超过大小限制", traceId(request), now()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(DataAccessException ex, HttpServletRequest request) {
        log.error("database error", ex);
        return internalError("数据库访问失败", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("unhandled error", ex);
        return internalError("系统内部错误", request);
    }

    private ResponseEntity<ApiError> badRequest(String message, HttpServletRequest request) {
        return ResponseEntity
                .status(ErrorCode.INVALID_ARGUMENT.getStatus())
                .body(new ApiError(ErrorCode.INVALID_ARGUMENT.name(), message, traceId(request), now()));
    }

    private ResponseEntity<ApiError> internalError(String message, HttpServletRequest request) {
        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(new ApiError(ErrorCode.INTERNAL_ERROR.name(), message, traceId(request), now()));
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute("traceId");
        return traceId == null ? null : traceId.toString();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
