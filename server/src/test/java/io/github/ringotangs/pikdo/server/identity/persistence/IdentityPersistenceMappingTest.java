package io.github.ringotangs.pikdo.server.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.ringotangs.pikdo.server.identity.domain.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

class IdentityPersistenceMappingTest {
    // 直接构造 Row 模拟外部或损坏数据，验证领域还原不盲目信任数据库记录。
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
        // 即使原始值本身合法，也必须重新派生并核对规范化键，防止身份错配。
        var row = new AccountRow(ID, ID, "EMAIL", "Alice@Example.COM", "bob@example.com", TIME);
        assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
    }

    @Test
    void missingStoredBindingIdentityIsRejected() {
        var row = new AccountRow(null, ID, "EMAIL", "alice@example.com", "alice@example.com", TIME);
        assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
    }

    @Test
    void credentialStringRepresentationDoesNotExposeItsHash() {
        var row = new PasswordCredentialRow(ID, "sensitive-test-hash", 0, "test-v1", TIME, TIME);
        assertFalse(row.toString().contains("sensitive-test-hash"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "email", "UNKNOWN", "PASSWORD"})
    void unknownOrMissingLoginTypesAreRejected(String type) {
        var row = new AccountRow(ID, ID, type, "alice@example.com", "alice@example.com", TIME);
        assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"+0123", "13812345678", "+86 13812345678", "+1234567890123456", "+123\n"})
    void invalidStoredPhonesCannotBecomeDomainAccounts(String phone) {
        var row = new AccountRow(ID, ID, "PHONE", phone, phone, TIME);
        assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
    }

    @Test
    void restoresTypedIdentifiersAndRejectsInconsistentOrMissingValues() {
        var emailRow =
                new AccountRow(ID, ID, "EMAIL", "Alice@Example.COM", "alice@example.com", TIME);
        var restored = AccountRepositoryImpl.fromRow(emailRow);
        assertEquals(
                new EmailIdentifier(EmailAddress.create("Alice@Example.COM")),
                restored.getIdentifier());
        assertEquals("Alice@Example.COM", restored.getIdentifier().getOriginalIdentifier());
        var phoneRow = new AccountRow(ID, ID, "PHONE", "+8613812345678", "+8613812345678", TIME);
        var phone = AccountRepositoryImpl.fromRow(phoneRow);
        assertEquals(
                new PhoneIdentifier(PhoneNumber.create("+8613812345678")), phone.getIdentifier());
        for (var row :
                java.util.List.of(
                        new AccountRow(ID, ID, "PHONE", "+8613812345678", "+8613812345679", TIME),
                        new AccountRow(ID, null, "PHONE", "+8613812345678", "+8613812345678", TIME),
                        new AccountRow(ID, ID, "PHONE", null, "+8613812345678", TIME),
                        new AccountRow(ID, ID, "PHONE", "+8613812345678", null, TIME),
                        new AccountRow(ID, ID, "EMAIL", null, "alice@example.com", TIME),
                        new AccountRow(ID, ID, "EMAIL", "alice@example.com", null, TIME))) {
            assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
        }
    }

    @Test
    void usernameRowsRestoreTheirTypedIdentityAndOriginalSpelling() {
        var row = new AccountRow(ID, ID, "USERNAME", "Alice_User", "alice_user", TIME);
        var account = AccountRepositoryImpl.fromRow(row);
        assertEquals(
                new UsernameIdentifier(Username.create("Alice_User")), account.getIdentifier());
        assertEquals("Alice_User", account.getIdentifier().getOriginalIdentifier());
        assertEquals("alice_user", account.getIdentifier().getCanonicalIdentifier());
        assertEquals(new AccountId(ID), account.getId());
        assertEquals(new UserId(ID), account.getUserId());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {"ab", "1abc", "Ali-ce", "Alice\n", "admin", "SYSTEM", "support", "A用户名"})
    void invalidStoredUsernamesAreRejected(String value) {
        var row = new AccountRow(ID, ID, "USERNAME", value, value, TIME);
        assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
    }

    @Test
    void usernameCanonicalKeyMustMatchAndBePresent() {
        for (String canonical : new String[] {null, "ALICE_USER", "other_user"}) {
            var row = new AccountRow(ID, ID, "USERNAME", "Alice_User", canonical, TIME);
            assertThrows(IllegalStateException.class, () -> AccountRepositoryImpl.fromRow(row));
        }
    }
}
