package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LoginIdentifierTest {
    @Test
    void emailRetainsOriginalSpellingAndExistingIdentityEquality() {
        var original = new EmailIdentifier(EmailAddress.create("Alice+News@Example.COM"));
        var equivalent = new EmailIdentifier(EmailAddress.create("alice+news@example.com"));
        assertEquals(IdentityType.EMAIL, original.getType());
        assertEquals("Alice+News@Example.COM", original.getOriginalIdentifier());
        assertEquals("alice+news@example.com", original.getCanonicalIdentifier());
        assertEquals(original, equivalent);
        assertEquals(original.hashCode(), equivalent.hashCode());
        assertNotEquals(original, new EmailIdentifier(EmailAddress.create("alice@example.com")));
    }

    @Test
    void phoneRetainsTheCanonicalValueAndType() {
        var identifier = new PhoneIdentifier(PhoneNumber.create("+8613812345678"));
        assertEquals(IdentityType.PHONE, identifier.getType());
        assertEquals("+8613812345678", identifier.getOriginalIdentifier());
        assertEquals(identifier.getOriginalIdentifier(), identifier.getCanonicalIdentifier());
        assertEquals(identifier, new PhoneIdentifier(PhoneNumber.create("+8613812345678")));
        assertNotEquals(identifier, new PhoneIdentifier(PhoneNumber.create("+8613812345679")));
    }

    @Test
    void wrappersRejectMissingValues() {
        assertThrows(NullPointerException.class, () -> new EmailIdentifier(null));
        assertThrows(NullPointerException.class, () -> new PhoneIdentifier(null));
    }
}
