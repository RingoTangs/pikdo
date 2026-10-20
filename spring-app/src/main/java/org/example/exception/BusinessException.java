package org.example.exception;

import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.util.Objects;

public class BusinessException extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    private final ErrorCode error;
    private final Object[] args;

    private BusinessException(ErrorCode error, Object[] args) {
        super(Objects.requireNonNull(error, "error must not be null").getMessageKey());
        Objects.requireNonNull(error.getCode(), "code must not be null");
        Objects.requireNonNull(error.getStatus(), "status must not be null");
        Objects.requireNonNull(error.getMessageKey(), "messageKey must not be null");
        this.error = error;
        this.args = Objects.requireNonNull(args, "args must not be null").clone();
    }

    public static BusinessException of(ErrorCode error) {
        return new BusinessException(error, new Object[0]);
    }

    public static BusinessException of(ErrorCode error, Object... args) {
        return new BusinessException(error, args);
    }

    public ErrorCode getError() {
        return error;
    }

    public Object[] getArgs() {
        return args.clone();
    }

    public String getCode() {
        return error.getCode();
    }

    public HttpStatus getStatus() {
        return error.getStatus();
    }
}
