package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Optional;

public interface UserRepository {
    void insert(User user);

    Optional<User> findById(UserId id);

    void updateStatus(User user);
}
