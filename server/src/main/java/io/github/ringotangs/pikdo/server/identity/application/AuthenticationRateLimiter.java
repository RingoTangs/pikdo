package io.github.ringotangs.pikdo.server.identity.application;

import io.github.ringotangs.pikdo.server.identity.persistence.AuthenticationRateMapper;

import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 拒绝请求也提交已使用的来源预算，不因业务异常回滚限流。 */
public final class AuthenticationRateLimiter {
    private final AuthenticationRateMapper mapper;
    private final VerificationCodes codes;
    private final AuthenticationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AuthenticationRateLimiter(
            AuthenticationRateMapper mapper,
            VerificationCodes codes,
            AuthenticationProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.mapper = mapper;
        this.codes = codes;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    public void send(String source, String email) {
        boolean accepted =
                Boolean.TRUE.equals(
                        transactions.execute(
                                status ->
                                        take(
                                                        "SOURCE_SEND",
                                                        source,
                                                        properties.sendIpLimit(),
                                                        Duration.ofMinutes(1),
                                                        Duration.ZERO)
                                                && take(
                                                        "MAIL_SEND",
                                                        email,
                                                        properties.emailHourlyLimit(),
                                                        Duration.ofHours(1),
                                                        properties.resendInterval())));
        require(accepted);
    }

    public void verify(String source) {
        require(
                Boolean.TRUE.equals(
                        transactions.execute(
                                status ->
                                        take(
                                                "SOURCE_VERIFY",
                                                source,
                                                properties.verifyIpLimit(),
                                                Duration.ofMinutes(1),
                                                Duration.ZERO))));
    }

    public void csrf(String source) {
        require(
                Boolean.TRUE.equals(
                        transactions.execute(
                                status ->
                                        take(
                                                "SOURCE_CSRF",
                                                source,
                                                properties.csrfIpLimit(),
                                                Duration.ofMinutes(1),
                                                Duration.ZERO))));
    }

    /** HTTP 入口预算覆盖格式错误和未通过 CSRF 的请求。 */
    public void request(String source, boolean sending) {
        require(
                Boolean.TRUE.equals(
                        transactions.execute(
                                status ->
                                        take(
                                                sending ? "HTTP_SEND" : "HTTP_VERIFY",
                                                source,
                                                sending
                                                        ? properties.sendIpLimit()
                                                        : properties.verifyIpLimit(),
                                                Duration.ofMinutes(1),
                                                Duration.ZERO))));
    }

    private boolean take(
            String scope, String subject, int limit, Duration window, Duration cooldown) {
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return mapper.reserve(
                        scope,
                        codes.digest("rate", scope, subject),
                        now,
                        now.plus(window),
                        now.plus(cooldown),
                        limit)
                .isPresent();
    }

    private static void require(boolean accepted) {
        if (!accepted) throw new AuthenticationFailure(AuthenticationFailure.Reason.RATE_LIMITED);
    }
}
