package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.List;
import java.util.Optional;

/** Domain persistence contract; transaction ownership remains with the caller. */
public interface AccountRepository {
    void insert(Account account);

    Optional<Account> findById(AccountId id);

    Optional<Account> findByIdentifier(LoginIdentifier identifier);

    /** Returns EMAIL, PHONE, then USERNAME, or an empty list when no bindings exist. */
    List<Account> findByUserId(UserId userId);
}
