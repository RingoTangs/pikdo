package io.github.ringotangs.pikdo.server.authentication.persistence;

import io.github.ringotangs.pikdo.server.authentication.domain.CodePurpose;
import io.github.ringotangs.pikdo.server.authentication.domain.VerificationChallenge;
import io.github.ringotangs.pikdo.server.authentication.domain.VerificationChallengeRepository;
import io.github.ringotangs.pikdo.server.identity.domain.*;

import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VerificationChallengeRepositoryImpl implements VerificationChallengeRepository {
    private final EmailChallengeMapper mapper;

    public VerificationChallengeRepositoryImpl(EmailChallengeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(VerificationChallenge challenge) {
        if (challenge.attempts() != 0
                || challenge.consumedAt() != null
                || challenge.invalidatedAt() != null)
            throw new IllegalArgumentException("Only new challenges can be inserted");
        if (mapper.insert(toRow(challenge)) != 1)
            throw new IllegalStateException("Challenge insertion failed");
    }

    @Override
    public Optional<VerificationChallenge> lockById(UUID id) {
        return mapper.lockById(id).map(VerificationChallengeRepositoryImpl::fromRow);
    }

    @Override
    public void invalidateFlow(VerificationChallenge replacement, OffsetDateTime now) {
        mapper.invalidateFlow(
                replacement.flowDigest(),
                replacement.canonicalEmail(),
                replacement.purpose().name(),
                now);
    }

    @Override
    public void failedAttempt(VerificationChallenge challenge, OffsetDateTime now) {
        if (mapper.failedAttempt(challenge.id(), now) != 1)
            throw new IllegalStateException("Challenge attempt update failed");
    }

    @Override
    public void consume(VerificationChallenge challenge) {
        if (challenge.consumedAt() == null
                || mapper.consume(challenge.id(), challenge.consumedAt()) != 1)
            throw new IllegalStateException("Challenge consumption failed");
    }

    @Override
    public void invalidate(UUID id, OffsetDateTime now) {
        lockById(id)
                .filter(challenge -> challenge.invalidate(now))
                .ifPresent(challenge -> mapper.invalidate(id, now));
    }

    @Override
    public boolean lockUser(UserId id) {
        return mapper.lockUser(id.value()).isPresent();
    }

    static VerificationChallenge fromRow(EmailChallengeRow row) {
        try {
            var email = EmailAddress.create(row.originalEmail());
            if (!email.getCanonicalAddress().equals(row.canonicalEmail()))
                throw new IllegalArgumentException("Inconsistent canonical email");
            return VerificationChallenge.restore(
                    row.id(),
                    CodePurpose.valueOf(row.purpose()),
                    email,
                    row.flowDigest(),
                    row.codeDigest(),
                    row.eligible(),
                    row.attempts(),
                    row.maxAttempts(),
                    row.expiresAt(),
                    row.consumedAt(),
                    row.invalidatedAt(),
                    row.accountId(),
                    row.userId());
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted verification challenge", failure);
        }
    }

    static EmailChallengeRow toRow(VerificationChallenge challenge) {
        return new EmailChallengeRow(
                challenge.id(),
                challenge.purpose().name(),
                challenge.canonicalEmail(),
                challenge.originalEmail(),
                challenge.flowDigest(),
                challenge.codeDigest(),
                challenge.eligible(),
                challenge.attempts(),
                challenge.maxAttempts(),
                challenge.expiresAt(),
                challenge.consumedAt(),
                challenge.invalidatedAt(),
                challenge.accountId(),
                challenge.userId());
    }
}
