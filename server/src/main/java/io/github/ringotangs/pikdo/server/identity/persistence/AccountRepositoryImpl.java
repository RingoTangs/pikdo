package io.github.ringotangs.pikdo.server.identity.persistence;

import io.github.ringotangs.pikdo.server.identity.domain.Account;
import io.github.ringotangs.pikdo.server.identity.domain.AccountId;
import io.github.ringotangs.pikdo.server.identity.domain.AccountRepository;
import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;
import io.github.ringotangs.pikdo.server.identity.domain.EmailIdentifier;
import io.github.ringotangs.pikdo.server.identity.domain.IdentityType;
import io.github.ringotangs.pikdo.server.identity.domain.LoginIdentifier;
import io.github.ringotangs.pikdo.server.identity.domain.PhoneIdentifier;
import io.github.ringotangs.pikdo.server.identity.domain.PhoneNumber;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.domain.Username;
import io.github.ringotangs.pikdo.server.identity.domain.UsernameIdentifier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 通过 AccountMapper 持久化登录身份并重新执行领域校验。不把还原成功视为控制权验证；事务由调用方负责。 */
@Repository
@ConditionalOnProperty(name = "pikdo.auth.enabled", havingValue = "true", matchIfMissing = true)
public class AccountRepositoryImpl implements AccountRepository {
    /** 构造器注入的 Mapper，不在此创建连接或接管事务。 */
    private final AccountMapper mapper;

    /**
     * 使用已有 Mapper 创建持久化实现，不接管事务边界。
     *
     * @param mapper 通过构造器提供的 MyBatis Mapper
     * @throws NullPointerException 必要参数为 null
     */
    public AccountRepositoryImpl(AccountMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** {@inheritDoc} */
    @Override
    public void insert(Account account) {
        Objects.requireNonNull(account, "account");
        // 直接插入，由数据库原子保证每用户每类型一条及规范化身份全局唯一，避免查询后插入的竞态。
        if (mapper.insert(
                        account.getId().value(),
                        account.getUserId().value(),
                        account.getIdentifier().getType().name(),
                        account.getIdentifier().getOriginalIdentifier(),
                        account.getIdentifier().getCanonicalIdentifier())
                != 1) {
            throw new IllegalStateException("Account insert did not affect exactly one row");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Account> findById(AccountId id) {
        Objects.requireNonNull(id, "id");
        return mapper.findById(id.value()).map(AccountRepositoryImpl::fromRow);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Account> findByIdentifier(LoginIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return mapper.findByIdentifier(
                        identifier.getType().name(), identifier.getCanonicalIdentifier())
                .map(AccountRepositoryImpl::fromRow);
    }

    /** {@inheritDoc} */
    @Override
    public List<Account> findByUserId(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        return mapper.findByUserId(userId.value()).stream()
                .map(AccountRepositoryImpl::fromRow)
                .toList();
    }

    /**
     * 通过领域校验还原数据库记录；不信任未知状态、非法字段或不一致的规范化键。
     *
     * @param row 待还原的数据库记录
     * @return 还原的领域对象
     * @throws IllegalStateException 记录无法还原为合法领域对象
     */
    static Account fromRow(AccountRow row) {
        // 重新经过领域校验，拒绝未知类型或损坏数据，不把数据库记录直接当作可信领域对象。
        try {
            LoginIdentifier identifier =
                    switch (IdentityType.valueOf(row.identityType())) {
                        case EMAIL ->
                                new EmailIdentifier(EmailAddress.create(row.originalIdentifier()));
                        case PHONE ->
                                new PhoneIdentifier(PhoneNumber.create(row.originalIdentifier()));
                        case USERNAME ->
                                new UsernameIdentifier(Username.create(row.originalIdentifier()));
                    };
            // 从原始值重新派生身份键，防止原始表示与查询键指向不同身份。
            if (!identifier.getCanonicalIdentifier().equals(row.canonicalIdentifier())) {
                throw new IllegalStateException("Invalid persisted login identity key");
            }
            return Account.create(new AccountId(row.id()), new UserId(row.userId()), identifier);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted account", failure);
        }
    }
}
