package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/** Immutable information for one email binding instance, identified independently of the user. */
public final class Account {
    private final AccountId id;
    private final UserId userId;
    private final EmailAddress email;

    private Account(AccountId id, UserId userId, EmailAddress email) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.email = Objects.requireNonNull(email, "email");
    }

    /**
     * Constructs a domain object only; it does not verify user existence, mailbox ownership,
     * uniqueness, authorization, or the binding's committed and currently effective status.
     */
    public static Account create(AccountId id, UserId userId, EmailAddress email) {
        return new Account(id, userId, email);
    }

    public AccountId getId() {
        return id;
    }

    public UserId getUserId() {
        return userId;
    }

    public EmailAddress getEmail() {
        return email;
    }
}
