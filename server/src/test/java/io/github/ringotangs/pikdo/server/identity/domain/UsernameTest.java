package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

class UsernameTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "abc",
                "Alice_123",
                "A__",
                "a00",
                "admin123",
                "admin_",
                "system1",
                "support_team",
                "root",
                "administrator"
            })
    void acceptsAsciiNamesAndRetainsOriginalSpelling(String value) {
        assertEquals(value, Username.create(value).getOriginalUsername());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "a",
                "ab",
                "1abc",
                "_abc",
                " Alice",
                "Alice ",
                "Ali ce",
                "Alice-1",
                "Alice.1",
                "Alice@example.com",
                "Alíce",
                "用户abc",
                "Ａlice",
                "Alice١",
                "Alice\n",
                "Alice\r",
                "Alice\t",
                "Alice\u0000"
            })
    void rejectsInvalidFormatWithoutCleaningInput(String value) {
        assertThrows(IllegalArgumentException.class, () -> Username.create(value));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "admin", "ADMIN", "AdMiN", "system", "SYSTEM", "SyStEm", "support", "SUPPORT",
                "SuPpOrT"
            })
    void rejectsOnlyTheExactReservedNamesIgnoringAsciiCase(String value) {
        assertThrows(IllegalArgumentException.class, () -> Username.create(value));
    }

    @Test
    void enforcesLengthBoundariesAndRejectsNull() {
        assertThrows(NullPointerException.class, () -> Username.create(null));
        assertEquals("Abc", Username.create("Abc").getOriginalUsername());
        String longest = "A" + "b".repeat(31);
        assertEquals(longest, Username.create(longest).getOriginalUsername());
        assertThrows(IllegalArgumentException.class, () -> Username.create(longest + "c"));
    }

    @Test
    void identityEqualityUsesCanonicalSpellingWithoutReplacingOriginals() {
        var first = Username.create("Alice_123");
        var second = Username.create("alice_123");
        assertEquals("Alice_123", first.getOriginalUsername());
        assertEquals("alice_123", first.getCanonicalUsername());
        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first, Username.create(first.getCanonicalUsername()));
        assertNotEquals(first, Username.create("Alice123"));
        assertNotEquals(first, Username.create("Alice_124"));
        assertNotEquals(first, null);
        assertNotEquals(first, "alice_123");
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void asciiFoldingDoesNotDependOnDefaultLocale() {
        Locale previous = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("iris_123", Username.create("IRIS_123").getCanonicalUsername());
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }
}
