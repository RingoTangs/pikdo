package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

class PasswordCredentialTest {
    private static final UserId USER_ID =
            new UserId(UUID.fromString("11111111-2222-4333-8444-555555555555"));

    @Test
    void createsInitialStateAndRestoresAnExistingVersionWithoutChangingValues() {
        // 哈希与输入版本视为不透明值，不解释算法，也不修剪内容。
        var initial = PasswordCredential.create(USER_ID, " test-hash ", " test-input-v1 ");
        assertSame(USER_ID, initial.getUserId());
        assertEquals(" test-hash ", initial.getPasswordHash());
        assertEquals(" test-input-v1 ", initial.getPasswordInputVersion());
        assertEquals(0, initial.getAuthenticationVersion());
        var restored = PasswordCredential.restore(USER_ID, "test-restored-hash", 7, "test-v2");
        assertSame(USER_ID, restored.getUserId());
        assertEquals("test-restored-hash", restored.getPasswordHash());
        assertEquals("test-v2", restored.getPasswordInputVersion());
        assertEquals(7, restored.getAuthenticationVersion());
        assertEquals(0, initial.getAuthenticationVersion());
        assertEquals(
                Long.MAX_VALUE,
                PasswordCredential.restore(USER_ID, "test-hash", Long.MAX_VALUE, "test-v1")
                        .getAuthenticationVersion());
    }

    @Test
    void rejectsMissingOwnerAndNegativeVersion() {
        assertThrows(
                NullPointerException.class,
                () -> PasswordCredential.create(null, "test-hash", "test-v1"));
        assertThrows(
                NullPointerException.class,
                () -> PasswordCredential.restore(null, "test-hash", 0, "test-v1"));
        for (long version : new long[] {-1, Long.MIN_VALUE}) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    PasswordCredential.restore(
                                            USER_ID, "sensitive-test-hash", version, "test-v1"));
            assertFalse(failure.getMessage().contains("sensitive-test-hash"));
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\r\n", "\u2003"})
    void rejectsMissingOrBlankHashAndInputVersion(String value) {
        Class<? extends RuntimeException> expected =
                value == null ? NullPointerException.class : IllegalArgumentException.class;
        assertThrows(expected, () -> PasswordCredential.create(USER_ID, value, "test-v1"));
        assertThrows(expected, () -> PasswordCredential.create(USER_ID, "test-hash", value));
        assertThrows(expected, () -> PasswordCredential.restore(USER_ID, value, 7, "test-v1"));
        var failure =
                assertThrows(
                        expected,
                        () -> PasswordCredential.restore(USER_ID, "sensitive-test-hash", 7, value));
        assertFalse(failure.getMessage().contains("sensitive-test-hash"));
    }

    @Test
    void stringRepresentationNeverContainsCredentialValues() {
        var credential =
                PasswordCredential.restore(USER_ID, "sensitive-test-hash", 8, "private-test-input");
        assertEquals("PasswordCredential[redacted]", credential.toString());
        assertFalse(credential.toString().contains(credential.getPasswordHash()));
        assertFalse(credential.toString().contains(credential.getPasswordInputVersion()));
    }
}
