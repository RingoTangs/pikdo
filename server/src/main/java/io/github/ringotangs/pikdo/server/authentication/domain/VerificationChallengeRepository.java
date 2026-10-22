package io.github.ringotangs.pikdo.server.authentication.domain;

import io.github.ringotangs.pikdo.server.identity.domain.UserId;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface VerificationChallengeRepository {
    void insert(VerificationChallenge challenge);

    Optional<VerificationChallenge> lockById(UUID id);

    void invalidateFlow(VerificationChallenge replacement, OffsetDateTime now);

    void failedAttempt(VerificationChallenge challenge, OffsetDateTime now);

    void consume(VerificationChallenge challenge);

    void invalidate(UUID id, OffsetDateTime now);

    boolean lockUser(UserId id);
}
