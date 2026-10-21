package io.github.ringotangs.pikdo.server.database;

import static org.junit.jupiter.api.Assertions.*;

import io.github.ringotangs.pikdo.server.shared.id.UuidV7Generator;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

/** Runs only against disposable containers; never reads application datasource configuration. */
class AccountMigrationIT {
    @Test
    void v1RecordsUpgradeWithoutChangingTheirIdentityOriginalSpellingOrAuditFields() {
        try (var postgres = container()) {
            postgres.start();
            var source =
                    new DriverManagerDataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var jdbc = new JdbcTemplate(source);
            var v1 = Flyway.configure().dataSource(source).target("1").load();
            assertEquals(1, v1.migrate().migrationsExecuted);
            var user = UuidV7Generator.generate();
            var account = UuidV7Generator.generate();
            jdbc.update(
                    "INSERT INTO identity_users (id, status, created_at, updated_at) VALUES (?, 'DISABLED', TIMESTAMPTZ '2026-01-01 01:02:03Z', TIMESTAMPTZ '2026-02-02 02:03:04Z')",
                    user);
            jdbc.update(
                    "INSERT INTO identity_accounts (id, user_id, original_address, canonical_address, created_at) VALUES (?, ?, 'Alice+News@Example.COM', 'alice+news@example.com', TIMESTAMPTZ '2026-03-03 03:04:05Z')",
                    account,
                    user);
            jdbc.update(
                    "INSERT INTO identity_password_credentials (user_id, password_hash, authentication_version, password_input_version, created_at, updated_at) VALUES (?, 'test-migration-hash', 7, 'test-input-v1', TIMESTAMPTZ '2026-04-04 04:05:06Z', TIMESTAMPTZ '2026-05-05 05:06:07Z')",
                    user);
            // V1 intentionally delegates full email grammar to the domain. Do not make V2
            // reject an existing ASCII row merely because it is outside that grammar.
            var legacyUser = UuidV7Generator.generate();
            jdbc.update("INSERT INTO identity_users (id, status) VALUES (?, 'ACTIVE')", legacyUser);
            jdbc.update(
                    "INSERT INTO identity_accounts (id, user_id, original_address, canonical_address) VALUES (?, ?, 'Legacy-ASCII', 'legacy-ascii')",
                    UuidV7Generator.generate(),
                    legacyUser);
            var beforeUsers = jdbc.queryForList("SELECT * FROM identity_users ORDER BY id");
            var beforeAccounts =
                    jdbc.queryForList(
                            "SELECT id, user_id, original_address AS original_identifier, canonical_address AS canonical_identifier, created_at FROM identity_accounts ORDER BY id");
            var beforeCredentials =
                    jdbc.queryForList(
                            "SELECT * FROM identity_password_credentials ORDER BY user_id");
            var checksum =
                    jdbc.queryForObject(
                            "SELECT checksum FROM flyway_schema_history WHERE version = '1'",
                            Integer.class);

            var latest = Flyway.configure().dataSource(source).load();
            assertEquals(1, latest.migrate().migrationsExecuted);
            assertEquals(
                    beforeUsers, jdbc.queryForList("SELECT * FROM identity_users ORDER BY id"));
            assertEquals(
                    beforeAccounts,
                    jdbc.queryForList(
                            "SELECT id, user_id, original_identifier, canonical_identifier, created_at FROM identity_accounts ORDER BY id"));
            assertEquals(
                    beforeCredentials,
                    jdbc.queryForList(
                            "SELECT * FROM identity_password_credentials ORDER BY user_id"));
            assertEquals(
                    List.of("EMAIL", "EMAIL"),
                    jdbc.queryForList(
                            "SELECT identity_type FROM identity_accounts ORDER BY id",
                            String.class));
            assertEquals(
                    checksum,
                    jdbc.queryForObject(
                            "SELECT checksum FROM flyway_schema_history WHERE version = '1'",
                            Integer.class));
            jdbc.update(
                    "INSERT INTO identity_accounts (id, user_id, identity_type, original_identifier, canonical_identifier) VALUES (?, ?, 'PHONE', '+8613812345678', '+8613812345678')",
                    UuidV7Generator.generate(),
                    user);
            assertEquals(
                    2L,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM identity_accounts WHERE user_id = ?",
                            Long.class,
                            user));
            assertEquals(
                    1L,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM identity_password_credentials WHERE user_id = ?",
                            Long.class,
                            user));
            latest.validate();
            assertEquals(0, latest.migrate().migrationsExecuted);
            assertEquals(2, latest.info().applied().length);
        }
    }

    @Test
    void emptyDatabaseExecutesBothMigrationsAndCanRepeatValidation() {
        try (var postgres = container()) {
            postgres.start();
            var source =
                    new DriverManagerDataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var flyway = Flyway.configure().dataSource(source).load();
            assertEquals(2, flyway.migrate().migrationsExecuted);
            var jdbc = new JdbcTemplate(source);
            assertEquals(
                    List.of("1", "2"),
                    jdbc.queryForList(
                            "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                            String.class));
            assertEquals(
                    0L, jdbc.queryForObject("SELECT count(*) FROM identity_accounts", Long.class));
            flyway.validate();
            assertEquals(0, flyway.migrate().migrationsExecuted);
        }
    }

    private static PostgreSQLContainer container() {
        return new PostgreSQLContainer("postgres:18.6")
                .withDatabaseName("pikdo_migration_test")
                .withUsername("migration_test")
                .withPassword(UUID.randomUUID().toString());
    }
}
