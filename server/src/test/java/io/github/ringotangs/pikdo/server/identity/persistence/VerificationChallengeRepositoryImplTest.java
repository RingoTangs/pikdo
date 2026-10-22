package io.github.ringotangs.pikdo.server.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.ringotangs.pikdo.server.identity.domain.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

class VerificationChallengeRepositoryImplTest {
    final EmailChallengeMapper mapper = mock(EmailChallengeMapper.class);
    final VerificationChallengeRepositoryImpl repository =
            new VerificationChallengeRepositoryImpl(mapper);
    final OffsetDateTime now = OffsetDateTime.parse("2026-10-22T00:00:00Z");

    EmailChallengeRow row() {
        return new EmailChallengeRow(
                UUID.randomUUID(),
                "LOGIN",
                "original@example.com",
                "Original@Example.com",
                new byte[32],
                new byte[32],
                true,
                2,
                5,
                now.plusMinutes(5),
                null,
                null,
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    @Test
    void rowRoundTripPreservesEveryFieldAndCopiesDigestArrays() {
        var row = row();
        var challenge = VerificationChallengeRepositoryImpl.fromRow(row);
        var restored = VerificationChallengeRepositoryImpl.toRow(challenge);
        assertEquals(row.id(), restored.id());
        assertEquals(row.purpose(), restored.purpose());
        assertEquals(row.originalEmail(), restored.originalEmail());
        assertEquals(row.canonicalEmail(), restored.canonicalEmail());
        assertArrayEquals(row.flowDigest(), restored.flowDigest());
        assertArrayEquals(row.codeDigest(), restored.codeDigest());
        assertEquals(row.eligible(), restored.eligible());
        assertEquals(row.attempts(), restored.attempts());
        assertEquals(row.maxAttempts(), restored.maxAttempts());
        assertEquals(row.expiresAt(), restored.expiresAt());
        assertEquals(row.consumedAt(), restored.consumedAt());
        assertEquals(row.invalidatedAt(), restored.invalidatedAt());
        assertEquals(row.accountId(), restored.accountId());
        assertEquals(row.userId(), restored.userId());
        row.flowDigest()[0] = 1;
        restored.codeDigest()[0] = 1;
        assertEquals(0, challenge.flowDigest()[0]);
        assertEquals(0, challenge.codeDigest()[0]);
    }

    @Test
    void terminalTimestampsSurviveMapping() {
        var row = row();
        var challenge = VerificationChallengeRepositoryImpl.fromRow(row);
        challenge.invalidate(now);
        var invalidated =
                VerificationChallengeRepositoryImpl.fromRow(
                        VerificationChallengeRepositoryImpl.toRow(challenge));
        assertEquals(now, invalidated.invalidatedAt());
        assertFalse(invalidated.canDeliver(now));
        challenge = VerificationChallengeRepositoryImpl.fromRow(row);
        challenge.verify(
                CodePurpose.LOGIN,
                EmailAddress.create(row.originalEmail()),
                row.flowDigest(),
                row.codeDigest(),
                now);
        assertTrue(challenge.consume(now));
        var consumed =
                VerificationChallengeRepositoryImpl.fromRow(
                        VerificationChallengeRepositoryImpl.toRow(challenge));
        assertEquals(now, consumed.consumedAt());
        assertFalse(consumed.canDeliver(now));
    }

    @Test
    void failedAttemptsUseExistingConditionalUpdate() {
        var challenge = VerificationChallengeRepositoryImpl.fromRow(row());
        when(mapper.failedAttempt(challenge.id(), now)).thenReturn(1);
        challenge.verify(
                CodePurpose.LOGIN,
                EmailAddress.create(challenge.originalEmail()),
                new byte[32],
                new byte[32 + 1],
                now);
        repository.failedAttempt(challenge, now);
        verify(mapper).failedAttempt(challenge.id(), now);
        assertEquals(3, challenge.attempts());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void consumptionMustAffectExactlyOneRow(int affected) {
        var row = row();
        var challenge = VerificationChallengeRepositoryImpl.fromRow(row);
        challenge.verify(
                CodePurpose.LOGIN,
                EmailAddress.create(row.originalEmail()),
                row.flowDigest(),
                row.codeDigest(),
                now);
        assertTrue(challenge.consume(now));
        when(mapper.consume(row.id(), now)).thenReturn(affected);
        assertThrows(IllegalStateException.class, () -> repository.consume(challenge));
    }

    @Test
    void unverifiedChallengeCannotBePersistedAsConsumed() {
        var challenge = VerificationChallengeRepositoryImpl.fromRow(row());
        assertThrows(IllegalStateException.class, () -> repository.consume(challenge));
        verifyNoInteractions(mapper);
    }

    @Test
    void invalidationLoadsAndLocksTheEntityBeforeUpdating() {
        var row = row();
        when(mapper.lockById(row.id())).thenReturn(Optional.of(row));
        repository.invalidate(row.id(), now);
        var order = inOrder(mapper);
        order.verify(mapper).lockById(row.id());
        order.verify(mapper).invalidate(row.id(), now);
    }

    @Test
    void inconsistentCanonicalEmailIsNotSilentlyRepaired() {
        var row = row();
        var invalid =
                new EmailChallengeRow(
                        row.id(),
                        row.purpose(),
                        "other@example.com",
                        row.originalEmail(),
                        row.flowDigest(),
                        row.codeDigest(),
                        row.eligible(),
                        row.attempts(),
                        row.maxAttempts(),
                        row.expiresAt(),
                        null,
                        null,
                        row.accountId(),
                        row.userId());
        assertThrows(
                IllegalStateException.class,
                () -> VerificationChallengeRepositoryImpl.fromRow(invalid));
    }
}
