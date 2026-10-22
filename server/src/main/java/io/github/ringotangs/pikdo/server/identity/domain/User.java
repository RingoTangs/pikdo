package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

public final class User {
    private final UserId id;

    private UserStatus status;

    private User(UserId id) {
        this.id = Objects.requireNonNull(id, "id");
        this.status = UserStatus.ACTIVE;
    }

    public static User create(UserId id) {
        return new User(id);
    }

    public UserId getId() {
        return id;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void disable() {
        status = UserStatus.DISABLED;
    }

    public void restore() {
        status = UserStatus.ACTIVE;
    }
}
