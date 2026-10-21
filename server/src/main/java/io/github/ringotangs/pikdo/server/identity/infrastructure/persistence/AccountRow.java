package io.github.ringotangs.pikdo.server.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Database representation; audit fields are populated by PostgreSQL. */
public record AccountRow(
        UUID id,
        UUID userId,
        String originalAddress,
        String canonicalAddress,
        OffsetDateTime createdAt) {}
