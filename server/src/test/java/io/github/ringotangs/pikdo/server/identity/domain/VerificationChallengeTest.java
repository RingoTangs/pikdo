package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.UUID;

class VerificationChallengeTest {
    final UUID id = UUID.randomUUID();
    final EmailAddress email = EmailAddress.create("Original@Example.com");
    final byte[] flow = digest(1);
    final byte[] code = digest(2);
    final OffsetDateTime now = OffsetDateTime.parse("2026-10-22T00:00:00Z");

    static byte[] digest(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return bytes;
    }

    VerificationChallenge challenge(boolean eligible) {
        return VerificationChallenge.create(
                id,
                CodePurpose.REGISTER,
                email,
                flow,
                code,
                eligible,
                3,
                now.plusMinutes(5),
                null,
                null);
    }

    VerificationResult verify(VerificationChallenge challenge) {
        return challenge.verify(CodePurpose.REGISTER, email, flow, code, now);
    }

    @Test
    void correctCodeCanBeConsumedOnlyOnceAfterVerification() {
        var challenge = challenge(true);
        assertFalse(challenge.consume(now));
        assertEquals(VerificationResult.VERIFIED, verify(challenge));
        assertEquals(0, challenge.attempts());
        assertTrue(challenge.consume(now));
        assertEquals(now, challenge.consumedAt());
        assertFalse(challenge.consume(now));
        assertFalse(challenge.invalidate(now));
        assertFalse(challenge.canDeliver(now));
        assertEquals(VerificationResult.REJECTED, verify(challenge));
    }

    @Test
    void wrongCodeRecordsFailuresAndInvalidatesAtTheLimit() {
        var challenge = challenge(true);
        for (int count = 1; count <= 3; count++) {
            assertEquals(
                    VerificationResult.FAILED_ATTEMPT,
                    challenge.verify(CodePurpose.REGISTER, email, flow, digest(3), now));
            assertEquals(count, challenge.attempts());
            assertEquals(count == 3 ? now : null, challenge.invalidatedAt());
        }
        assertEquals(VerificationResult.REJECTED, verify(challenge));
        assertEquals(3, challenge.attempts());
        assertFalse(challenge.consume(now));
        assertFalse(challenge.canDeliver(now));
    }

    @Test
    void correctCodeAfterAnEarlierFailureCanStillSucceed() {
        var challenge = challenge(true);
        challenge.verify(CodePurpose.REGISTER, email, flow, digest(3), now);
        assertEquals(VerificationResult.VERIFIED, verify(challenge));
        assertTrue(challenge.consume(now));
        assertEquals(1, challenge.attempts());
    }

