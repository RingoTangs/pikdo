package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Optional;

/** Domain persistence contract; transaction ownership remains with the caller. */
public interface PasswordCredentialRepository {
    /** Inserts initial state only; a nonzero authentication version is rejected. */
    void insert(PasswordCredential credential);

    Optional<PasswordCredential> findByUserId(UserId userId);

    /**
     * Atomically replaces password state and increments the version. Returns false when the
     * credential is absent or its version differs; never retries or mutates existing domain
     * objects.
     */
    boolean updateIfVersionMatches(
            UserId userId, long expectedVersion, String passwordHash, String passwordInputVersion);
}
