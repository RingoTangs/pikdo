package io.github.ringotangs.pikdo.server.identity.web;

import io.github.ringotangs.pikdo.server.identity.application.AuthenticationFailure;
import io.github.ringotangs.pikdo.server.shared.web.error.*;

import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@Order(-1)
@RestControllerAdvice(assignableTypes = EmailAuthenticationController.class)
public final class AuthenticationExceptionHandler {
    private final ErrorMessageResolver messages;

    public AuthenticationExceptionHandler(ErrorMessageResolver messages) {
        this.messages = messages;
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorResponse> authentication(
            AuthenticationFailure failure, Locale locale) {
        return response(AuthenticationError.fromReason(failure.reason()), locale);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> unavailable(DataAccessException failure, Locale locale) {
        return response(AuthenticationError.UNAVAILABLE, locale);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException failure, Locale locale) {
        return response(RequestError.VALIDATION_FAILED, locale);
    }

    private ResponseEntity<ErrorResponse> response(ErrorCode error, Locale locale) {
        return ResponseEntity.status(error.getStatus())
                .header("Cache-Control", "no-store")
                .body(messages.resolve(error, locale));
    }
}
