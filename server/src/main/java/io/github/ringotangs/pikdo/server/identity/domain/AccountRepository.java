package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Optional;

/** Domain persistence contract; transaction ownership remains with the caller. */
public interface AccountRepository {
    void insert(Account account);

    Optional<Account> findById(AccountId id);

    Optional<Account> findByEmail(EmailAddress email);
}
