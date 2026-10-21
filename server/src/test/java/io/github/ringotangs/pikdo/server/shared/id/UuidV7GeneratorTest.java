package io.github.ringotangs.pikdo.server.shared.id;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class UuidV7GeneratorTest {
    @Test
    void generatesRfc9562Version7WithStandardStringRoundTrip() {
        UUID id = UuidV7Generator.generate();
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        assertTrue(
                id.toString()
                        .matches(
                                "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertEquals(id, UUID.fromString(id.toString()));
    }

    @Test
    void sequentialSampleHasNoDuplicates() {
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            UUID id = UuidV7Generator.generate();
            assertEquals(7, id.version());
            assertEquals(2, id.variant());
            assertTrue(ids.add(id));
        }
        assertEquals(10_000, ids.size());
    }

    @Test
    void concurrentCallsToTheSharedGeneratorHaveNoDuplicatesInTheSample() throws Exception {
        var jobs = new ArrayList<Callable<HashSet<UUID>>>();
        for (int thread = 0; thread < 4; thread++) {
            jobs.add(
                    () -> {
                        var ids = new HashSet<UUID>();
                        for (int i = 0; i < 1_000; i++) {
                            UUID id = UuidV7Generator.generate();
                            assertEquals(7, id.version());
                            assertEquals(2, id.variant());
                            assertTrue(ids.add(id));
                        }
                        return ids;
                    });
        }
        var all = new HashSet<UUID>();
        try (var workers = Executors.newFixedThreadPool(4)) {
            for (var result : workers.invokeAll(jobs, 10, TimeUnit.SECONDS)) {
                for (UUID id : result.get()) {
                    assertTrue(all.add(id));
                }
            }
        }
        assertEquals(4_000, all.size());
    }
}
