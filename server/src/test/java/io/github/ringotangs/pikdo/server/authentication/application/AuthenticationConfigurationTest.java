package io.github.ringotangs.pikdo.server.authentication.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.ringotangs.pikdo.server.authentication.mail.SmtpEmailCodeSender;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** 配置在启动时拒绝缺失密钥、不安全的开发投递目标和无效预算。 */
class AuthenticationConfigurationTest {
    AuthenticationProperties properties(String host, int limit) {
        return new AuthenticationProperties(
                "secret-key",
                Duration.ofSeconds(60),
                limit,
                5,
                10,
                30,
                60,
                Duration.ofHours(12),
                host,
                587,
                "user",
                "smtp-secret",
                "noreply@pikdo.invalid",
                2,
                100);
    }

    @Test
    void developmentMailOnlyUsesLoopback() {
        assertDoesNotThrow(() -> new SmtpEmailCodeSender(properties("localhost", 5), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SmtpEmailCodeSender(properties("smtp.example.com", 5), true));
    }

    @Test
    void productionRequiresExplicitMailSettings() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SmtpEmailCodeSender(properties("", 5), false));
    }

    @Test
    void invalidBudgetsFailAndSecretsAreRedacted() {
        assertThrows(IllegalArgumentException.class, () -> properties("localhost", 0));
        var text = properties("localhost", 5).toString();
        assertFalse(text.contains("secret-key"));
        assertFalse(text.contains("smtp-secret"));
    }
}
