package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class EmailAddressTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "alice@example.com",
                "Alice@Example.COM",
                "alice.smith@example.com",
                "alice+news@example.com",
                "a.b@sub.example.com",
                "!#$%&'*+-/=?^_`{|}~@example.com",
                "alice@xn--bcher-kva.example",
                "alice@sub-domain.example",
                "alice@1.2.3.example",
                "alice@256.1.1.1"
            })
    void acceptsSupportedAddressesAndRetainsOriginalSpelling(String original) {
        assertEquals(original, EmailAddress.create(original).getOriginalAddress());
    }

    @Test
    void rejectsNullAddress() {
        assertThrows(NullPointerException.class, () -> EmailAddress.create(null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " alice@example.com",
                "alice@example.com ",
                "ali ce@example.com",
                "alice@exam ple.com",
                "alice\t@example.com",
                "alice\n@example.com",
                "alice@example.com\r\n",
                "alice\u0000@example.com",
                "alice\u007f@example.com",
                "aliceexample.com",
                "alice@@example.com",
                "alice@bob@example.com",
                "@example.com",
                "alice@",
                ".alice@example.com",
                "alice.@example.com",
                "ali..ce@example.com",
                "\"alice\"@example.com",
                "Alice <alice@example.com>",
                "alice(comment)@example.com",
                "alice,bob@example.com",
                "alice\\bob@example.com",
                "用户@example.com",
                "alice@例子.com",
                "álîce@example.com",
                "alice\u00a0@example.com",
                "alice@example_com",
                "alice@-example.com",
                "alice@example-.com",
                "alice@example.-com",
                "alice@example.com-",
                "alice@localhost",
                "alice@.example.com",
                "alice@example..com",
                "alice@example.com.",
                "alice@[127.0.0.1]",
                "alice@[IPv6:::1]",
                "alice@127.0.0.1",
                "alice@0.0.0.0",
                "alice@255.255.255.255",
                "alice@001.002.003.004"
            })
    void rejectsUnsupportedAddresses(String original) {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> EmailAddress.create(original));
        if (!original.isEmpty()) {
            assertFalse(exception.getMessage().contains(original));
        }
    }

    @Test
    void foldsAsciiCaseWhilePreservingOriginalRepresentation() {
        // 原始拼写用于保留输入；规范化值用于身份比较，不代表邮箱控制权。
        EmailAddress email = EmailAddress.create("Alice.Smith+NEWS@EXAMPLE.COM");

        assertEquals("Alice.Smith+NEWS@EXAMPLE.COM", email.getOriginalAddress());
        assertEquals("alice.smith+news@example.com", email.getCanonicalAddress());
    }

    @Test
    void canonicalizationIsIdempotent() {
        EmailAddress original = EmailAddress.create("Alice@Example.COM");
        EmailAddress canonical = EmailAddress.create(original.getCanonicalAddress());

        assertEquals(original.getCanonicalAddress(), canonical.getCanonicalAddress());
        assertEquals(original, canonical);
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void canonicalizationDoesNotDependOnDefaultLocale() {
        // 土耳其语的 I 大小写规则不同；ASCII 身份键不能受默认 Locale 影响。
        Locale previous = Locale.getDefault();
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.US);
            EmailAddress english = EmailAddress.create("IRIS@IDENTITY.EXAMPLE");
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            EmailAddress turkish = EmailAddress.create("IRIS@IDENTITY.EXAMPLE");

            assertEquals("iris@identity.example", turkish.getCanonicalAddress());
            assertEquals(english, turkish);
            assertEquals("IRIS@IDENTITY.EXAMPLE", turkish.getOriginalAddress());
        } finally {
            // Locale 是 JVM 全局状态，必须恢复各类别的原值，避免影响其他测试。
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }

    @Test
    void identityEqualityIsReflexiveSymmetricTransitiveAndConsistent() {
        EmailAddress first = EmailAddress.create("Alice@Example.COM");
        EmailAddress second = EmailAddress.create("alice@example.com");
        EmailAddress third = EmailAddress.create("ALICE@EXAMPLE.com");

        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(second, third);
        assertEquals(first, third);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(second.hashCode(), third.hashCode());
        assertFalse(first.equals(null));
        assertFalse(first.equals("alice@example.com"));
        assertEquals("Alice@Example.COM", first.getOriginalAddress());
        assertEquals("alice@example.com", second.getOriginalAddress());
        assertNotEquals(first.getOriginalAddress(), second.getOriginalAddress());
        assertEquals(first.getCanonicalAddress(), second.getCanonicalAddress());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "alicesmith@example.com",
                "alice.smith+news@example.com",
                "alice.smith@example.net",
                "bob@example.com"
            })
    void doesNotMergeDotsTagsDomainsOrDifferentLocalParts(String other) {
        assertNotEquals(EmailAddress.create("alice.smith@example.com"), EmailAddress.create(other));
    }

    @Test
    void equivalentKeysDeduplicateInSetsAndFindMapValuesWithoutReplacingOriginals() {
        EmailAddress original = EmailAddress.create("Alice@Example.COM");
        EmailAddress equivalent = EmailAddress.create("alice@example.com");
        Set<EmailAddress> identities = new HashSet<>();

        assertTrue(identities.add(original));
        assertFalse(identities.add(equivalent));
        assertEquals(1, identities.size());
        assertSame(original, identities.iterator().next());
        assertEquals("value", Map.of(original, "value").get(equivalent));
        assertEquals("Alice@Example.COM", original.getOriginalAddress());
        assertEquals("alice@example.com", equivalent.getOriginalAddress());
    }

    @Test
    void acceptsLocalPartAt64CharacterLimit() {
        String original = "a".repeat(64) + "@example.com";

        assertEquals(original, EmailAddress.create(original).getOriginalAddress());
    }

    @Test
    void rejectsLocalPartAbove64CharacterLimit() {
        String original = "a".repeat(65) + "@example.com";

        assertThrows(IllegalArgumentException.class, () -> EmailAddress.create(original));
    }

    @Test
    void acceptsDomainLabelAt63CharacterLimit() {
        String original = "alice@" + "a".repeat(63) + ".example";

        assertEquals(original, EmailAddress.create(original).getOriginalAddress());
    }

    @Test
    void rejectsDomainLabelAbove63CharacterLimit() {
        String original = "alice@" + "a".repeat(64) + ".example";

        assertThrows(IllegalArgumentException.class, () -> EmailAddress.create(original));
    }

    @Test
    void acceptsCompleteAddressAt254CharacterLimit() {
        String original =
                "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);

        assertEquals(254, original.length());
        assertEquals(original, EmailAddress.create(original).getOriginalAddress());
    }

    @Test
    void rejectsCompleteAddressAbove254CharacterLimit() {
        String original =
                "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);

        assertEquals(255, original.length());
        assertThrows(IllegalArgumentException.class, () -> EmailAddress.create(original));
    }

    @ParameterizedTest
    @MethodSource("unsupportedAsciiCharacters")
    void rejectsEveryAsciiWhitespaceAndControlCharacter(char character) {
        assertThrows(
                IllegalArgumentException.class,
                () -> EmailAddress.create("ali" + character + "ce@example.com"));
    }

    private static Stream<Character> unsupportedAsciiCharacters() {
        return Stream.concat(
                IntStream.rangeClosed(0, 32).mapToObj(value -> (char) value),
                Stream.of((char) 127));
    }
}
