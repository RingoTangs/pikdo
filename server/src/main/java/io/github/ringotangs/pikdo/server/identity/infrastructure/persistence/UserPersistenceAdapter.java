package io.github.ringotangs.pikdo.server.identity.infrastructure.persistence;

import io.github.ringotangs.pikdo.server.identity.domain.User;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.domain.UserStatus;

import java.util.Objects;
import java.util.Optional;

/** Constructor-wired persistence boundary; transaction ownership remains with the caller. */
public final class UserPersistenceAdapter {
    private final UserMapper mapper;

    public UserPersistenceAdapter(UserMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public void insert(User user) {
        Objects.requireNonNull(user, "user");
        if (mapper.insert(user.getId().value(), user.getStatus().name()) != 1) {
            throw new IllegalStateException("User insert did not affect exactly one row");
        }
    }

    public Optional<User> findById(UserId id) {
        Objects.requireNonNull(id, "id");
        return mapper.findById(id.value()).map(UserPersistenceAdapter::fromRow);
    }

    public void updateStatus(User user) {
        Objects.requireNonNull(user, "user");
        if (mapper.updateStatus(user.getId().value(), user.getStatus().name()) != 1) {
            throw new IllegalStateException("User status update did not affect exactly one row");
        }
    }

    static User fromRow(UserRow row) {
        try {
            var user = User.create(new UserId(row.id()));
            if (UserStatus.valueOf(row.status()) == UserStatus.DISABLED) {
                // Existing lifecycle methods have no event or timestamp side effects.
                user.disable();
            }
            return user;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted user", failure);
        }
    }
}
