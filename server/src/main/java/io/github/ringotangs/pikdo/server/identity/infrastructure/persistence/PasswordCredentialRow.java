package io.github.ringotangs.pikdo.server.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Database representation; audit fields are populated by PostgreSQL. */
public record PasswordCredentialRow(
        UUID userId,
        String passwordHash,
        long authenticationVersion,
        String passwordInputVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
    @Override
    public String toString() {
        return "PasswordCredentialRow[redacted]";
    }
}
