package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.List;
import java.util.Optional;

public interface AccountRepository {
    void insert(Account account);

    Optional<Account> findById(AccountId id);

    Optional<Account> findByIdentifier(LoginIdentifier identifier);

    List<Account> findByUserId(UserId userId);
}
