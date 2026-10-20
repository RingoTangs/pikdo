package org.example.exception;

import org.springframework.http.HttpStatus;

// Common errors are available for projects created from this template.
@SuppressWarnings("unused")
public enum CommonError implements ErrorCode {
    BAD_REQUEST("COMMON_BAD_REQUEST", HttpStatus.BAD_REQUEST, "error.common.bad_request"),
    UNAUTHORIZED("COMMON_UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "error.common.unauthorized"),
    FORBIDDEN("COMMON_FORBIDDEN", HttpStatus.FORBIDDEN, "error.common.forbidden"),
    NOT_FOUND("COMMON_NOT_FOUND", HttpStatus.NOT_FOUND, "error.common.not_found"),
    CONFLICT("COMMON_CONFLICT", HttpStatus.CONFLICT, "error.common.conflict"),
    INTERNAL_SERVER_ERROR(
            "COMMON_INTERNAL_SERVER_ERROR",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "error.common.internal_server_error");

    private final String code;
    private final HttpStatus status;
    private final String messageKey;

    CommonError(String code, HttpStatus status, String messageKey) {
        this.code = code;
        this.status = status;
        this.messageKey = messageKey;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public HttpStatus getStatus() {
        return status;
    }

    @Override
    public String getMessageKey() {
        return messageKey;
    }
}
