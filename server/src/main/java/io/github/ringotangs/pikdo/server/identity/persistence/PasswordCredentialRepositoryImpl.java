package io.github.ringotangs.pikdo.server.identity.persistence;

import io.github.ringotangs.pikdo.server.identity.domain.PasswordCredential;
import io.github.ringotangs.pikdo.server.identity.domain.PasswordCredentialRepository;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;

import java.util.Objects;
import java.util.Optional;

/** 复用现有密码 Mapper 的领域持久化实现。保持数据库版本比较和递增的原子性，不自动重试，事务由调用方负责。 */
public final class PasswordCredentialRepositoryImpl implements PasswordCredentialRepository {
    /** 构造器注入的 Mapper，不在此创建连接或接管事务。 */
    private final PasswordCredentialMapper mapper;

    /**
     * 使用已有 Mapper 创建持久化实现，不接管事务边界。
     *
     * @param mapper 通过构造器提供的 MyBatis Mapper
     * @throws NullPointerException 必要参数为 null
     */
    public PasswordCredentialRepositoryImpl(PasswordCredentialMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public Optional<PasswordCredential> findByUserId(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        return mapper.findByUserId(userId.value()).map(PasswordCredentialRepositoryImpl::fromRow);
    }

    /** {@inheritDoc} */
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

    /**
     * 通过领域校验还原数据库记录；不信任未知状态、非法字段或不一致的规范化键。
     *
     * @param row 待还原的数据库记录
     * @return 还原的领域对象
     * @throws IllegalStateException 记录无法还原为合法领域对象
     */
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
