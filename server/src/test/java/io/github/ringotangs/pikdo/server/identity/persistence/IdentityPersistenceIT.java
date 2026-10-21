package io.github.ringotangs.pikdo.server.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.ringotangs.pikdo.server.Application;
import io.github.ringotangs.pikdo.server.identity.domain.Account;
import io.github.ringotangs.pikdo.server.identity.domain.AccountId;
import io.github.ringotangs.pikdo.server.identity.domain.AccountRepository;
import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;
import io.github.ringotangs.pikdo.server.identity.domain.EmailIdentifier;
import io.github.ringotangs.pikdo.server.identity.domain.IdentityType;
import io.github.ringotangs.pikdo.server.identity.domain.LoginIdentifier;
import io.github.ringotangs.pikdo.server.identity.domain.PasswordCredential;
import io.github.ringotangs.pikdo.server.identity.domain.PasswordCredentialRepository;
import io.github.ringotangs.pikdo.server.identity.domain.PhoneIdentifier;
import io.github.ringotangs.pikdo.server.identity.domain.PhoneNumber;
import io.github.ringotangs.pikdo.server.identity.domain.User;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.domain.UserRepository;
import io.github.ringotangs.pikdo.server.identity.domain.UserStatus;
import io.github.ringotangs.pikdo.server.identity.domain.Username;
import io.github.ringotangs.pikdo.server.identity.domain.UsernameIdentifier;
import io.github.ringotangs.pikdo.server.shared.id.UuidV7Generator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    private PasswordCredentialRepository credentialRepository;

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
        // Mapper 使用生产扫描规则；Repository 仍手动装配，不额外注册 Spring Bean。
        userRepository = new UserRepositoryImpl(users);
        accountRepository = new AccountRepositoryImpl(accounts);
        credentialRepository = new PasswordCredentialRepositoryImpl(credentials);
        for (String table :
                List.of("identity_accounts", "identity_password_credentials", "identity_users")) {
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
    }

    @AfterEach
    void clearOnlyThisDisposableDatabase() {
        // 仅清理本测试容器的数据，按外键依赖顺序删除，不接触开发数据库。
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
        var user = User.create(new UserId(UuidV7Generator.generate()));
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
        assertTrue(accountRepository.findByIdentifier(email("absent@example.com")).isEmpty());
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
                accountRepository.findByIdentifier(email("alice+news@example.com")).orElseThrow();
        var row = accounts.findById(account.getId().value()).orElseThrow();
        assertEquals(account.getId(), byId.getId());
        assertEquals(user.getId(), byId.getUserId());
        assertEquals(account.getId(), byEmail.getId());
        assertEquals("Alice+News@Example.COM", byEmail.getIdentifier().getOriginalIdentifier());
        assertEquals("alice+news@example.com", byEmail.getIdentifier().getCanonicalIdentifier());
        assertEquals(
                row, accounts.findByIdentifier("EMAIL", "alice+news@example.com").orElseThrow());
        assertEquals(account.getId().value(), row.id());
        assertEquals(user.getId().value(), row.userId());
        assertEquals("Alice+News@Example.COM", row.originalIdentifier());
        assertEquals("alice+news@example.com", row.canonicalIdentifier());
        assertNotNull(row.createdAt());
        assertTrue(accounts.findByIdentifier("EMAIL", "ALICE+NEWS@example.com").isEmpty());
    }

    @Test
    void doesNotMergeDotsTagsOrDifferentDomainsWhenQuerying() {
        var account = newAccount(newUser(), "Alice.Smith+News@Example.COM");
        assertEquals(
                account.getId(),
                accountRepository
                        .findByIdentifier(email("alice.smith+news@example.com"))
                        .orElseThrow()
                        .getId());
        for (String other :
                List.of(
                        "alicesmith+news@example.com",
                        "alice.smith@example.com",
                        "alice.smith+news@example.org")) {
            assertTrue(accountRepository.findByIdentifier(email(other)).isEmpty());
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
                        .getIdentifier()
                        .getOriginalIdentifier());
    }

    @Test
    void invalidForeignKeysAreRejectedThroughRealMappers() {
        var missing = User.create(new UserId(UuidV7Generator.generate()));
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
                                "EMAIL",
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
        // SQL 只保证基础存储约束；完整邮箱语法仍须在还原领域对象时校验。
        var user = newUser();
        var id = UUID.randomUUID();
        assertEquals(
                1,
                accounts.insert(id, user.getId().value(), "EMAIL", "not-an-email", "not-an-email"));
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
        var account = Account.create(accountId, userId, email("V7@Example.COM"));
        accountRepository.insert(account);
        assertEquals(1, credentials.insert(userId.value(), "test-v7-hash", "test-input-v1"));

        assertEquals(userId, userRepository.findById(userId).orElseThrow().getId());
        var restored = accountRepository.findById(accountId).orElseThrow();
        assertEquals(accountId, restored.getId());
        assertEquals(userId, restored.getUserId());
        assertEquals("V7@Example.COM", restored.getIdentifier().getOriginalIdentifier());
        assertEquals("v7@example.com", restored.getIdentifier().getCanonicalIdentifier());
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
        accountRepository.insert(Account.create(accountId, userId, email("Legacy@Example.COM")));
        credentials.insert(userId.value(), "test-legacy-hash", "test-input-v1");

        assertEquals(userId, userRepository.findById(userId).orElseThrow().getId());
        var restored =
                accountRepository.findByIdentifier(email("legacy@example.com")).orElseThrow();
        assertEquals(accountId, restored.getId());
        assertEquals(userId, restored.getUserId());
        assertEquals(accountId, accountRepository.findById(accountId).orElseThrow().getId());
        assertEquals(userId.value(), users.findById(userId.value()).orElseThrow().id());
        assertEquals(accountId.value(), accounts.findById(accountId.value()).orElseThrow().id());
        assertEquals(
                userId.value(), credentials.findByUserId(userId.value()).orElseThrow().userId());
    }

    @Test
    void emailAndPhoneRoundTripShareOneUserOwnedPassword() {
        // 密码凭据归 UserId 所有，增加登录身份不会复制或拆分密码。
        var user = newUser();
        assertTrue(accountRepository.findByUserId(user.getId()).isEmpty());
        assertTrue(
                accountRepository.findByUserId(new UserId(UuidV7Generator.generate())).isEmpty());
        // Insert PHONE first to verify the documented query ordering.
        var phone = bind(user, new PhoneIdentifier(PhoneNumber.create("+8613812345678")));
        var email = newAccount(user, "Alice@Example.COM");
        var bindings = accountRepository.findByUserId(user.getId());
        assertEquals(
                List.of(email.getId(), phone.getId()),
                bindings.stream().map(Account::getId).toList());
        assertEquals(
                List.of(IdentityType.EMAIL, IdentityType.PHONE),
                bindings.stream().map(a -> a.getIdentifier().getType()).toList());
        for (var binding : bindings) {
            assertEquals(user.getId(), binding.getUserId());
            assertEquals(
                    binding.getIdentifier(),
                    accountRepository.findById(binding.getId()).orElseThrow().getIdentifier());
            assertEquals(
                    binding.getId(),
                    accountRepository
                            .findByIdentifier(binding.getIdentifier())
                            .orElseThrow()
                            .getId());
            assertEquals(7, binding.getId().value().version());
        }
        assertEquals("Alice@Example.COM", bindings.get(0).getIdentifier().getOriginalIdentifier());
        assertEquals("+8613812345678", bindings.get(1).getIdentifier().getOriginalIdentifier());
        assertTrue(
                accountRepository
                        .findByIdentifier(new PhoneIdentifier(PhoneNumber.create("+14155552671")))
                        .isEmpty());
        assertEquals(1, credentials.insert(user.getId().value(), "test-shared-hash", "test-v1"));
        var before = credentials.findByUserId(user.getId().value()).orElseThrow();
        for (var binding : bindings) {
            var owner =
                    accountRepository
                            .findByIdentifier(binding.getIdentifier())
                            .orElseThrow()
                            .getUserId();
            assertEquals(before, credentials.findByUserId(owner.value()).orElseThrow());
        }
        assertThrows(
                DuplicateKeyException.class,
                () -> credentials.insert(user.getId().value(), "test-other-hash", "test-v1"));
        assertEquals(
                1,
                credentials.updateIfVersionMatches(
                        user.getId().value(), 0, "test-replacement-hash", "test-v2"));
        for (var binding : bindings) {
            var credential = credentials.findByUserId(binding.getUserId().value()).orElseThrow();
            assertEquals("test-replacement-hash", credential.passwordHash());
            assertEquals(1, credential.authenticationVersion());
        }
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_password_credentials", Long.class));
    }

    @ParameterizedTest
    @EnumSource(IdentityType.class)
    void eachTypeHasPerUserAndGlobalUniqueness(IdentityType type) {
        // 分别验证同用户同类型只能一条，以及同类型规范化身份不能跨用户占用。
        var owner = newUser();
        var other = newUser();
        var first = identifier(type, false);
        var stored = bind(owner, first);
        assertThrows(DuplicateKeyException.class, () -> bind(owner, identifier(type, true)));
        assertThrows(DuplicateKeyException.class, () -> bind(other, first));
        assertEquals(
                stored.getId(), accountRepository.findByIdentifier(first).orElseThrow().getId());
        assertEquals(1, accountRepository.findByUserId(owner.getId()).size());
        assertTrue(accountRepository.findByUserId(other.getId()).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(IdentityType.class)
    void foreignKeysAndTransactionRollbackApplyToAllTypes(IdentityType type) {
        // 最后一次绑定故意冲突，前面写入的用户、凭据和另一类型绑定必须一起回滚。
        var taken = identifier(type, false);
        bind(newUser(), taken);
        var missing = User.create(new UserId(UuidV7Generator.generate()));
        assertThrows(
                DataIntegrityViolationException.class, () -> bind(missing, identifier(type, true)));
        assertThrows(
                DuplicateKeyException.class,
                () ->
                        new TransactionTemplate(transactions)
                                .executeWithoutResult(
                                        status -> {
                                            userRepository.insert(missing);
                                            credentials.insert(
                                                    missing.getId().value(),
                                                    "test-rollback-hash",
                                                    "test-v1");
                                            bind(
                                                    missing,
                                                    identifier(
                                                            type == IdentityType.EMAIL
                                                                    ? IdentityType.PHONE
                                                                    : IdentityType.EMAIL,
                                                            true));
                                            bind(missing, taken);
                                        }));
        assertTrue(userRepository.findById(missing.getId()).isEmpty());
        assertTrue(accountRepository.findByUserId(missing.getId()).isEmpty());
        assertTrue(credentials.findByUserId(missing.getId().value()).isEmpty());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
    }

    @ParameterizedTest
    @CsvSource({
        "EMAIL,false",
        "PHONE,false",
        "USERNAME,false",
        "EMAIL,true",
        "PHONE,true",
        "USERNAME,true"
    })
    void concurrentBindingCommitsOnlyOneWinner(IdentityType type, boolean sameUser)
            throws Exception {
        var first = newUser();
        var second = sameUser ? first : newUser();
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> competeForBinding(first, identifier(type, false), start));
            // Same owner with distinct values exercises (user_id, identity_type).
            var b =
                    workers.submit(
                            () ->
                                    competeForBinding(
                                            second,
                                            type == IdentityType.USERNAME && !sameUser
                                                    ? new UsernameIdentifier(
                                                            Username.create("aLiCe_uSeR"))
                                                    : identifier(type, sameUser),
                                            start));
            assertEquals(1, a.get(30, TimeUnit.SECONDS) + b.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
        var row =
                jdbc.queryForMap(
                        "SELECT user_id, identity_type, canonical_identifier FROM identity_accounts");
        assertEquals(type.name(), row.get("identity_type"));
        assertTrue(
                List.of(first.getId().value(), second.getId().value())
                        .contains(row.get("user_id")));
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
                "+１２３４",
                "+1234567890123456",
                "+123\n",
                "+123\r",
                "+123\t"
            })
    void databaseRejectsInvalidPhoneSyntaxEvenThroughDirectMapper(String value) {
        var user = newUser();
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        accounts.insert(
                                UuidV7Generator.generate(),
                                user.getId().value(),
                                "PHONE",
                                value,
                                value));
    }

    @Test
    void databaseRequiresExplicitKnownTypeAndConsistentPhoneValues() {
        var user = newUser();
        for (String type : List.of("", "phone", "UNKNOWN")) {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () ->
                            accounts.insert(
                                    UuidV7Generator.generate(),
                                    user.getId().value(),
                                    type,
                                    "+123",
                                    "+123"));
        }
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        accounts.insert(
                                UuidV7Generator.generate(),
                                user.getId().value(),
                                null,
                                "+123",
                                "+123"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        accounts.insert(
                                UuidV7Generator.generate(),
                                user.getId().value(),
                                "PHONE",
                                "+123",
                                "+124"));
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "INSERT INTO identity_accounts (id, user_id, original_identifier, canonical_identifier) VALUES (?, ?, '+123', '+123')",
                                UuidV7Generator.generate(),
                                user.getId().value()));
        assertEquals(
                1,
                accounts.insert(
                        UuidV7Generator.generate(),
                        user.getId().value(),
                        "PHONE",
                        "+123456789012345",
                        "+123456789012345"));
    }

    @Test
    void identifierQueryTreatsSqlTextAsDataAndIncludesTheType() {
        newAccount(newUser(), "Alice@Example.COM");
        assertTrue(accounts.findByIdentifier("EMAIL", "alice@example.com' OR '1'='1").isEmpty());
        assertTrue(accounts.findByIdentifier("PHONE", "alice@example.com").isEmpty());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
    }

    @Test
    void credentialRepositoryCreatesRestoresAndEnforcesOneCredentialPerUser() {
        var user = newUser();
        assertTrue(credentialRepository.findByUserId(user.getId()).isEmpty());
        String hash = "test-hash'); DROP TABLE identity_users; --";
        var initial = PasswordCredential.create(user.getId(), hash, "test-v1");
        credentialRepository.insert(initial);
        var stored = credentialRepository.findByUserId(user.getId()).orElseThrow();
        assertCredential(stored, user.getId(), hash, 0, "test-v1");
        assertEquals("PasswordCredential[redacted]", stored.toString());
        assertThrows(DuplicateKeyException.class, () -> credentialRepository.insert(initial));
        var missing = new UserId(UuidV7Generator.generate());
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        credentialRepository.insert(
                                PasswordCredential.create(missing, "test-hash", "test-v1")));
        assertTrue(credentialRepository.findByUserId(missing).isEmpty());
        assertFalse(
                credentialRepository.updateIfVersionMatches(
                        missing, 0, "test-new-hash", "test-v2"));
        assertTrue(userRepository.findById(user.getId()).isPresent());
    }

    @Test
    void credentialRepositoryIsSharedByEmailAndPhoneBindings() {
        var user = newUser();
        var email = newAccount(user, "Shared@Example.COM");
        var phone = bind(user, new PhoneIdentifier(PhoneNumber.create("+8613812345678")));
        credentialRepository.insert(
                PasswordCredential.create(user.getId(), "test-shared-hash", "test-v1"));
        assertTrue(
                credentialRepository.updateIfVersionMatches(
                        user.getId(), 0, "test-updated-hash", "test-v2"));
        for (var binding : List.of(email, phone)) {
            var owner =
                    accountRepository
                            .findByIdentifier(binding.getIdentifier())
                            .orElseThrow()
                            .getUserId();
            assertCredential(
                    credentialRepository.findByUserId(owner).orElseThrow(),
                    user.getId(),
                    "test-updated-hash",
                    1,
                    "test-v2");
        }
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_password_credentials", Long.class));
    }

    @Test
    void credentialRepositoryUpdatesOnlyTheExpectedVersionAndPreservesAuditFieldsOnConflict() {
        var user = newUser();
        var initial = PasswordCredential.create(user.getId(), "test-original-hash", "test-v1");
        credentialRepository.insert(initial);
        jdbc.update(
                "UPDATE identity_password_credentials SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00Z' WHERE user_id = ?",
                user.getId().value());
        var before = credentials.findByUserId(user.getId().value()).orElseThrow();
        assertTrue(
                credentialRepository.updateIfVersionMatches(
                        user.getId(), 0, "test-new-hash", "test-v2"));
        var updated = credentialRepository.findByUserId(user.getId()).orElseThrow();
        assertCredential(updated, user.getId(), "test-new-hash", 1, "test-v2");
        assertCredential(initial, user.getId(), "test-original-hash", 0, "test-v1");
        var row = credentials.findByUserId(user.getId().value()).orElseThrow();
        assertEquals(before.createdAt().toInstant(), row.createdAt().toInstant());
        assertTrue(row.updatedAt().isAfter(before.updatedAt()));
        assertFalse(
                credentialRepository.updateIfVersionMatches(
                        user.getId(), 0, "test-stale-hash", "test-v3"));
        assertEquals(row, credentials.findByUserId(user.getId().value()).orElseThrow());
        assertTrue(
                credentialRepository.updateIfVersionMatches(
                        user.getId(), 1, "test-next-hash", "test-v3"));
        assertCredential(
                credentialRepository.findByUserId(user.getId()).orElseThrow(),
                user.getId(),
                "test-next-hash",
                2,
                "test-v3");
    }

    @Test
    void credentialRepositoryInsertAndUpdateParticipateInTheCallerTransaction() {
        var user = newUser();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            credentialRepository.insert(
                                    PasswordCredential.create(
                                            user.getId(), "test-rolled-back-hash", "test-v1"));
                            assertTrue(credentialRepository.findByUserId(user.getId()).isPresent());
                            status.setRollbackOnly();
                        });
        assertTrue(credentialRepository.findByUserId(user.getId()).isEmpty());
        credentialRepository.insert(
                PasswordCredential.create(user.getId(), "test-original-hash", "test-v1"));
        var before = credentials.findByUserId(user.getId().value()).orElseThrow();
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            assertTrue(
                                    credentialRepository.updateIfVersionMatches(
                                            user.getId(), 0, "test-rolled-back-hash", "test-v2"));
                            assertCredential(
                                    credentialRepository.findByUserId(user.getId()).orElseThrow(),
                                    user.getId(),
                                    "test-rolled-back-hash",
                                    1,
                                    "test-v2");
                            status.setRollbackOnly();
                        });
        assertEquals(before, credentials.findByUserId(user.getId().value()).orElseThrow());
        assertCredential(
                credentialRepository.findByUserId(user.getId()).orElseThrow(),
                user.getId(),
                "test-original-hash",
                0,
                "test-v1");
    }

    @Test
    void credentialRepositoryConcurrentUpdatesHaveExactlyOneWinner() throws Exception {
        var user = newUser();
        credentialRepository.insert(
                PasswordCredential.create(user.getId(), "test-original-hash", "test-v1"));
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first =
                    workers.submit(() -> competeForCredential(user.getId(), start, "test-hash-a"));
            var second =
                    workers.submit(() -> competeForCredential(user.getId(), start, "test-hash-b"));
            assertEquals(1, first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS));
        }
        var restored = credentialRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals(1, restored.getAuthenticationVersion());
        assertTrue(List.of("test-hash-a", "test-hash-b").contains(restored.getPasswordHash()));
        assertEquals("test-v2", restored.getPasswordInputVersion());
    }

    @Test
    void credentialRepositoryRejectsLegacyBlankFieldsRatherThanInventingValidState() {
        // 原 SQL 允许这些历史字符串，但新增领域边界不应把空白凭据当成有效状态。
        var first = newUser();
        credentials.insert(first.getId().value(), " ", "test-v1");
        assertThrows(
                IllegalStateException.class,
                () -> credentialRepository.findByUserId(first.getId()));
        var second = newUser();
        credentials.insert(second.getId().value(), "test-hash", " ");
        assertThrows(
                IllegalStateException.class,
                () -> credentialRepository.findByUserId(second.getId()));
    }

    private int competeForCredential(UserId userId, CyclicBarrier start, String hash) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        return new TransactionTemplate(transactions)
                .execute(
                        status ->
                                credentialRepository.updateIfVersionMatches(
                                                userId, 0, hash, "test-v2")
                                        ? 1
                                        : 0);
    }

    private static void assertCredential(
            PasswordCredential credential,
            UserId userId,
            String hash,
            long version,
            String inputVersion) {
        assertEquals(userId, credential.getUserId());
        assertEquals(hash, credential.getPasswordHash());
        assertEquals(version, credential.getAuthenticationVersion());
        assertEquals(inputVersion, credential.getPasswordInputVersion());
    }

    @Test
    void threeLoginIdentitiesAreOrderedAndShareOneCredential() {
        var user = newUser();
        // 故意乱序插入，验证查询排序独立于插入顺序和枚举声明顺序。
        var username = bind(user, new UsernameIdentifier(Username.create("Alice_User")));
        var phone = bind(user, new PhoneIdentifier(PhoneNumber.create("+8613812345678")));
        var email = newAccount(user, "Alice@Example.COM");
        var bindings = accountRepository.findByUserId(user.getId());
        assertEquals(
                List.of(email.getId(), phone.getId(), username.getId()),
                bindings.stream().map(Account::getId).toList());
        assertEquals(
                List.of(IdentityType.EMAIL, IdentityType.PHONE, IdentityType.USERNAME),
                bindings.stream().map(binding -> binding.getIdentifier().getType()).toList());
        credentialRepository.insert(
                PasswordCredential.create(user.getId(), "test-shared-hash", "test-v1"));
        assertTrue(
                credentialRepository.updateIfVersionMatches(
                        user.getId(), 0, "test-updated-hash", "test-v2"));
        for (var binding : bindings) {
            var restored =
                    accountRepository.findByIdentifier(binding.getIdentifier()).orElseThrow();
            assertEquals(binding.getId(), restored.getId());
            assertEquals(user.getId(), restored.getUserId());
            assertEquals(7, restored.getId().value().version());
            assertCredential(
                    credentialRepository.findByUserId(restored.getUserId()).orElseThrow(),
                    user.getId(),
                    "test-updated-hash",
                    1,
                    "test-v2");
        }
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_password_credentials", Long.class));
    }

    @Test
    void usernameQueriesPreserveOriginalAndRejectCrossUserCaseVariants() {
        var owner = newUser();
        var account = bind(owner, new UsernameIdentifier(Username.create("Alice_User")));
        var variant = new UsernameIdentifier(Username.create("ALICE_USER"));
        var restored = accountRepository.findByIdentifier(variant).orElseThrow();
        assertEquals(account.getId(), restored.getId());
        assertEquals(owner.getId(), restored.getUserId());
        assertEquals("Alice_User", restored.getIdentifier().getOriginalIdentifier());
        assertEquals("alice_user", restored.getIdentifier().getCanonicalIdentifier());
        assertEquals(
                restored.getIdentifier(),
                accountRepository.findById(account.getId()).orElseThrow().getIdentifier());
        assertThrows(DuplicateKeyException.class, () -> bind(newUser(), variant));
        assertThrows(
                DuplicateKeyException.class,
                () -> bind(owner, new UsernameIdentifier(Username.create("Other_User"))));
        assertTrue(
                accountRepository
                        .findByIdentifier(new UsernameIdentifier(Username.create("Missing_User")))
                        .isEmpty());
        assertTrue(accounts.findByIdentifier("USERNAME", "alice_user' OR '1'='1").isEmpty());
        assertTrue(accounts.findByIdentifier("EMAIL", "alice_user").isEmpty());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "a",
                "ab",
                "1abc",
                "_abc",
                " Alice",
                "Alice ",
                "Ali ce",
                "Ali-ce",
                "Ali.ce",
                "Alice@example.com",
                "álîce",
                "用户abc",
                "Ａlice",
                "Alice١",
                "Alice\n",
                "Alice\r",
                "Alice\t",
                "admin",
                "ADMIN",
                "AdMiN",
                "system",
                "SYSTEM",
                "support",
                "SUPPORT"
            })
    void databaseRejectsInvalidAndReservedUsernamesThroughDirectMapper(String value) {
        var user = newUser();
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        accounts.insert(
                                UuidV7Generator.generate(),
                                user.getId().value(),
                                "USERNAME",
                                value,
                                value.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    void databaseEnforcesUsernameLengthAndCanonicalDerivation() {
        var user = newUser();
        for (String canonical : List.of("ALICE_USER", "other_user", "alice_user\n")) {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () ->
                            accounts.insert(
                                    UuidV7Generator.generate(),
                                    user.getId().value(),
                                    "USERNAME",
                                    "Alice_User",
                                    canonical));
        }
        String tooLong = "a".repeat(33);
        assertThrows(
                DataIntegrityViolationException.class,
                () ->
                        accounts.insert(
                                UuidV7Generator.generate(),
                                user.getId().value(),
                                "USERNAME",
                                tooLong,
                                tooLong));
        assertTrue(accountRepository.findByUserId(user.getId()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Abc",
                "admin123",
                "admin_",
                "system1",
                "support_team",
                "root",
                "administrator",
                "Abcdefghijklmnopqrstuvwxyz123456"
            })
    void databaseAcceptsValidUsernameBoundariesAndNonreservedNames(String value) {
        var user = newUser();
        var id = UuidV7Generator.generate();
        String canonical = value.toLowerCase(java.util.Locale.ROOT);
        assertEquals(1, accounts.insert(id, user.getId().value(), "USERNAME", value, canonical));
        var restored = accountRepository.findById(new AccountId(id)).orElseThrow();
        assertEquals(value, restored.getIdentifier().getOriginalIdentifier());
        assertEquals(canonical, restored.getIdentifier().getCanonicalIdentifier());
        assertEquals(IdentityType.USERNAME, restored.getIdentifier().getType());
    }

    private int competeForBinding(User user, LoginIdentifier identifier, CyclicBarrier start) {
        // 屏障让两个任务就绪后再竞争；每个任务使用独立事务，由唯一约束裁决胜者。
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        try {
            new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> bind(user, identifier));
            return 1;
        } catch (DuplicateKeyException conflict) {
            return 0;
        }
    }

    private static LoginIdentifier identifier(IdentityType type, boolean different) {
        return switch (type) {
            case USERNAME ->
                    new UsernameIdentifier(
                            Username.create(different ? "Other_User" : "Alice_User"));
            case EMAIL -> email(different ? "other@example.com" : "Alice@Example.COM");
            case PHONE ->
                    new PhoneIdentifier(
                            PhoneNumber.create(different ? "+14155552671" : "+8613812345678"));
        };
    }

    private Account bind(User user, LoginIdentifier identifier) {
        var account =
                Account.create(new AccountId(UuidV7Generator.generate()), user.getId(), identifier);
        accountRepository.insert(account);
        return account;
    }

    private int compete(UserId user, CyclicBarrier start, String hash) {
        // 两个任务持有同一预期认证版本，条件更新只允许一个成功推进版本。
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
        var user = User.create(new UserId(UuidV7Generator.generate()));
        userRepository.insert(user);
        return user;
    }

    private Account newAccount(User user, String address) {
        var account =
                Account.create(
                        new AccountId(UuidV7Generator.generate()), user.getId(), email(address));
        accountRepository.insert(account);
        return account;
    }

    private static EmailIdentifier email(String value) {
        return new EmailIdentifier(EmailAddress.create(value));
    }
}
