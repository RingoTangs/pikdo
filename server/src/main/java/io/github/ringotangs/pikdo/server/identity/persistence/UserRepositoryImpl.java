package io.github.ringotangs.pikdo.server.identity.persistence;

import io.github.ringotangs.pikdo.server.identity.domain.User;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.domain.UserRepository;
import io.github.ringotangs.pikdo.server.identity.domain.UserStatus;

import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

@Repository
public class UserRepositoryImpl implements UserRepository {
    private final UserMapper mapper;

    public UserRepositoryImpl(UserMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void insert(User user) {
        Objects.requireNonNull(user, "user");
        if (mapper.insert(user.getId().value(), user.getStatus().name()) != 1) {
            throw new IllegalStateException("User insert did not affect exactly one row");
        }
    }

    @Override
    public Optional<User> findById(UserId id) {
        Objects.requireNonNull(id, "id");
        return mapper.findById(id.value()).map(UserRepositoryImpl::fromRow);
    }

    @Override
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
                user.disable();
            }
            return user;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted user", failure);
        }
    }
}
