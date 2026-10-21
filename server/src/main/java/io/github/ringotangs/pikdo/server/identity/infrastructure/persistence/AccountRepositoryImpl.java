package io.github.ringotangs.pikdo.server.identity.infrastructure.persistence;

import io.github.ringotangs.pikdo.server.identity.domain.Account;
import io.github.ringotangs.pikdo.server.identity.domain.AccountId;
import io.github.ringotangs.pikdo.server.identity.domain.AccountRepository;
import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;

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
                        account.getEmail().getOriginalAddress(),
                        account.getEmail().getCanonicalAddress())
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
    public Optional<Account> findByEmail(EmailAddress email) {
        Objects.requireNonNull(email, "email");
        return mapper.findByCanonicalAddress(email.getCanonicalAddress())
                .map(AccountRepositoryImpl::fromRow);
    }

    static Account fromRow(AccountRow row) {
        try {
            var email = EmailAddress.create(row.originalAddress());
            if (!email.getCanonicalAddress().equals(row.canonicalAddress())) {
                throw new IllegalStateException("Invalid persisted email identity key");
            }
            return Account.create(new AccountId(row.id()), new UserId(row.userId()), email);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted account", failure);
        }
    }
}
