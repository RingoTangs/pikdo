package io.github.ringotangs.pikdo.server.identity.web.dto;

import java.time.Instant;
import java.util.UUID;

public record IssuedCodeResponse(UUID challengeId, Instant expiresAt, long retryAfterSeconds) {}
