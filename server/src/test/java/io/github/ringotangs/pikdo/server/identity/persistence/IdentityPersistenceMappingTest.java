package io.github.ringotangs.pikdo.server.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

class IdentityPersistenceMappingTest {
    private static final UUID ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final OffsetDateTime TIME =
            OffsetDateTime.of(2026, 10, 21, 0, 0, 0, 0, ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "active", ""})
    void unknownStoredUserStatesAreNotRestoredAsActive(String status) {
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> UserRepositoryImpl.fromRow(new UserRow(ID, status, TIME, TIME)));
        assertNotNull(failure.getCause());
    }

    @Test
    void missingStoredUserIdentityIsNotSilentlyAccepted() {
        assertThrows(
                IllegalStateException.class,
                () -> UserRepositoryImpl.fromRow(new UserRow(null, "ACTIVE", TIME, TIME)));
    }

    @Test
    void missingStoredUserStateIsNotSilentlyAccepted() {
        assertThrows(
                IllegalStateException.class,
                () -> UserRepositoryImpl.fromRow(new UserRow(ID, null, TIME, TIME)));
    }

    @Test
    void canonicalKeyMustAgreeWithTheOriginalEvenIfTheRowCameFromAnotherSource() {
        var row = new AccountRow(ID, ID, "Alice@Example.COM", "bob@example.com", TIME);
        assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
    }

    @Test
    void missingStoredBindingIdentityIsRejected() {
        var row = new AccountRow(null, ID, "alice@example.com", "alice@example.com", TIME);
        assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
    }

    @Test
    void credentialStringRepresentationDoesNotExposeItsHash() {
        var row = new PasswordCredentialRow(ID, "sensitive-test-hash", 0, "test-v1", TIME, TIME);
        assertFalse(row.toString().contains("sensitive-test-hash"));
    }
}
