package com.devmind.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST),
    FILE_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST),
    KNOWLEDGE_BASE_NOT_FOUND(HttpStatus.NOT_FOUND),
    TEAM_NOT_FOUND(HttpStatus.NOT_FOUND),
    DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND),
    SQL_DIAGNOSIS_NOT_FOUND(HttpStatus.NOT_FOUND),
    DOCUMENT_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND),
    WORKFLOW_NOT_FOUND(HttpStatus.NOT_FOUND),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
    WORKFLOW_BUSY(HttpStatus.TOO_MANY_REQUESTS),
    DOCUMENT_PROCESS_FAILED(HttpStatus.UNPROCESSABLE_ENTITY),
    MODEL_CALL_FAILED(HttpStatus.BAD_GATEWAY),
    VECTOR_SEARCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
