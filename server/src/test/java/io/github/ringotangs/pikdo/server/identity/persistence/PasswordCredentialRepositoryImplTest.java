package io.github.ringotangs.pikdo.server.identity.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.ringotangs.pikdo.server.identity.domain.PasswordCredential;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class PasswordCredentialRepositoryImplTest {
    private static final UUID ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UserId USER_ID = new UserId(ID);
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Test
    void queriesMapFullStateAndPreserveAbsence() {
        var mapper = new FakeMapper();
        var repository = new PasswordCredentialRepositoryImpl(mapper);
        assertTrue(repository.findByUserId(USER_ID).isEmpty());
        mapper.row =
                Optional.of(new PasswordCredentialRow(ID, "test-hash", 9, "test-v2", TIME, TIME));
        var credential = repository.findByUserId(USER_ID).orElseThrow();
        assertEquals(USER_ID, credential.getUserId());
        assertEquals("test-hash", credential.getPasswordHash());
        assertEquals(9, credential.getAuthenticationVersion());
        assertEquals("test-v2", credential.getPasswordInputVersion());
        assertEquals(List.of(ID), mapper.arguments);
    }

    @Test
    void insertAndUpdateUseValidatedValuesWithoutRetrying() {
        var mapper = new FakeMapper();
        var repository = new PasswordCredentialRepositoryImpl(mapper);
        repository.insert(PasswordCredential.create(USER_ID, " test-hash ", "test-v1"));
        assertEquals(Arrays.asList(ID, " test-hash ", "test-v1"), mapper.arguments);
        assertTrue(repository.updateIfVersionMatches(USER_ID, 4, "test-new-hash", "test-v2"));
        assertEquals(Arrays.asList(ID, 4L, "test-new-hash", "test-v2"), mapper.arguments);
        mapper.affected = 0;
        assertFalse(repository.updateIfVersionMatches(USER_ID, 4, "test-other-hash", "test-v3"));
        assertEquals(3, mapper.calls);
    }

    @Test
    void invalidInputsAndNoninitialInsertsNeverReachTheMapper() {
        var mapper = new FakeMapper();
        var repository = new PasswordCredentialRepositoryImpl(mapper);
        assertThrows(NullPointerException.class, () -> new PasswordCredentialRepositoryImpl(null));
        assertThrows(NullPointerException.class, () -> repository.insert(null));
        assertThrows(NullPointerException.class, () -> repository.findByUserId(null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        repository.insert(
                                PasswordCredential.restore(USER_ID, "test-hash", 3, "test-v1")));
        assertThrows(
                NullPointerException.class,
                () -> repository.updateIfVersionMatches(null, 0, "test-hash", "test-v1"));
        assertThrows(
                NullPointerException.class,
                () -> repository.updateIfVersionMatches(USER_ID, 0, null, "test-v1"));
        assertThrows(
                NullPointerException.class,
                () -> repository.updateIfVersionMatches(USER_ID, 0, "test-hash", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.updateIfVersionMatches(USER_ID, -1, "test-hash", "test-v1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.updateIfVersionMatches(USER_ID, 0, " ", "test-v1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> repository.updateIfVersionMatches(USER_ID, 0, "test-hash", ""));
        assertEquals(0, mapper.calls);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2})
    void insertionRequiresExactlyOneRow(int affected) {
        var mapper = new FakeMapper();
        mapper.affected = affected;
        var repository = new PasswordCredentialRepositoryImpl(mapper);
        assertThrows(
                IllegalStateException.class,
                () ->
                        repository.insert(
                                PasswordCredential.create(USER_ID, "test-hash", "test-v1")));
        assertEquals(1, mapper.calls);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void updateRejectsUnexpectedRowCounts(int affected) {
        var mapper = new FakeMapper();
        mapper.affected = affected;
        var repository = new PasswordCredentialRepositoryImpl(mapper);
        assertThrows(
                IllegalStateException.class,
                () -> repository.updateIfVersionMatches(USER_ID, 0, "test-hash", "test-v1"));
        assertEquals(1, mapper.calls);
    }

    @Test
    void invalidStoredStateFailsRestorationWithoutExposingHash() {
        for (var row :
                List.of(
                        new PasswordCredentialRow(
                                null, "sensitive-test-hash", 0, "test-v1", TIME, TIME),
                        new PasswordCredentialRow(ID, null, 0, "test-v1", TIME, TIME),
                        new PasswordCredentialRow(ID, "", 0, "test-v1", TIME, TIME),
                        new PasswordCredentialRow(ID, " ", 0, "test-v1", TIME, TIME),
                        new PasswordCredentialRow(
                                ID, "sensitive-test-hash", -1, "test-v1", TIME, TIME),
                        new PasswordCredentialRow(ID, "sensitive-test-hash", 0, null, TIME, TIME),
                        new PasswordCredentialRow(ID, "sensitive-test-hash", 0, "", TIME, TIME),
                        new PasswordCredentialRow(ID, "sensitive-test-hash", 0, " ", TIME, TIME))) {
            var mapper = new FakeMapper();
            mapper.row = Optional.of(row);
            var failure =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    new PasswordCredentialRepositoryImpl(mapper)
                                            .findByUserId(USER_ID));
            assertFalse(failure.toString().contains("sensitive-test-hash"));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause().toString().contains("sensitive-test-hash"));
        }
    }

    private static final class FakeMapper implements PasswordCredentialMapper {
        private int affected = 1;
        private int calls;
        private List<Object> arguments = List.of();
        private Optional<PasswordCredentialRow> row = Optional.empty();

        @Override
        public int insert(
                @NonNull UUID userId, @NonNull String hash, @NonNull String inputVersion) {
            calls++;
            arguments = Arrays.asList(userId, hash, inputVersion);
            return affected;
        }

        @Override
        public Optional<PasswordCredentialRow> findByUserId(@NonNull UUID userId) {
            calls++;
            arguments = List.of(userId);
            return row;
        }

        @Override
        public int updateIfVersionMatches(
                @NonNull UUID userId,
                long expectedVersion,
                @NonNull String hash,
                @NonNull String inputVersion) {
            calls++;
            arguments = Arrays.asList(userId, expectedVersion, hash, inputVersion);
            return affected;
        }
    }
}
