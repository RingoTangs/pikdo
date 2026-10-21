package io.github.ringotangs.pikdo.server.shared.id;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;

/** Generates new business entity IDs at the application boundary, independently of persistence. */
public final class UuidV7Generator {
    // JUG's generator is thread-safe; reuse its state instead of creating one per call.
    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private UuidV7Generator() {}

    public static UUID generate() {
        return GENERATOR.generate();
    }
}
