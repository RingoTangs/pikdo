package io.github.ringotangs.pikdo.server.identity.persistence;

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
        // 覆盖 record 默认输出，避免调试或日志意外泄露密码哈希。
        return "PasswordCredentialRow[redacted]";
    }
}
