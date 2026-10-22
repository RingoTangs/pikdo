package io.github.ringotangs.pikdo.server.identity.application;

public final class AuthenticationFailure extends RuntimeException {
    public enum Reason {
        INVALID,
        RATE_LIMITED,
        UNAVAILABLE,
        ALREADY_AUTHENTICATED
    }

    private final Reason reason;

    public AuthenticationFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
