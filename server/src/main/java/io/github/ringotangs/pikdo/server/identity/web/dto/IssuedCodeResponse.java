package io.github.ringotangs.pikdo.server.identity.web.dto;

import java.time.Instant;
import java.util.UUID;

/** 邮箱认证 HTTP 数据契约。 */
public record IssuedCodeResponse(UUID challengeId, Instant expiresAt, long retryAfterSeconds) {}
