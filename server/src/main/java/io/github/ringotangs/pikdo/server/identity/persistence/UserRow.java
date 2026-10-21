package io.github.ringotangs.pikdo.server.identity.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Database representation; audit fields are populated by PostgreSQL. */
public record UserRow(UUID id, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
