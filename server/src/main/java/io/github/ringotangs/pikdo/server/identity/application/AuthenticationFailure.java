package io.github.ringotangs.pikdo.server.identity.application;

/** 不携带邮箱、验证码、摘要或底层异常文本的应用失败。 */
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
