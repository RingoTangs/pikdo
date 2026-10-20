package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

class UserIdTest {
    private static final String UUID_TEXT = "11111111-2222-4333-8444-555555555555";

    @Test
    void retainsUuidValue() {
        UUID value = UUID.fromString(UUID_TEXT);

        UserId id = new UserId(value);

        assertEquals(value, id.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new UserId(null));
    }

    @Test
    void equalUuidValuesHaveEqualIdentifiersAndHashCodes() {
        UserId first = new UserId(UUID.fromString(UUID_TEXT));
        UserId second = new UserId(UUID.fromString(UUID_TEXT));

        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void differentUuidValuesHaveDifferentIdentifiers() {
        UserId first = new UserId(UUID.fromString(UUID_TEXT));
        UserId second = new UserId(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));

        assertNotEquals(first, second);
    }

    @Test
    void canBeUsedAsStableValueKeyAcrossUserLifecycle() {
        UserId id = new UserId(UUID.fromString(UUID_TEXT));
        Map<UserId, String> ownership = Map.of(id, "owned resource");
        User user = User.create(id);

        user.disable();
        user.restore();

        assertEquals(UUID.fromString(UUID_TEXT), id.value());
        assertEquals("owned resource", ownership.get(new UserId(UUID.fromString(UUID_TEXT))));
    }
}
