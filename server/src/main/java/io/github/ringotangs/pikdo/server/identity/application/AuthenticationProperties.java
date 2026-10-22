package io.github.ringotangs.pikdo.server.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 所有限制均为共享数据库预算；生产密钥没有默认值。 */
@ConfigurationProperties("pikdo.auth")
public record AuthenticationProperties(
        @DefaultValue("") String hmacKey,
        @DefaultValue("60s") Duration resendInterval,
        @DefaultValue("5") int emailHourlyLimit,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10") int sendIpLimit,
        @DefaultValue("30") int verifyIpLimit,
        @DefaultValue("60") int csrfIpLimit,
        @DefaultValue("12h") Duration absoluteSessionLifetime,
        @DefaultValue("") String smtpHost,
        @DefaultValue("587") int smtpPort,
        @DefaultValue("") String smtpUsername,
        @DefaultValue("") String smtpPassword,
        @DefaultValue("") String mailFrom) {
    public AuthenticationProperties {
        if (resendInterval.isNegative()
                || resendInterval.isZero()
                || absoluteSessionLifetime.isNegative()
                || absoluteSessionLifetime.isZero()
                || emailHourlyLimit < 1
                || maxAttempts < 1
                || sendIpLimit < 1
                || verifyIpLimit < 1
                || csrfIpLimit < 1
                || smtpPort < 1
                || smtpPort > 65535)
            throw new IllegalArgumentException("Invalid authentication limits");
    }

    @Override
    public String toString() {
        return "AuthenticationProperties[redacted]";
    }
}
