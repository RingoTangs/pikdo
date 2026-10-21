package io.github.ringotangs.pikdo.server.identity.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.ringotangs.pikdo.server.Application;
import io.github.ringotangs.pikdo.server.identity.domain.Account;
import io.github.ringotangs.pikdo.server.identity.domain.AccountId;
import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;
import io.github.ringotangs.pikdo.server.identity.domain.User;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.domain.UserStatus;
import io.github.ringotangs.pikdo.server.shared.id.UuidV7Generator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Uses production mapper discovery, not the foundation test's explicitly registered probe. */
@SpringBootTest(classes = Application.class)
@Import(IdentityPersistenceIT.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdentityPersistenceIT {
    @Autowired private UserMapper users;
    @Autowired private AccountMapper accounts;
    @Autowired private PasswordCredentialMapper credentials;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private UserRepository userRepository;
    private AccountRepository accountRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6")
                    .withDatabaseName("pikdo_persistence_test")
                    .withUsername("persistence_test")
                    .withPassword(UUID.randomUUID().toString());
        }
    }

    @BeforeEach
    void initializeRepositoriesAndCheckIsolation() {
        userRepository = new UserRepository(users);
        accountRepository = new AccountRepository(accounts);
        for (String table :
                List.of("identity_accounts", "identity_password_credentials", "identity_users")) {
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
    }

    @AfterEach
    void clearOnlyThisDisposableDatabase() {
        jdbc.update("DELETE FROM identity_accounts");
        jdbc.update("DELETE FROM identity_password_credentials");
        jdbc.update("DELETE FROM identity_users");
    }

    @Test
    void createsAndRestoresAnActiveUserAndItsDatabaseAuditFields() {
        var user = newUser();
        var restored = userRepository.findById(user.getId()).orElseThrow();
        var row = users.findById(user.getId().value()).orElseThrow();
        assertEquals(user.getId(), restored.getId());
        assertEquals(UserStatus.ACTIVE, restored.getStatus());
        assertEquals(user.getId().value(), row.id());
        assertEquals("ACTIVE", row.status());
        assertNotNull(row.createdAt());
        assertNotNull(row.updatedAt());
    }

    @Test
    void canCreateADisabledUserWithoutChangingTheDomainContract() {
        var user = User.create(new UserId(UUID.randomUUID()));
        user.disable();
        userRepository.insert(user);
        assertEquals(
                UserStatus.DISABLED,
                userRepository.findById(user.getId()).orElseThrow().getStatus());
    }

    @Test
    void statusUpdatesRestoreDisabledThenActiveAndPreserveCreationTime() {
        var user = newUser();
        var before = users.findById(user.getId().value()).orElseThrow();
        jdbc.update(
                "UPDATE identity_users SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z' WHERE id = ?",
                user.getId().value());
        user.disable();
        userRepository.updateStatus(user);
        var disabled = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(UserStatus.DISABLED, disabled.getStatus());
        var after = users.findById(user.getId().value()).orElseThrow();
        assertEquals(before.createdAt().toInstant(), after.createdAt().toInstant());
        assertTrue(after.updatedAt().isAfter(OffsetDateTime.parse("2000-01-01T00:00:00Z")));
        disabled.restore();
        userRepository.updateStatus(disabled);
        assertEquals(
                UserStatus.ACTIVE, userRepository.findById(user.getId()).orElseThrow().getStatus());
    }

    @Test
    void queriesForAbsentRowsReturnEmptyAndStatusUpdatesDoNotPretendToSucceed() {
        var id = new UserId(UUID.randomUUID());
        assertTrue(users.findById(id.value()).isEmpty());
        assertTrue(userRepository.findById(id).isEmpty());
        assertEquals(0, users.updateStatus(id.value(), "DISABLED"));
        assertThrows(
                IllegalStateException.class, () -> userRepository.updateStatus(User.create(id)));
        var accountId = new AccountId(UUID.randomUUID());
        assertTrue(accounts.findById(accountId.value()).isEmpty());
        assertTrue(accountRepository.findById(accountId).isEmpty());
        assertTrue(
                accountRepository.findByEmail(EmailAddress.create("absent@example.com")).isEmpty());
        assertTrue(credentials.findByUserId(id.value()).isEmpty());
        assertEquals(
                0, credentials.updateIfVersionMatches(id.value(), 0, "test-only-hash", "test-v1"));
    }

    @Test
    void createsBindingAndQueriesItByIdAndCanonicalIdentity() {
        var user = newUser();
        var account = newAccount(user, "Alice+News@Example.COM");
        var byId = accountRepository.findById(account.getId()).orElseThrow();
        var byEmail =
                accountRepository
                        .findByEmail(EmailAddress.create("alice+news@example.com"))
                        .orElseThrow();
        var row = accounts.findById(account.getId().value()).orElseThrow();
        assertEquals(account.getId(), byId.getId());
        assertEquals(user.getId(), byId.getUserId());
        assertEquals(account.getId(), byEmail.getId());
        assertEquals("Alice+News@Example.COM", byEmail.getEmail().getOriginalAddress());
        assertEquals("alice+news@example.com", byEmail.getEmail().getCanonicalAddress());
        assertEquals(row, accounts.findByCanonicalAddress("alice+news@example.com").orElseThrow());
        assertEquals(account.getId().value(), row.id());
        assertEquals(user.getId().value(), row.userId());
        assertEquals("Alice+News@Example.COM", row.originalAddress());
        assertEquals("alice+news@example.com", row.canonicalAddress());
        assertNotNull(row.createdAt());
        assertTrue(accounts.findByCanonicalAddress("ALICE+NEWS@example.com").isEmpty());
    }

    @Test
    void doesNotMergeDotsTagsOrDifferentDomainsWhenQuerying() {
        var account = newAccount(newUser(), "Alice.Smith+News@Example.COM");
        assertEquals(
                account.getId(),
                accountRepository
                        .findByEmail(EmailAddress.create("alice.smith+news@example.com"))
                        .orElseThrow()
                        .getId());
        for (String other :
                List.of(
                        "alicesmith+news@example.com",
                        "alice.smith@example.com",
                        "alice.smith+news@example.org")) {
            assertTrue(accountRepository.findByEmail(EmailAddress.create(other)).isEmpty());
        }
    }

    @Test
    void duplicateUserAndBindingIdentitiesPropagateDatabaseErrors() {
        var user = newUser();
        assertThrows(
                DuplicateKeyException.class,
                () -> userRepository.insert(User.create(user.getId())));
        var account = newAccount(user, "Alice@Example.COM");
        assertThrows(DuplicateKeyException.class, () -> accountRepository.insert(account));
        var other = newUser();
        assertThrows(DuplicateKeyException.class, () -> newAccount(other, "alice@example.com"));
        assertThrows(DuplicateKeyException.class, () -> newAccount(user, "different@example.com"));
        assertEquals(
                "Alice@Example.COM",
                accountRepository
                        .findById(account.getId())
                        .orElseThrow()
                        .getEmail()
                        .getOriginalAddress());
    }

    @Test
    void invalidForeignKeysAreRejectedThroughRealMappers() {
        var missing = User.create(new UserId(UUID.randomUUID()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> newAccount(missing, "alice@example.com"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> credentials.insert(missing.getId().value(), "test-only-hash", "test-v1"));
    }

    @Test
    void databaseChecksStillApplyToDirectMapperCalls() {
        var user = newUser();
        assertThrows(
                DataIntegrityViolationException.class,
                () -> users.updateStatus(user.getId().value(), "PENDING"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        accounts.insert(
                                UUID.randomUUID(),
                                user.getId().value(),
                                "Alice@example.com",
                                "bob@example.com"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> credentials.insert(user.getId().value(), "test-only-hash", ""));
    }

    @Test
    void credentialRoundTripIsUserOwnedAndBindsHashAsData() {
        var user = newUser();
        String hash = "test-hash'); DROP TABLE identity_users; --";
        assertEquals(1, credentials.insert(user.getId().value(), hash, "test-input-v1"));
        var row = credentials.findByUserId(user.getId().value()).orElseThrow();
        assertEquals(user.getId().value(), row.userId());
        assertEquals(hash, row.passwordHash());
        assertEquals(0, row.authenticationVersion());
        assertEquals("test-input-v1", row.passwordInputVersion());
        assertNotNull(row.createdAt());
        assertNotNull(row.updatedAt());
        assertTrue(userRepository.findById(user.getId()).isPresent());
        assertThrows(
                DuplicateKeyException.class,
                () -> credentials.insert(user.getId().value(), "other-test-hash", "test-input-v1"));
    }

    @Test
    void matchingVersionAtomicallyChangesHashInputVersionAndAuditTime() {
        var user = newUser();
        credentials.insert(user.getId().value(), "test-old-hash", "test-input-v1");
        jdbc.update(
                "UPDATE identity_password_credentials SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z' WHERE user_id = ?",
                user.getId().value());
        var before = credentials.findByUserId(user.getId().value()).orElseThrow();
        assertEquals(
                1,
                credentials.updateIfVersionMatches(
                        user.getId().value(), 0, "test-new-hash", "test-input-v2"));
        var after = credentials.findByUserId(user.getId().value()).orElseThrow();
        assertEquals("test-new-hash", after.passwordHash());
        assertEquals("test-input-v2", after.passwordInputVersion());
        assertEquals(1, after.authenticationVersion());
        assertEquals(before.createdAt().toInstant(), after.createdAt().toInstant());
        assertTrue(after.updatedAt().isAfter(before.updatedAt()));
        assertEquals(
                1,
                credentials.updateIfVersionMatches(
                        user.getId().value(), 1, "test-next-hash", "test-input-v2"));
        assertEquals(
                2,
                credentials
                        .findByUserId(user.getId().value())
                        .orElseThrow()
                        .authenticationVersion());
    }

    @Test
    void staleVersionCannotChangeAnyCredentialField() {
        var user = newUser();
        credentials.insert(user.getId().value(), "test-old-hash", "test-input-v1");
        var before = credentials.findByUserId(user.getId().value()).orElseThrow();
        assertEquals(
                0,
                credentials.updateIfVersionMatches(
                        user.getId().value(), 7, "test-overwrite", "test-input-v2"));
        assertEquals(before, credentials.findByUserId(user.getId().value()).orElseThrow());
    }

    @Test
    void rollbackRemovesEarlierMapperWritesOnBindingConflict() {
        newAccount(newUser(), "taken@example.com");
        var id = new UserId(UUID.randomUUID());
        assertThrows(
                DuplicateKeyException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .executeWithoutResult(
                                        status -> {
                                            var user = User.create(id);
                                            userRepository.insert(user);
                                            credentials.insert(
                                                    id.value(), "test-only-hash", "test-input-v1");
                                            newAccount(user, "taken@example.com");
                                        }));
        assertTrue(userRepository.findById(id).isEmpty());
        assertTrue(credentials.findByUserId(id.value()).isEmpty());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
    }

    @Test
    void rollbackRestoresHashAndVersionAfterAConditionalUpdate() {
        var user = newUser();
        credentials.insert(user.getId().value(), "test-old-hash", "test-input-v1");
        var before = credentials.findByUserId(user.getId().value()).orElseThrow();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            assertEquals(
                                    1,
                                    credentials.updateIfVersionMatches(
                                            user.getId().value(),
                                            0,
                                            "test-new-hash",
                                            "test-input-v2"));
                            status.setRollbackOnly();
                        });
        assertEquals(before, credentials.findByUserId(user.getId().value()).orElseThrow());
    }

    @Test
    void aDatabaseRowOutsideTheFullEmailGrammarCannotBecomeADomainAccount() {
        var user = newUser();
        var id = UUID.randomUUID();
        assertEquals(1, accounts.insert(id, user.getId().value(), "not-an-email", "not-an-email"));
        assertThrows(
                IllegalStateException.class, () -> accountRepository.findById(new AccountId(id)));
    }

    @Test
    void onlyOneConcurrentUpdateWithTheSameExpectedVersionSucceeds() throws Exception {
        var user = newUser();
        credentials.insert(user.getId().value(), "test-old-hash", "test-input-v1");
        var before = credentials.findByUserId(user.getId().value()).orElseThrow();
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> compete(user.getId(), start, "test-hash-a"));
            var second = workers.submit(() -> compete(user.getId(), start, "test-hash-b"));
            assertEquals(1, first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS));
        }
        var after = credentials.findByUserId(user.getId().value()).orElseThrow();
        assertEquals(1, after.authenticationVersion());
        assertTrue(List.of("test-hash-a", "test-hash-b").contains(after.passwordHash()));
        assertEquals("test-input-v2", after.passwordInputVersion());
        assertEquals(before.createdAt().toInstant(), after.createdAt().toInstant());
    }

    @Test
    void uuidV7IdsRoundTripWithoutGeneratingSeparateForeignOrSharedKeys() {
        var userId = new UserId(UuidV7Generator.generate());
        var accountId = new AccountId(UuidV7Generator.generate());
        assertEquals(7, userId.value().version());
        assertEquals(7, accountId.value().version());
        assertNotEquals(userId.value(), accountId.value());
        userRepository.insert(User.create(userId));
        var account = Account.create(accountId, userId, EmailAddress.create("V7@Example.COM"));
        accountRepository.insert(account);
        assertEquals(1, credentials.insert(userId.value(), "test-v7-hash", "test-input-v1"));

        assertEquals(userId, userRepository.findById(userId).orElseThrow().getId());
        var restored = accountRepository.findById(accountId).orElseThrow();
        assertEquals(accountId, restored.getId());
        assertEquals(userId, restored.getUserId());
        assertEquals("V7@Example.COM", restored.getEmail().getOriginalAddress());
        assertEquals("v7@example.com", restored.getEmail().getCanonicalAddress());
        assertEquals(userId.value(), users.findById(userId.value()).orElseThrow().id());
        assertEquals(accountId.value(), accounts.findById(accountId.value()).orElseThrow().id());
        assertEquals(
                userId.value(), credentials.findByUserId(userId.value()).orElseThrow().userId());
    }

    @Test
    void legacyUuidV4IdsRemainReadableWithoutConversionOrVersionRejection() {
        var userId = new UserId(UUID.fromString("11111111-2222-4333-8444-555555555555"));
        var accountId = new AccountId(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"));
        assertEquals(4, userId.value().version());
        assertEquals(4, accountId.value().version());
        userRepository.insert(User.create(userId));
        accountRepository.insert(
                Account.create(accountId, userId, EmailAddress.create("Legacy@Example.COM")));
        credentials.insert(userId.value(), "test-legacy-hash", "test-input-v1");

        assertEquals(userId, userRepository.findById(userId).orElseThrow().getId());
        var restored =
                accountRepository
                        .findByEmail(EmailAddress.create("legacy@example.com"))
                        .orElseThrow();
        assertEquals(accountId, restored.getId());
        assertEquals(userId, restored.getUserId());
        assertEquals(accountId, accountRepository.findById(accountId).orElseThrow().getId());
        assertEquals(userId.value(), users.findById(userId.value()).orElseThrow().id());
        assertEquals(accountId.value(), accounts.findById(accountId.value()).orElseThrow().id());
        assertEquals(
                userId.value(), credentials.findByUserId(userId.value()).orElseThrow().userId());
    }

    private int compete(UserId user, CyclicBarrier start, String hash) {
        return new TransactionTemplate(transactions)
                .execute(
                        status -> {
                            try {
                                start.await(10, TimeUnit.SECONDS);
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                            return credentials.updateIfVersionMatches(
                                    user.value(), 0, hash, "test-input-v2");
                        });
    }

    private User newUser() {
        var user = User.create(new UserId(UUID.randomUUID()));
        userRepository.insert(user);
        return user;
    }

    private Account newAccount(User user, String address) {
        var account =
                Account.create(
                        new AccountId(UUID.randomUUID()),
                        user.getId(),
                        EmailAddress.create(address));
        accountRepository.insert(account);
        return account;
    }
}
