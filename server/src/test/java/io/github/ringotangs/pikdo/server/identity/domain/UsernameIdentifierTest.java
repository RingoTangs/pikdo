package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UsernameIdentifierTest {
    @Test
    void exposesTypeAndOriginalAndCanonicalUsername() {
        var username = Username.create("Alice_User");
        var identifier = new UsernameIdentifier(username);
        assertSame(username, identifier.username());
        assertEquals(IdentityType.USERNAME, identifier.getType());
        assertEquals("Alice_User", identifier.getOriginalIdentifier());
        assertEquals("alice_user", identifier.getCanonicalIdentifier());
        var equivalent = new UsernameIdentifier(Username.create("ALICE_USER"));
        assertEquals(identifier, equivalent);
        assertEquals(identifier.hashCode(), equivalent.hashCode());
        assertNotEquals(identifier, new UsernameIdentifier(Username.create("Other_User")));
    }

    @Test
    void rejectsMissingUsername() {
        assertThrows(NullPointerException.class, () -> new UsernameIdentifier(null));
    }
}
