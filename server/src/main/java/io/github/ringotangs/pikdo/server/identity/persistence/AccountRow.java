package io.github.ringotangs.pikdo.server.identity.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountRow(
        UUID id,
        UUID userId,
        String identityType,
        String originalIdentifier,
        String canonicalIdentifier,
        OffsetDateTime createdAt) {}
