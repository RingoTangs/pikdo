package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Optional;

/** Domain persistence contract; transaction ownership remains with the caller. */
public interface UserRepository {
    void insert(User user);

    Optional<User> findById(UserId id);

    void updateStatus(User user);
}
