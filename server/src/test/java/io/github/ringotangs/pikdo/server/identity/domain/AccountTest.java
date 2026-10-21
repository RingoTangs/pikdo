package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class AccountTest {
    private static final AccountId ACCOUNT_ID =
            new AccountId(UUID.fromString("11111111-2222-4333-8444-555555555555"));
    private static final UserId USER_ID =
            new UserId(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));
    private static final EmailIdentifier EMAIL =
            new EmailIdentifier(EmailAddress.create("Alice@Example.COM"));

    @Test
    void retainsSuppliedBindingInformation() {
        Account account = Account.create(ACCOUNT_ID, USER_ID, EMAIL);

        assertSame(ACCOUNT_ID, account.getId());
        assertSame(USER_ID, account.getUserId());
        assertSame(EMAIL, account.getIdentifier());
        assertEquals("Alice@Example.COM", account.getIdentifier().getOriginalIdentifier());
        assertEquals("alice@example.com", account.getIdentifier().getCanonicalIdentifier());
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
    void rejectsNullIdentifier() {
        assertThrows(NullPointerException.class, () -> Account.create(ACCOUNT_ID, USER_ID, null));
    }

    @Test
    void newInstanceCanReferenceSameUserWithoutChangingOriginalBindingInformation() {
        Account original = Account.create(ACCOUNT_ID, USER_ID, EMAIL);
        AccountId newId = new AccountId(UUID.fromString("22222222-3333-4444-8555-666666666666"));
        EmailIdentifier newEmail = new EmailIdentifier(EmailAddress.create("New@Example.COM"));

        Account another = Account.create(newId, original.getUserId(), newEmail);

        assertNotSame(original, another);
        assertSame(USER_ID, original.getUserId());
        assertSame(USER_ID, another.getUserId());
        assertNotEquals(original.getId(), another.getId());
        assertSame(newId, another.getId());
        assertSame(newEmail, another.getIdentifier());
        assertSame(ACCOUNT_ID, original.getId());
        assertSame(EMAIL, original.getIdentifier());
        assertEquals("Alice@Example.COM", original.getIdentifier().getOriginalIdentifier());
        assertEquals("New@Example.COM", another.getIdentifier().getOriginalIdentifier());
    }

    @Test
    void refersToExistingUserIdentityWithoutManagingUserState() {
        User user = User.create(USER_ID);
        Account account = Account.create(ACCOUNT_ID, user.getId(), EMAIL);

        user.disable();
        user.restore();

        assertSame(user.getId(), account.getUserId());
        assertSame(ACCOUNT_ID, account.getId());
        assertSame(EMAIL, account.getIdentifier());
    }

    @Test
    void aUserCanBeReferencedByAnEmailAndAPhoneBinding() {
        var email = Account.create(ACCOUNT_ID, USER_ID, EMAIL);
        var identifier = new PhoneIdentifier(PhoneNumber.create("+8613812345678"));
        var phoneId = new AccountId(UUID.fromString("22222222-3333-4444-8555-666666666666"));
        var phone = Account.create(phoneId, USER_ID, identifier);
        assertEquals(email.getUserId(), phone.getUserId());
        assertNotEquals(email.getId(), phone.getId());
        assertSame(identifier, phone.getIdentifier());
        assertEquals(IdentityType.PHONE, phone.getIdentifier().getType());
        assertEquals(IdentityType.EMAIL, email.getIdentifier().getType());
    }
}
