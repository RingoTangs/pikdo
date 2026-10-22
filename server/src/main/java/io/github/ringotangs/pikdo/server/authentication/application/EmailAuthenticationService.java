package io.github.ringotangs.pikdo.server.authentication.application;

import io.github.ringotangs.pikdo.server.authentication.domain.CodePurpose;
import io.github.ringotangs.pikdo.server.authentication.domain.VerificationChallenge;
import io.github.ringotangs.pikdo.server.authentication.domain.VerificationChallengeRepository;
import io.github.ringotangs.pikdo.server.authentication.domain.VerificationResult;
import io.github.ringotangs.pikdo.server.authentication.mail.AsyncEmailDelivery;
import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.shared.id.UuidV7Generator;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public final class EmailAuthenticationService {
    private final UserRepository users;
    private final AccountRepository accounts;
    private final VerificationChallengeRepository challenges;
    private final VerificationCodes codes;
    private final AuthenticationRateLimiter rates;
    private final AsyncEmailDelivery delivery;
    private final AuthenticationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public record IssuedCode(UUID challengeId, Instant expiresAt, long retryAfterSeconds) {}

    public EmailAuthenticationService(
            UserRepository users,
            AccountRepository accounts,
            VerificationChallengeRepository challenges,
            VerificationCodes codes,
            AuthenticationRateLimiter rates,
            AsyncEmailDelivery delivery,
            AuthenticationProperties properties,
            PlatformTransactionManager manager,
            Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.challenges = challenges;
        this.codes = codes;
        this.rates = rates;
        this.delivery = delivery;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    public IssuedCode send(CodePurpose purpose, EmailAddress email, String flow, String source) {
        rates.send(source, email.getCanonicalAddress());
        var id = UuidV7Generator.generate();
        var now = now();
        String code = codes.generate();
        var issued =
                transactions.execute(
                        status -> {
                            var account = accounts.findByIdentifier(new EmailIdentifier(email));
                            boolean eligible =
                                    purpose == CodePurpose.REGISTER
                                            ? account.isEmpty()
                                            : account.isPresent()
                                                    && users.findById(account.get().getUserId())
                                                            .filter(
                                                                    user ->
                                                                            user.getStatus()
                                                                                    == UserStatus
                                                                                            .ACTIVE)
                                                            .isPresent();
                            // 登录只使用已保存的原始邮箱投递，不接受调用方的大小写变体改写目标。
                            String original =
                                    purpose == CodePurpose.LOGIN && account.isPresent()
                                            ? account.get().getIdentifier().getOriginalIdentifier()
                                            : email.getOriginalAddress();
                            var challenge =
                                    VerificationChallenge.create(
                                            id,
                                            purpose,
                                            EmailAddress.create(original),
                                            codes.digest("flow", flow),
                                            codeDigest(
                                                    id,
                                                    purpose,
                                                    email.getCanonicalAddress(),
                                                    flow,
                                                    code),
                                            eligible,
                                            properties.maxAttempts(),
                                            now.plusMinutes(5),
                                            account.map(a -> a.getId().value()).orElse(null),
                                            account.map(a -> a.getUserId().value()).orElse(null));
                            challenges.invalidateFlow(challenge, now);
                            challenges.insert(challenge);
                            delivery.afterCommit(challenge.id(), purpose, code);
                            return challenge;
                        });
        if (issued == null)
            throw new AuthenticationFailure(AuthenticationFailure.Reason.UNAVAILABLE);
        return new IssuedCode(
                id, issued.expiresAt().toInstant(), properties.resendInterval().toSeconds());
    }

    public UserId complete(
            CodePurpose purpose,
            UUID challengeId,
            EmailAddress email,
            String code,
            String flow,
            String source) {
        rates.verify(source);
        Optional<UserId> result;
        try {
            result =
                    transactions.execute(
                            status -> verifyAndConsume(purpose, challengeId, email, code, flow));
        } catch (DuplicateKeyException conflict) {
            // 并发注册只能有一个赢家；失败事务中的用户和绑定一起回滚。
            transactions.executeWithoutResult(status -> challenges.invalidate(challengeId, now()));
            throw invalid();
        }
        if (result == null || result.isEmpty()) throw invalid();
        return result.get();
    }

    private Optional<UserId> verifyAndConsume(
            CodePurpose purpose, UUID challengeId, EmailAddress email, String code, String flow) {
        var challenge = challenges.lockById(challengeId).orElse(null);
        var now = now();
        if (challenge == null) return Optional.empty();
        var verification =
                challenge.verify(
                        purpose,
                        email,
                        codes.digest("flow", flow),
                        codeDigest(
                                challenge.id(), purpose, email.getCanonicalAddress(), flow, code),
                        now);
        if (verification == VerificationResult.FAILED_ATTEMPT) {
            challenges.failedAttempt(challenge, now);
        }
        // 返回结果而不在事务内抛业务异常，确保错误尝试次数提交。
        if (verification != VerificationResult.VERIFIED) return Optional.empty();
        UserId userId;
        if (purpose == CodePurpose.REGISTER) {
            if (accounts.findByIdentifier(new EmailIdentifier(email)).isPresent()) {
                challenges.invalidate(challenge.id(), now);
                return Optional.empty();
            }
            userId = new UserId(UuidV7Generator.generate());
            users.insert(User.create(userId));
            accounts.insert(
                    Account.create(
                            new AccountId(UuidV7Generator.generate()),
                            userId,
                            new EmailIdentifier(EmailAddress.create(challenge.originalEmail()))));
        } else {
            var account = accounts.findByIdentifier(new EmailIdentifier(email)).orElse(null);
            if (account == null
                    || !account.getId().value().equals(challenge.accountId())
                    || !account.getUserId().value().equals(challenge.userId())) {
                challenges.invalidate(challenge.id(), now);
                return Optional.empty();
            }
            userId = account.getUserId();
            if (!challenges.lockUser(userId)
                    || users.findById(userId)
                            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                            .isEmpty()) {
                challenges.invalidate(challenge.id(), now);
                return Optional.empty();
            }
        }
        if (!challenge.consume(now))
            throw new IllegalStateException("Challenge consumption failed");
        challenges.consume(challenge);
        return Optional.of(userId);
    }

    private byte[] codeDigest(
            UUID id, CodePurpose purpose, String canonical, String flow, String code) {
        return codes.digest("code", id.toString(), purpose.name(), canonical, flow, code);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static AuthenticationFailure invalid() {
        return new AuthenticationFailure(AuthenticationFailure.Reason.INVALID);
    }
}
