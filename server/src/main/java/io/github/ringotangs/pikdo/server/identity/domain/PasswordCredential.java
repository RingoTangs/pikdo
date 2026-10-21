package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/** Immutable user-owned password state, independent of login identities and hash algorithms. */
public final class PasswordCredential {
    private final UserId userId;
    private final String passwordHash;
    private final long authenticationVersion;
    private final String passwordInputVersion;

    private PasswordCredential(
            UserId userId,
            String passwordHash,
            long authenticationVersion,
            String passwordInputVersion) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.passwordInputVersion =
                Objects.requireNonNull(passwordInputVersion, "passwordInputVersion");
        if (passwordHash.isBlank() || passwordInputVersion.isBlank()) {
            throw new IllegalArgumentException("Password credential strings must not be blank");
        }
        if (authenticationVersion < 0) {
            throw new IllegalArgumentException("Authentication version must not be negative");
        }
        this.authenticationVersion = authenticationVersion;
    }

    /** Creates initial state; the database also initializes the authentication version to zero. */
    public static PasswordCredential create(
            UserId userId, String passwordHash, String passwordInputVersion) {
        return new PasswordCredential(userId, passwordHash, 0, passwordInputVersion);
    }

    public static PasswordCredential restore(
            UserId userId,
            String passwordHash,
            long authenticationVersion,
            String passwordInputVersion) {
        return new PasswordCredential(
                userId, passwordHash, authenticationVersion, passwordInputVersion);
    }

    public UserId getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public long getAuthenticationVersion() {
        return authenticationVersion;
    }

    public String getPasswordInputVersion() {
        return passwordInputVersion;
    }

    @Override
    public String toString() {
        // 避免日志或调试输出暴露密码哈希，不使用包含字段值的默认表示。
        return "PasswordCredential[redacted]";
    }
}
