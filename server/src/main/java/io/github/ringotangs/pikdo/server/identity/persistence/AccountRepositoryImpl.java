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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Restores a binding without treating its existence as mailbox verification or authorization. */
public final class AccountRepositoryImpl implements AccountRepository {
    private final AccountMapper mapper;

    public AccountRepositoryImpl(AccountMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void insert(Account account) {
        Objects.requireNonNull(account, "account");
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

    @Override
    public Optional<Account> findById(AccountId id) {
        Objects.requireNonNull(id, "id");
        return mapper.findById(id.value()).map(AccountRepositoryImpl::fromRow);
    }

    @Override
    public Optional<Account> findByIdentifier(LoginIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return mapper.findByIdentifier(
                        identifier.getType().name(), identifier.getCanonicalIdentifier())
                .map(AccountRepositoryImpl::fromRow);
    }

    @Override
    public List<Account> findByUserId(UserId userId) {
        Objects.requireNonNull(userId, "userId");
        return mapper.findByUserId(userId.value()).stream()
                .map(AccountRepositoryImpl::fromRow)
                .toList();
    }

    static Account fromRow(AccountRow row) {
        try {
            LoginIdentifier identifier =
                    switch (IdentityType.valueOf(row.identityType())) {
                        case EMAIL ->
                                new EmailIdentifier(EmailAddress.create(row.originalIdentifier()));
                        case PHONE ->
                                new PhoneIdentifier(PhoneNumber.create(row.originalIdentifier()));
                    };
            if (!identifier.getCanonicalIdentifier().equals(row.canonicalIdentifier())) {
                throw new IllegalStateException("Invalid persisted login identity key");
            }
            return Account.create(new AccountId(row.id()), new UserId(row.userId()), identifier);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted account", failure);
        }
    }
}
