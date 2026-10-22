package io.github.ringotangs.pikdo.server.authentication.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.ringotangs.pikdo.server.authentication.domain.CodePurpose;
import io.github.ringotangs.pikdo.server.authentication.persistence.*;

import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 显式控制提交回调和队列，不依赖睡眠或外部 SMTP。 */
class AsyncEmailDeliveryTest {
    final EmailChallengeMapper challenges = mock(EmailChallengeMapper.class);
    final EmailCodeSender sender = mock(EmailCodeSender.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
    final List<Runnable> queue = new ArrayList<>();
    PlatformTransactionManager manager;

    @BeforeEach
    void setup() {
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    EmailChallengeRow row(boolean eligible, OffsetDateTime expiry) {
        return new EmailChallengeRow(
                UUID.randomUUID(),
                "REGISTER",
                "secret@example.com",
                "Secret@example.com",
                new byte[32],
                new byte[32],
                eligible,
                0,
                5,
                expiry,
                null,
                null,
                null,
                null);
    }

    EmailChallengeRow row(boolean eligible) {
        return row(eligible, OffsetDateTime.now(clock).plusMinutes(5));
    }

    AsyncEmailDelivery delivery(Executor executor) {
        return new AsyncEmailDelivery(
                executor,
                sender,
                new VerificationChallengeRepositoryImpl(challenges),
                manager,
                clock);
    }

    void commit() {
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations())
            synchronization.afterCommit();
    }

    @Test
    void bothEligibilityStatesAreScheduledOnlyAfterCommit() {
        var eligible = row(true);
        var inert = row(false);
        when(challenges.lockById(eligible.id())).thenReturn(Optional.of(eligible));
        when(challenges.lockById(inert.id())).thenReturn(Optional.of(inert));
        var delivery = delivery(queue::add);
        delivery.afterCommit(eligible.id(), CodePurpose.REGISTER, "123456");
        delivery.afterCommit(inert.id(), CodePurpose.REGISTER, "654321");
        assertTrue(queue.isEmpty());
        verifyNoInteractions(sender);
        commit();
        assertEquals(2, queue.size());
        queue.forEach(Runnable::run);
        verify(sender).send("Secret@example.com", CodePurpose.REGISTER, "123456");
        verifyNoMoreInteractions(sender);
    }

    @Test
    void expiredConsumedInvalidatedAndMissingChallengesAreNotDelivered() {
        var base = row(true);
        var now = OffsetDateTime.now(clock);
        var expired = row(true, now);
        var consumed =
                new EmailChallengeRow(
                        base.id(),
                        base.purpose(),
                        base.canonicalEmail(),
                        base.originalEmail(),
                        base.flowDigest(),
                        base.codeDigest(),
                        true,
                        0,
                        5,
                        base.expiresAt(),
                        now,
                        null,
                        null,
                        null);
        var invalidated =
                new EmailChallengeRow(
                        base.id(),
                        base.purpose(),
                        base.canonicalEmail(),
                        base.originalEmail(),
                        base.flowDigest(),
                        base.codeDigest(),
                        true,
                        0,
                        5,
                        base.expiresAt(),
                        null,
                        now,
                        null,
                        null);
        when(challenges.lockById(any()))
                .thenReturn(
                        Optional.of(expired),
                        Optional.of(consumed),
                        Optional.of(invalidated),
                        Optional.empty());
        for (int i = 0; i < 4; i++)
            delivery(queue::add).afterCommit(base.id(), CodePurpose.REGISTER, "123456");
        commit();
        queue.forEach(Runnable::run);
        verifyNoInteractions(sender);
    }

    @Test
    void rejectionInvalidatesEitherEligibilityWithoutThrowing() {
        var eligible = row(true);
        var inert = row(false);
        when(challenges.lockById(eligible.id())).thenReturn(Optional.of(eligible));
        when(challenges.lockById(inert.id())).thenReturn(Optional.of(inert));
        var delivery =
                delivery(
                        task -> {
                            throw new RejectedExecutionException("secret@example.com 123456");
                        });
        delivery.afterCommit(eligible.id(), CodePurpose.REGISTER, "123456");
        delivery.afterCommit(inert.id(), CodePurpose.REGISTER, "123456");
        assertDoesNotThrow(this::commit);
        verify(challenges).invalidate(eq(eligible.id()), any());
        verify(challenges).invalidate(eq(inert.id()), any());
        verifyNoInteractions(sender);
    }

    @Test
    void smtpAndInvalidationFailuresAreContainedAndRedacted() {
        var row = row(true);
        when(challenges.lockById(row.id())).thenReturn(Optional.of(row));
        doThrow(new IllegalStateException("secret@example.com 123456"))
                .when(sender)
                .send(anyString(), any(), anyString());
        when(challenges.invalidate(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("secret@example.com 123456"));
        var logger = (Logger) LoggerFactory.getLogger(AsyncEmailDelivery.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            delivery(queue::add).afterCommit(row.id(), CodePurpose.REGISTER, "123456");
            commit();
            assertDoesNotThrow(() -> queue.getFirst().run());
            assertEquals(2, logs.list.size());
            for (var event : logs.list) {
                assertFalse(event.getFormattedMessage().contains("123456"));
                assertFalse(event.getFormattedMessage().contains("secret@example.com"));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void backgroundReadsKeepRequiresNewPropagation() {
        var row = row(true);
        when(challenges.lockById(row.id())).thenReturn(Optional.of(row));
        delivery(queue::add).afterCommit(row.id(), CodePurpose.REGISTER, "123456");
        commit();
        queue.getFirst().run();
        verify(manager)
                .getTransaction(
                        argThat(
                                definition ->
                                        definition.getPropagationBehavior()
                                                == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }
}
