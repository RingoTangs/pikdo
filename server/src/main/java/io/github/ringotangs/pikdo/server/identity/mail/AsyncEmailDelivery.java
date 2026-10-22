package io.github.ringotangs.pikdo.server.identity.mail;

import io.github.ringotangs.pikdo.server.identity.domain.CodePurpose;
import io.github.ringotangs.pikdo.server.identity.domain.VerificationChallengeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public final class AsyncEmailDelivery {
    private static final Logger log = LoggerFactory.getLogger(AsyncEmailDelivery.class);
    private final Executor executor;
    private final EmailCodeSender sender;
    private final VerificationChallengeRepository challenges;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AsyncEmailDelivery(
            @Qualifier("authenticationMailExecutor") Executor executor,
            EmailCodeSender sender,
            VerificationChallengeRepository challenges,
            PlatformTransactionManager manager,
            Clock clock) {
        this.executor = executor;
        this.sender = sender;
        this.challenges = challenges;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public void afterCommit(UUID id, CodePurpose purpose, String code) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        submit(id, purpose, code);
                    }
                });
    }

    private void submit(UUID id, CodePurpose purpose, String code) {
        try {
            executor.execute(() -> deliver(id, purpose, code));
        } catch (RejectedExecutionException failure) {
            log.warn("Email verification queue unavailable");
            invalidate(id);
        }
    }

    private void deliver(UUID id, CodePurpose purpose, String code) {
        try {
            var challenge = transactions.execute(status -> challenges.lockById(id).orElse(null));
            if (challenge == null || !challenge.canDeliver(now())) return;
            sender.send(challenge.originalEmail(), purpose, code);
        } catch (RuntimeException failure) {
            log.warn("Email verification delivery failed");
            invalidate(id);
        }
    }

    private void invalidate(UUID id) {
        try {
            transactions.executeWithoutResult(status -> challenges.invalidate(id, now()));
        } catch (RuntimeException failure) {
            log.warn("Email verification invalidation failed");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
