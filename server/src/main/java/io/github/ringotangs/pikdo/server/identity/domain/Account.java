package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/**
 * Immutable information for one login identity binding instance, identified independently of the
 * user.
 */
public final class Account {
    private final AccountId id;
    private final UserId userId;
    private final LoginIdentifier identifier;

    private Account(AccountId id, UserId userId, LoginIdentifier identifier) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
    }

    /**
     * Constructs a domain object only; it does not verify user existence, identity ownership,
     * uniqueness, authorization, or the binding's committed and currently effective status.
     */
    public static Account create(AccountId id, UserId userId, LoginIdentifier identifier) {
        return new Account(id, userId, identifier);
    }

    public AccountId getId() {
        return id;
    }

    public UserId getUserId() {
        return userId;
    }

    public LoginIdentifier getIdentifier() {
        return identifier;
    }
}
