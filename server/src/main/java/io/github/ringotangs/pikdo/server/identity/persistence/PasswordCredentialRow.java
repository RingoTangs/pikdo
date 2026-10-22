package io.github.ringotangs.pikdo.server.identity.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 以 UserId 为主键的密码数据库记录，审计时间由数据库维护；字符串表示始终脱敏。
 *
 * @param userId 用户标识
 * @param passwordHash 已由调用方计算的密码哈希，不得传入明文密码
 * @param authenticationVersion 非负认证版本
 * @param passwordInputVersion 密码输入处理版本的不透明标识，不解释具体策略
 * @param createdAt 数据库创建时间
 * @param updatedAt 数据库最近更新时间
 */
public record PasswordCredentialRow(
        UUID userId,
        String passwordHash,
        long authenticationVersion,
        String passwordInputVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
    /**
     * {@inheritDoc} 返回固定脱敏表示，不包含密码哈希或其他字段值。
     *
     * @return 脱敏字符串
     */
    @Override
    public String toString() {
        // 覆盖 record 默认输出，避免调试或日志意外泄露密码哈希。
        return "PasswordCredentialRow[redacted]";
    }
}
