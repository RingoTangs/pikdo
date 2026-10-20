package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class AccountTest {
    private static final AccountId ACCOUNT_ID =
            new AccountId(UUID.fromString("11111111-2222-4333-8444-555555555555"));
    private static final UserId USER_ID =
            new UserId(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));
    private static final EmailAddress EMAIL = EmailAddress.create("Alice@Example.COM");

    @Test
    void retainsSuppliedBindingInformation() {
        Account account = Account.create(ACCOUNT_ID, USER_ID, EMAIL);

        assertSame(ACCOUNT_ID, account.getId());
        assertSame(USER_ID, account.getUserId());
        assertSame(EMAIL, account.getEmail());
        assertEquals("Alice@Example.COM", account.getEmail().getOriginalAddress());
        assertEquals("alice@example.com", account.getEmail().getCanonicalAddress());
    }

    @Test
    void rejectsNullAccountId() {
        assertThrows(NullPointerException.class, () -> Account.create(null, USER_ID, EMAIL));
    }

    @Test
    void rejectsNullUserId() {
        assertThrows(NullPointerException.class, () -> Account.create(ACCOUNT_ID, null, EMAIL));
    }

    @Test
    void rejectsNullEmailAddress() {
        assertThrows(NullPointerException.class, () -> Account.create(ACCOUNT_ID, USER_ID, null));
    }

    @Test
    void newInstanceCanReferenceSameUserWithoutChangingOriginalBindingInformation() {
        Account original = Account.create(ACCOUNT_ID, USER_ID, EMAIL);
        AccountId newId = new AccountId(UUID.fromString("22222222-3333-4444-8555-666666666666"));
        EmailAddress newEmail = EmailAddress.create("New@Example.COM");

        Account another = Account.create(newId, original.getUserId(), newEmail);

        assertNotSame(original, another);
        assertSame(USER_ID, original.getUserId());
        assertSame(USER_ID, another.getUserId());
        assertNotEquals(original.getId(), another.getId());
        assertSame(newId, another.getId());
        assertSame(newEmail, another.getEmail());
        assertSame(ACCOUNT_ID, original.getId());
        assertSame(EMAIL, original.getEmail());
        assertEquals("Alice@Example.COM", original.getEmail().getOriginalAddress());
        assertEquals("New@Example.COM", another.getEmail().getOriginalAddress());
    }

    @Test
    void refersToExistingUserIdentityWithoutManagingUserState() {
        User user = User.create(USER_ID);
        Account account = Account.create(ACCOUNT_ID, user.getId(), EMAIL);

        user.disable();
        user.restore();

        assertSame(user.getId(), account.getUserId());
        assertSame(ACCOUNT_ID, account.getId());
        assertSame(EMAIL, account.getEmail());
    }
}
