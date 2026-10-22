package io.github.ringotangs.pikdo.server.database;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ringotangs.pikdo.server.Application;
import io.github.ringotangs.pikdo.server.authentication.persistence.AuthenticationRateMapper;
import io.github.ringotangs.pikdo.server.authentication.persistence.EmailChallengeMapper;
import io.github.ringotangs.pikdo.server.authentication.support.AuthenticationTestConfiguration;
import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;
import io.github.ringotangs.pikdo.server.identity.persistence.UserMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Real PostgreSQL only. The Spring context owns the container, so cached pools never outlive it.
 */
@SpringBootTest(
        classes = Application.class,
        properties = {
            "pikdo.auth.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "pikdo.auth.smtp-host=localhost",
            "pikdo.auth.mail-from=noreply@pikdo.invalid"
        })
@AutoConfigureMockMvc
@Import({DatabaseFoundationIT.DatabaseConfiguration.class, AuthenticationTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseFoundationIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;
    @Autowired private SqlSessionFactory sessions;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ProbeMapper probe;
    @Autowired private PostgreSQLContainer postgres;

    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = {UserMapper.class, EmailChallengeMapper.class})
    static class DatabaseConfiguration {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6")
                    .withDatabaseName("pikdo_foundation_test")
                    .withUsername("foundation_test")
                    .withPassword(UUID.randomUUID().toString());
        }

        // Explicit registration avoids exposing a test mapper to production package scanning.
        @Bean
        MapperFactoryBean<ProbeMapper> probeMapper(SqlSessionFactory factory) {
            var mapper = new MapperFactoryBean<>(ProbeMapper.class);
            mapper.setSqlSessionFactory(factory);
            return mapper;
        }
    }

    public interface ProbeMapper {
        @Select("SELECT CAST(#{value} AS text)")
        String echo(@Param("value") String value);
    }

    @BeforeEach
    void previousTestLeftNoBusinessRows() {
        for (String table :
                List.of("identity_accounts", "identity_password_credentials", "identity_users")) {
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
    }

    @AfterEach
    void removeOnlyRowsInThisDisposableContainer() {
        jdbc.update("DELETE FROM identity_accounts");
        jdbc.update("DELETE FROM identity_password_credentials");
        jdbc.update("DELETE FROM identity_users");
    }

    @Test
    void postgres18AndAllDatabaseInfrastructureAreAvailable() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals(18, connection.getMetaData().getDatabaseMajorVersion());
            assertEquals("pikdo_foundation_test", connection.getCatalog());
        }
        assertNotNull(transactions);
        assertSame(dataSource, sessions.getConfiguration().getEnvironment().getDataSource());
        assertTrue(sessions.getConfiguration().isMapUnderscoreToCamelCase());
        assertTrue(sessions.getConfiguration().hasMapper(UserMapper.class));
        assertTrue(sessions.getConfiguration().hasMapper(EmailChallengeMapper.class));
        assertTrue(sessions.getConfiguration().hasMapper(AuthenticationRateMapper.class));
        assertEquals(
                "a'); DROP TABLE identity_users; --",
                probe.echo("a'); DROP TABLE identity_users; --"));
    }

    @Test
    void realDatabaseHealthStillHidesComponentsAndManagementEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                                        JsonCompareMode.STRICT));
        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    void invalidDatabaseCredentialsPreventContextStartup() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=nonexistent_test_user",
                        "spring.datasource.password=invalid-test-credential",
                        "spring.datasource.hikari.connection-timeout=250",
                        "spring.datasource.hikari.initialization-fail-timeout=1",
                        "spring.datasource.driver-class-name=org.postgresql.Driver")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void automaticMigrationHasSuccessfulV1V2AndV3AndIsRepeatable() {
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                        Long.class));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success",
                        Long.class));
        assertEquals(5, flyway.info().applied().length);
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success",
                        Long.class));
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(5, flyway.info().applied().length);
        assertEquals(
                3L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN ('identity_users','identity_accounts','identity_password_credentials')",
                        Long.class));
    }

    @Test
    void schemaTypesNullabilityAndCollationMatchTheContract() {
        var expected =
                List.of(
                        "identity_users.id:uuid:NO",
                        "identity_users.status:character varying:NO",
                        "identity_users.created_at:timestamp with time zone:NO",
                        "identity_users.updated_at:timestamp with time zone:NO",
                        "identity_accounts.id:uuid:NO",
                        "identity_accounts.user_id:uuid:NO",
                        "identity_accounts.identity_type:character varying:NO",
                        "identity_accounts.original_identifier:character varying:NO",
                        "identity_accounts.canonical_identifier:character varying:NO",
                        "identity_accounts.created_at:timestamp with time zone:NO",
                        "identity_password_credentials.user_id:uuid:NO",
                        "identity_password_credentials.password_hash:text:NO",
                        "identity_password_credentials.authentication_version:bigint:NO",
                        "identity_password_credentials.password_input_version:character varying:NO",
                        "identity_password_credentials.created_at:timestamp with time zone:NO",
                        "identity_password_credentials.updated_at:timestamp with time zone:NO");
        var actual =
                jdbc.queryForList(
                        "SELECT table_name || '.' || column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name IN ('identity_users','identity_accounts','identity_password_credentials')",
                        String.class);
        assertEquals(new HashSet<>(expected), new HashSet<>(actual));
        assertEquals(
                254,
                jdbc.queryForObject(
                        "SELECT character_maximum_length FROM information_schema.columns WHERE table_name = 'identity_accounts' AND column_name = 'original_identifier'",
                        Integer.class));
        assertEquals(
                "C",
                jdbc.queryForObject(
                        "SELECT collation_name FROM information_schema.columns WHERE table_name = 'identity_accounts' AND column_name = 'canonical_identifier'",
                        String.class));
    }

    @Test
    void legitimateUserAccountAndCredentialCanBeStored() {
        var user = insertUser();
        var email = EmailAddress.create("Alice+News@Example.COM");
        insertAccount(user, email);
        insertCredential(user, 0, "test-input-v1");
        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "SELECT status FROM identity_users WHERE id = ?", String.class, user));
        assertEquals(
                email.getOriginalAddress(),
                jdbc.queryForObject(
                        "SELECT original_identifier FROM identity_accounts WHERE user_id = ?",
                        String.class,
                        user));
        assertEquals(
                email.getCanonicalAddress(),
                jdbc.queryForObject(
                        "SELECT canonical_identifier FROM identity_accounts WHERE user_id = ?",
                        String.class,
                        user));
    }

    @Test
    void duplicateUserIdIsRejected() {
        var user = insertUser();
        assertSqlState(
                "23505",
                () ->
                        jdbc.update(
                                "INSERT INTO identity_users (id, status) VALUES (?, 'ACTIVE')",
                                user));
    }

    @Test
    void disabledIsTheOnlyOtherAllowedStatus() {
        var user = insertUser();
        jdbc.update("UPDATE identity_users SET status = 'DISABLED' WHERE id = ?", user);
        assertEquals(
                "DISABLED",
                jdbc.queryForObject(
                        "SELECT status FROM identity_users WHERE id = ?", String.class, user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "DELETED", "active", ""})
    void invalidStatusesAreRejected(String status) {
        assertSqlState(
                "23514",
                () ->
                        jdbc.update(
                                "INSERT INTO identity_users (id, status) VALUES (?, ?)",
                                UUID.randomUUID(),
                                status));
    }

    @Test
    void accountNeedsAnExistingUser() {
        assertSqlState(
                "23503",
                () -> insertAccount(UUID.randomUUID(), EmailAddress.create("alice@example.com")));
    }

    @Test
    void accountIdIsUnique() {
        var first = insertUser();
        var second = insertUser();
        var id = UUID.randomUUID();
        insertAccount(id, first, "alice@example.com", "alice@example.com");
        assertSqlState(
                "23505", () -> insertAccount(id, second, "bob@example.com", "bob@example.com"));
    }

    @Test
    void aUserCannotHaveTwoEffectiveEmailAccounts() {
        var user = insertUser();
        insertAccount(user, EmailAddress.create("alice@example.com"));
        assertSqlState("23505", () -> insertAccount(user, EmailAddress.create("bob@example.com")));
    }

    @Test
    void canonicalIdentityCannotBelongToTwoUsers() {
        var first = insertUser();
        var second = insertUser();
        insertAccount(first, EmailAddress.create("alice@example.com"));
        assertSqlState(
                "23505", () -> insertAccount(second, EmailAddress.create("alice@example.com")));
    }

    @Test
    void domainDerivedCaseVariantsCollideWithoutReplacingTheOriginal() {
        var first = insertUser();
        var second = insertUser();
        var original = EmailAddress.create("Alice@Example.COM");
        var variant = EmailAddress.create("alice@example.com");
        assertEquals(original, variant);
        insertAccount(first, original);
        assertSqlState("23505", () -> insertAccount(second, variant));
        assertEquals(
                "Alice@Example.COM",
                jdbc.queryForObject(
                        "SELECT original_identifier FROM identity_accounts WHERE user_id = ?",
                        String.class,
                        first));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALICE@example.com", "bob@example.com"})
    void aCallerCannotSupplyAnInconsistentCanonicalKey(String canonical) {
        var user = insertUser();
        assertSqlState(
                "23514",
                () -> insertAccount(UUID.randomUUID(), user, "Alice@Example.COM", canonical));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " alice@example.com",
                "alice@example.com\n",
                "álîce@example.com",
                "alice@例子.com",
                "alice\u007f@example.com"
            })
    void databaseRejectsNonPrintableOrNonAsciiOriginals(String original) {
        var user = insertUser();
        assertSqlState("23514", () -> insertAccount(UUID.randomUUID(), user, original, original));
    }

    @Test
    void databasePreservesDotsTagsAndDifferentDomains() {
        for (String address :
                List.of(
                        "a.b+news@example.com",
                        "ab+news@example.com",
                        "a.b@example.com",
                        "a.b+news@example.org")) {
            insertAccount(insertUser(), EmailAddress.create(address));
        }
        assertEquals(4L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
    }

    @Test
    void databaseEnforcesAddressStorageLength() {
        var user = insertUser();
        var valid =
                "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertEquals(254, valid.length());
        insertAccount(user, EmailAddress.create(valid));
        var other = insertUser();
        assertSqlState(
                "22001", () -> insertAccount(UUID.randomUUID(), other, valid + "e", valid + "e"));
    }

    @Test
    void credentialNeedsAnExistingUser() {
        assertSqlState("23503", () -> insertCredential(UUID.randomUUID(), 0, "test-input-v1"));
    }

    @Test
    void aUserHasAtMostOnePasswordCredential() {
        var user = insertUser();
        insertCredential(user, 0, "test-input-v1");
        assertSqlState("23505", () -> insertCredential(user, 0, "test-input-v1"));
    }

    @Test
    void authenticationVersionDefaultsToZeroAndRejectsNegativeValues() {
        var user = insertUser();
        jdbc.update(
                "INSERT INTO identity_password_credentials (user_id, password_hash, password_input_version) VALUES (?, 'test-only-not-a-real-hash', 'test-input-v1')",
                user);
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT authentication_version FROM identity_password_credentials WHERE user_id = ?",
                        Long.class,
                        user));
        assertSqlState(
                "23514",
                () ->
                        jdbc.update(
                                "UPDATE identity_password_credentials SET authentication_version = -1 WHERE user_id = ?",
                                user));
    }

    @Test
    void passwordInputVersionCannotBeEmpty() {
        var user = insertUser();
        assertSqlState("23514", () -> insertCredential(user, 0, ""));
    }

    @Test
    void userDeletionDoesNotSilentlyCascadeIntoBindingsOrCredentials() {
        var user = insertUser();
        insertAccount(user, EmailAddress.create("alice@example.com"));
        assertSqlState("23001", () -> jdbc.update("DELETE FROM identity_users WHERE id = ?", user));
        jdbc.update("DELETE FROM identity_accounts");
        insertCredential(user, 0, "test-input-v1");
        assertSqlState("23001", () -> jdbc.update("DELETE FROM identity_users WHERE id = ?", user));
    }

    @Test
    void timestamptzPreservesTheInstantAcrossSessionTimeZones() {
        var user = insertUser();
        var supplied = OffsetDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneOffset.ofHours(8));
        jdbc.update("UPDATE identity_users SET created_at = ? WHERE id = ?", supplied, user);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            jdbc.execute("SET LOCAL TIME ZONE 'America/New_York'");
                            var stored =
                                    jdbc.queryForObject(
                                            "SELECT created_at FROM identity_users WHERE id = ?",
                                            OffsetDateTime.class,
                                            user);
                            assertNotNull(stored);
                            assertEquals(supplied.toInstant(), stored.toInstant());
                        });
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT updated_at FROM identity_users WHERE id = ?",
                        OffsetDateTime.class,
                        user));
    }

    @Test
    void springTransactionRollsBackTheEarlierUserInsertOnAccountConflict() {
        insertAccount(insertUser(), EmailAddress.create("taken@example.com"));
        var newUser = UUID.randomUUID();
        assertSqlState(
                "23505",
                () ->
                        new TransactionTemplate(transactions)
                                .executeWithoutResult(
                                        status -> {
                                            jdbc.update(
                                                    "INSERT INTO identity_users (id, status) VALUES (?, 'ACTIVE')",
                                                    newUser);
                                            insertAccount(
                                                    newUser,
                                                    EmailAddress.create("taken@example.com"));
                                        }));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_users WHERE id = ?", Long.class, newUser));
    }

    @Test
    void twoConcurrentTransactionsCannotCommitTheSameEmailIdentity() throws Exception {
        var start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> competeForIdentity(start));
            var second = workers.submit(() -> competeForIdentity(start));
            var results =
                    List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM identity_users", Long.class));
    }

    private boolean competeForIdentity(CyclicBarrier start) {
        try {
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            status -> {
                                var user = insertUser();
                                try {
                                    start.await(10, TimeUnit.SECONDS);
                                } catch (Exception failure) {
                                    throw new IllegalStateException(failure);
                                }
                                insertAccount(user, EmailAddress.create("concurrent@example.com"));
                            });
            return true;
        } catch (DataAccessException conflict) {
            assertEquals("23505", sqlState(conflict));
            return false;
        }
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO identity_users (id, status) VALUES (?, 'ACTIVE')", id);
        return id;
    }

    private void insertAccount(UUID user, EmailAddress email) {
        insertAccount(
                UUID.randomUUID(), user, email.getOriginalAddress(), email.getCanonicalAddress());
    }

    private void insertAccount(UUID id, UUID user, String original, String canonical) {
        jdbc.update(
                "INSERT INTO identity_accounts (id, user_id, identity_type, original_identifier, canonical_identifier) VALUES (?, ?, 'EMAIL', ?, ?)",
                id,
                user,
                original,
                canonical);
    }

    private void insertCredential(UUID user, long version, String inputVersion) {
        jdbc.update(
                "INSERT INTO identity_password_credentials (user_id, password_hash, authentication_version, password_input_version) VALUES (?, 'test-only-not-a-real-hash', ?, ?)",
                user,
                version,
                inputVersion);
    }

    private static void assertSqlState(String expected, Runnable operation) {
        var failure = assertThrows(DataAccessException.class, operation::run);
        assertEquals(expected, sqlState(failure));
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        throw new AssertionError("Expected an SQL failure", failure);
    }
}
