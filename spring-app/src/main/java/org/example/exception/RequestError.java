package org.example.exception;

import org.springframework.http.HttpStatus;

public enum RequestError implements ErrorCode {
    VALIDATION_FAILED("REQUEST_VALIDATION_FAILED", "error.request.validation_failed"),
    MISSING_PARAMETER("REQUEST_MISSING_PARAMETER", "error.request.missing_parameter"),
    MISSING_HEADER("REQUEST_MISSING_HEADER", "error.request.missing_header"),
    INVALID_PARAMETER_TYPE(
            "REQUEST_INVALID_PARAMETER_TYPE", "error.request.invalid_parameter_type"),
    INVALID_REQUEST_BODY("REQUEST_INVALID_REQUEST_BODY", "error.request.invalid_request_body");

    private final String code;
    private final HttpStatus status = HttpStatus.BAD_REQUEST;
    private final String messageKey;

    RequestError(String code, String messageKey) {
        this.code = code;
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
