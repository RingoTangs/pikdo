package io.github.ringotangs.pikdo.server.identity.persistence;

import io.github.ringotangs.pikdo.server.identity.domain.PasswordCredential;
import io.github.ringotangs.pikdo.server.identity.domain.PasswordCredentialRepository;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;

import java.util.Objects;
import java.util.Optional;

/** Constructor-wired persistence boundary; transaction ownership remains with the caller. */
public final class PasswordCredentialRepositoryImpl implements PasswordCredentialRepository {
    private final PasswordCredentialMapper mapper;

    public PasswordCredentialRepositoryImpl(PasswordCredentialMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void insert(PasswordCredential credential) {
        Objects.requireNonNull(credential, "credential");
        // 现有 SQL 使用数据库默认版本 0，不允许静默丢弃还原对象的非零版本。
        if (credential.getAuthenticationVersion() != 0) {
            throw new IllegalArgumentException("Only initial password credentials can be inserted");
        }
        if (mapper.insert(
                        credential.getUserId().value(),
                        credential.getPasswordHash(),
                        credential.getPasswordInputVersion())
                != 1) {
            throw new IllegalStateException(
                    "Password credential insert did not affect exactly one row");
        }
    }

    @Override
    public Optional<PasswordCredential> findByUserId(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        return mapper.findByUserId(userId.value()).map(PasswordCredentialRepositoryImpl::fromRow);
    }

    @Override
    public boolean updateIfVersionMatches(
            UserId userId, long expectedVersion, String passwordHash, String passwordInputVersion) {
        // 复用领域校验，但版本递增仍由数据库在条件更新中原子完成。
        var replacement =
                PasswordCredential.restore(
                        userId, passwordHash, expectedVersion, passwordInputVersion);
        int affected =
                mapper.updateIfVersionMatches(
                        replacement.getUserId().value(),
                        replacement.getAuthenticationVersion(),
                        replacement.getPasswordHash(),
                        replacement.getPasswordInputVersion());
        if (affected == 0) {
            return false;
        }
        if (affected != 1) {
            throw new IllegalStateException(
                    "Password credential update affected an unexpected row count");
        }
        return true;
    }

    static PasswordCredential fromRow(PasswordCredentialRow row) {
        try {
            return PasswordCredential.restore(
                    new UserId(row.userId()),
                    row.passwordHash(),
                    row.authenticationVersion(),
                    row.passwordInputVersion());
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted password credential", failure);
        }
    }
}
