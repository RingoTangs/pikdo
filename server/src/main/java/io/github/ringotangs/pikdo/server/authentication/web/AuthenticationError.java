package io.github.ringotangs.pikdo.server.authentication.web;

import io.github.ringotangs.pikdo.server.authentication.application.AuthenticationFailure;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorCode;

import org.springframework.http.HttpStatus;

public enum AuthenticationError implements ErrorCode {
    VERIFICATION_FAILED(
            "IDENTITY_VERIFICATION_FAILED",
            HttpStatus.UNAUTHORIZED,
            "error.identity.verification_failed"),
    ALREADY_AUTHENTICATED(
            "IDENTITY_ALREADY_AUTHENTICATED",
            HttpStatus.CONFLICT,
            "error.identity.already_authenticated"),
    RATE_LIMITED(
            "IDENTITY_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "error.identity.rate_limited"),
    UNAVAILABLE(
            "IDENTITY_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "error.identity.unavailable");
    private final String code;
    private final HttpStatus status;
    private final String key;

    AuthenticationError(String code, HttpStatus status, String key) {
        this.code = code;
        this.status = status;
        this.key = key;
    }

    public static AuthenticationError fromReason(AuthenticationFailure.Reason reason) {
        return switch (reason) {
            case INVALID -> VERIFICATION_FAILED;
            case RATE_LIMITED -> RATE_LIMITED;
            case UNAVAILABLE -> UNAVAILABLE;
            case ALREADY_AUTHENTICATED -> ALREADY_AUTHENTICATED;
        };
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessageKey() {
        return key;
    }
}
