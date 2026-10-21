package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumberTest {
    @ParameterizedTest
    @ValueSource(strings = {"+12", "+8613812345678", "+14155552671", "+123456789012345"})
    void retainsOnlyCanonicalInternationalSyntax(String value) {
        assertEquals(value, PhoneNumber.create(value).getValue());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "+",
                "+1",
                "+0123",
                "13812345678",
                "008613812345678",
                "+86 13812345678",
                "+86-13812345678",
                "+86(138)12345678",
                " +8613812345678",
                "+8613812345678 ",
                "+8613812345678\n",
                "+8613812345678\r",
                "+8613812345678\t",
                "+１２３４",
                "＋1234",
                "+١٢٣٤",
                "+1234567890123456",
                "+123x42",
                "+123\u0000"
            })
    void rejectsNonCanonicalOrOutOfBoundsNumbers(String value) {
        var failure = assertThrows(IllegalArgumentException.class, () -> PhoneNumber.create(value));
        if (!value.isEmpty()) {
            assertFalse(failure.getMessage().contains(value));
        }
    }

    @Test
    void nullIsRejectedAndEqualityUsesTheEntireNumber() {
        assertThrows(NullPointerException.class, () -> PhoneNumber.create(null));
        var first = PhoneNumber.create("+8613812345678");
        var second = PhoneNumber.create("+8613812345678");
        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, PhoneNumber.create("+8613812345679"));
        assertNotEquals(first, PhoneNumber.create("+113812345678"));
        assertNotEquals(first, null);
        assertNotEquals(first, "+8613812345678");
    }
}
