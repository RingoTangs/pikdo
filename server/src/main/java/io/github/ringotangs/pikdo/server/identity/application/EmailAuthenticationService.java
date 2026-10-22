package io.github.ringotangs.pikdo.server.identity.application;

import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.identity.mail.AsyncEmailDelivery;
import io.github.ringotangs.pikdo.server.identity.persistence.EmailChallengeMapper;
import io.github.ringotangs.pikdo.server.identity.persistence.EmailChallengeRow;
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
    private final EmailChallengeMapper challenges;
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
            EmailChallengeMapper challenges,
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
        var row =
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
                                    new EmailChallengeRow(
                                            id,
                                            purpose.name(),
                                            email.getCanonicalAddress(),
                                            original,
                                            codes.digest("flow", flow),
                                            codeDigest(
                                                    id,
                                                    purpose,
                                                    email.getCanonicalAddress(),
                                                    flow,
                                                    code),
                                            eligible,
                                            0,
                                            properties.maxAttempts(),
                                            now.plusMinutes(5),
                                            null,
                                            null,
                                            account.map(a -> a.getId().value()).orElse(null),
                                            account.map(a -> a.getUserId().value()).orElse(null));
                            challenges.invalidateFlow(
                                    challenge.flowDigest(),
                                    email.getCanonicalAddress(),
                                    purpose.name(),
                                    now);
                            if (challenges.insert(challenge) != 1)
                                throw new IllegalStateException("Challenge insertion failed");
                            delivery.afterCommit(challenge, purpose, code);
                            return challenge;
                        });
        if (row == null) throw new AuthenticationFailure(AuthenticationFailure.Reason.UNAVAILABLE);
        return new IssuedCode(
                id, row.expiresAt().toInstant(), properties.resendInterval().toSeconds());
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
        var row = challenges.lockById(challengeId).orElse(null);
        var now = now();
        // 不允许另一个用途、邮箱或浏览器消耗该挑战的次数。
        if (row == null
                || !row.purpose().equals(purpose.name())
                || !row.canonicalEmail().equals(email.getCanonicalAddress())
                || !codes.matches(row.flowDigest(), codes.digest("flow", flow)))
            return Optional.empty();
        if (row.consumedAt() != null
                || row.invalidatedAt() != null
                || !row.expiresAt().isAfter(now)
                || row.attempts() >= row.maxAttempts()) return Optional.empty();
        boolean matches =
                codes.matches(
                        row.codeDigest(),
                        codeDigest(row.id(), purpose, row.canonicalEmail(), flow, code));
        if (!matches || !row.eligible()) {
            challenges.failedAttempt(row.id(), now);
            // 返回结果而不在事务内抛业务异常，确保错误尝试次数提交。
            return Optional.empty();
        }
        UserId userId;
        if (purpose == CodePurpose.REGISTER) {
            if (accounts.findByIdentifier(new EmailIdentifier(email)).isPresent()) {
                challenges.invalidate(row.id(), now);
                return Optional.empty();
            }
            userId = new UserId(UuidV7Generator.generate());
            users.insert(User.create(userId));
            accounts.insert(
                    Account.create(
                            new AccountId(UuidV7Generator.generate()),
                            userId,
                            new EmailIdentifier(EmailAddress.create(row.originalEmail()))));
        } else {
            var account = accounts.findByIdentifier(new EmailIdentifier(email)).orElse(null);
            if (account == null
                    || !account.getId().value().equals(row.accountId())
                    || !account.getUserId().value().equals(row.userId())) {
                challenges.invalidate(row.id(), now);
                return Optional.empty();
            }
            userId = account.getUserId();
            if (challenges.lockUser(userId.value()).isEmpty()
                    || users.findById(userId)
                            .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                            .isEmpty()) {
                challenges.invalidate(row.id(), now);
                return Optional.empty();
            }
        }
        if (challenges.consume(row.id(), now) != 1)
            throw new IllegalStateException("Challenge consumption failed");
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
