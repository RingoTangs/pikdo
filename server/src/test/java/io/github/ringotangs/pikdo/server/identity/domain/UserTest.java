package io.github.ringotangs.pikdo.server.identity.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class UserTest {
    private static final UserId USER_ID =
            new UserId(UUID.fromString("11111111-2222-4333-8444-555555555555"));

    @Test
    void createsActiveUserWithSuppliedIdentity() {
        User user = User.create(USER_ID);

        assertSame(USER_ID, user.getId());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
    }

    @Test
    void rejectsNullIdentity() {
        assertThrows(NullPointerException.class, () -> User.create(null));
    }

    @Test
    void disablesActiveUserWithoutChangingIdentity() {
        User user = User.create(USER_ID);

        user.disable();

        assertEquals(UserStatus.DISABLED, user.getStatus());
        assertSame(USER_ID, user.getId());
    }

    @Test
    void restoresDisabledUserWithoutChangingIdentity() {
        User user = User.create(USER_ID);
        user.disable();

        user.restore();

        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertSame(USER_ID, user.getId());
    }

    @Test
    void disablingDisabledUserIsIdempotent() {
        User user = User.create(USER_ID);
        user.disable();

        user.disable();

        assertEquals(UserStatus.DISABLED, user.getStatus());
        assertSame(USER_ID, user.getId());
    }

    @Test
    void restoringActiveUserIsIdempotent() {
        User user = User.create(USER_ID);

        user.restore();
        user.restore();

        assertEquals(UserStatus.ACTIVE, user.getStatus());
        assertSame(USER_ID, user.getId());
    }

    @Test
    void repeatedLifecycleTransitionsPreserveIdentity() {
        // 禁用和恢复只改变状态，不更换业务身份，也不创建新用户。
        User user = User.create(USER_ID);

        for (int cycle = 0; cycle < 3; cycle++) {
            user.disable();
            assertEquals(UserStatus.DISABLED, user.getStatus());
            assertSame(USER_ID, user.getId());

            user.restore();
            assertEquals(UserStatus.ACTIVE, user.getStatus());
            assertSame(USER_ID, user.getId());
        }
    }

    @Test
    void separateInstancesHaveIndependentStateEvenWithSameIdentity() {
        User first = User.create(USER_ID);
        User second = User.create(USER_ID);
        assertNotSame(first, second);

        first.disable();
        assertEquals(UserStatus.DISABLED, first.getStatus());
        assertEquals(UserStatus.ACTIVE, second.getStatus());

        second.disable();
        first.restore();
        assertEquals(UserStatus.ACTIVE, first.getStatus());
        assertEquals(UserStatus.DISABLED, second.getStatus());
        assertSame(USER_ID, first.getId());
        assertSame(USER_ID, second.getId());
    }
}