    @Test
    void ineligibleChallengeUsesTheSameFailureBudgetButCannotBeDelivered() {
        var challenge = challenge(false);
        assertFalse(challenge.canDeliver(now));
        assertEquals(VerificationResult.FAILED_ATTEMPT, verify(challenge));
        assertEquals(1, challenge.attempts());
        assertFalse(challenge.consume(now));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void mismatchedPurposeEmailOrBrowserDoesNotSpendAttempts(int mismatch) {
        var challenge = challenge(true);
        assertEquals(
                VerificationResult.REJECTED,
                challenge.verify(
                        mismatch == 0 ? CodePurpose.LOGIN : CodePurpose.REGISTER,
                        mismatch == 1 ? EmailAddress.create("other@example.com") : email,
                        mismatch == 2 ? digest(4) : flow,
                        code,
                        now));
        assertEquals(0, challenge.attempts());
        assertNull(challenge.invalidatedAt());
        assertFalse(challenge.consume(now));
        assertEquals(VerificationResult.VERIFIED, verify(challenge));
    }

    @Test
    void canonicalEmailBindingAcceptsAsciiCaseVariants() {
        assertEquals(
                VerificationResult.VERIFIED,
                challenge(true)
                        .verify(
                                CodePurpose.REGISTER,
                                EmailAddress.create("original@EXAMPLE.COM"),
                                flow,
                                code,
                                now));
    }

    @ParameterizedTest
    @ValueSource(longs = {300, 301})
    void expiredChallengeIsRejectedWithoutRecordingFailure(long seconds) {
        var challenge = challenge(true);
        var expired = now.plusSeconds(seconds);
        assertEquals(
                VerificationResult.REJECTED,
                challenge.verify(CodePurpose.REGISTER, email, flow, code, expired));
        assertEquals(0, challenge.attempts());
        assertFalse(challenge.canDeliver(expired));
    }

    @Test
    void verificationDoesNotExtendExpiryOrPermitConsumptionAfterExpiry() {
        var challenge = challenge(true);
        assertEquals(VerificationResult.VERIFIED, verify(challenge));
        assertFalse(challenge.consume(now.plusMinutes(5)));
        assertNull(challenge.consumedAt());
        assertEquals(now.plusMinutes(5), challenge.expiresAt());
    }

    @Test
    void invalidationIsTerminalAndPreservesItsFirstTimestamp() {
        var challenge = challenge(true);
        assertEquals(VerificationResult.VERIFIED, verify(challenge));
        assertTrue(challenge.invalidate(now));
        assertFalse(challenge.invalidate(now.plusSeconds(1)));
        assertEquals(now, challenge.invalidatedAt());
        assertEquals(VerificationResult.REJECTED, verify(challenge));
        assertEquals(0, challenge.attempts());
        assertFalse(challenge.consume(now));
    }

    @Test
    void failedReverificationCannotReuseEarlierVerification() {
        var challenge = challenge(true);
        assertEquals(VerificationResult.VERIFIED, verify(challenge));
        assertEquals(
                VerificationResult.REJECTED,
                challenge.verify(CodePurpose.LOGIN, email, flow, code, now));
        assertFalse(challenge.consume(now));
    }

    @Test
    void restorationPreservesSnapshotsAndLifecycleState() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var challenge =
                VerificationChallenge.restore(
                        id,
                        CodePurpose.LOGIN,
                        email,
                        flow,
                        code,
                        true,
                        2,
                        3,
                        now.plusMinutes(5),
                        now,
                        null,
                        accountId,
                        userId);
        assertEquals(accountId, challenge.accountId());
        assertEquals(userId, challenge.userId());
        assertEquals(2, challenge.attempts());
        assertEquals(3, challenge.maxAttempts());
        assertEquals(now, challenge.consumedAt());
        assertEquals(
                VerificationResult.REJECTED,
                challenge.verify(CodePurpose.LOGIN, email, flow, code, now));
        var exhausted =
                VerificationChallenge.restore(
                        id,
                        CodePurpose.REGISTER,
                        email,
                        flow,
                        code,
                        true,
                        3,
                        3,
                        now.plusMinutes(5),
                        null,
                        null,
                        null,
                        null);
        assertEquals(VerificationResult.REJECTED, verify(exhausted));
    }

    @Test
    void digestsAreDefensivelyCopiedAndDiagnosticsAreRedacted() {
        var challenge = challenge(true);
        flow[0] = 9;
        code[0] = 9;
        challenge.flowDigest()[0] = 8;
        challenge.codeDigest()[0] = 8;
        assertArrayEquals(digest(1), challenge.flowDigest());
        assertArrayEquals(digest(2), challenge.codeDigest());
        assertEquals(
                VerificationResult.VERIFIED,
                challenge.verify(CodePurpose.REGISTER, email, digest(1), digest(2), now));
        assertEquals("VerificationChallenge[redacted]", challenge.toString());
    }

    @Test
    void invalidSnapshotsAndBudgetsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VerificationChallenge.create(
                                id,
                                CodePurpose.LOGIN,
                                email,
                                flow,
                                code,
                                true,
                                3,
                                now,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VerificationChallenge.create(
                                id,
                                CodePurpose.REGISTER,
                                email,
                                new byte[0],
                                code,
                                true,
                                3,
                                now,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VerificationChallenge.create(
                                id,
                                CodePurpose.REGISTER,
                                email,
                                flow,
                                code,
                                true,
                                0,
                                now,
                                null,
                                null));
        for (int attempts : new int[] {-1, 4})
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            VerificationChallenge.restore(
                                    id,
                                    CodePurpose.REGISTER,
                                    email,
                                    flow,
                                    code,
                                    true,
                                    attempts,
                                    3,
                                    now,
                                    null,
                                    null,
                                    null,
                                    null));
        assertThrows(
                NullPointerException.class,
                () ->
                        VerificationChallenge.create(
                                null,
                                CodePurpose.REGISTER,
                                email,
                                flow,
                                code,
                                true,
                                3,
                                now,
                                null,
                                null));
    }
}
