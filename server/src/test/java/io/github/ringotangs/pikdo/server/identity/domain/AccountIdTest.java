package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class AccountIdTest {
    private static final String UUID_TEXT = "11111111-2222-4333-8444-555555555555";

    @Test
    void retainsUuidValue() {
        UUID value = UUID.fromString(UUID_TEXT);

        assertEquals(value, new AccountId(value).value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new AccountId(null));
    }

    @Test
    void equalUuidValuesHaveEqualIdentifiersAndHashCodes() {
        AccountId first = new AccountId(UUID.fromString(UUID_TEXT));
        AccountId second = new AccountId(UUID.fromString(UUID_TEXT));

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void differentUuidValuesHaveDifferentIdentifiers() {
        AccountId first = new AccountId(UUID.fromString(UUID_TEXT));
        AccountId second = new AccountId(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));

        assertNotEquals(first, second);
    }
}
