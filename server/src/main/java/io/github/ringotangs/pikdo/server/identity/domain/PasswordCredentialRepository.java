package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Optional;

public interface PasswordCredentialRepository {
    void insert(PasswordCredential credential);

    Optional<PasswordCredential> findByUserId(UserId userId);

    boolean updateIfVersionMatches(
            UserId userId, long expectedVersion, String passwordHash, String passwordInputVersion);
}
