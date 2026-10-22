package io.github.ringotangs.pikdo.server.identity.persistence;

import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 挑战只存摘要；登录归属是发码时的快照，消费时仍须重新核对。 */
public record EmailChallengeRow(
        UUID id,
        String purpose,
        String canonicalEmail,
        String originalEmail,
        byte[] flowDigest,
        byte[] codeDigest,
        boolean eligible,
        int attempts,
        int maxAttempts,
        OffsetDateTime expiresAt,
        @Nullable OffsetDateTime consumedAt,
        @Nullable OffsetDateTime invalidatedAt,
        @Nullable UUID accountId,
        @Nullable UUID userId) {
    @Override
    public String toString() {
        return "EmailChallengeRow[redacted]";
    }
}
