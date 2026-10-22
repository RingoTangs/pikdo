package io.github.ringotangs.pikdo.server.identity.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 登录身份数据库记录。类型及规范化值须经领域还原重新校验，创建时间由数据库提供。
 *
 * @param id 登录身份绑定的标识
 * @param userId 用户标识
 * @param identityType EMAIL、PHONE 或 USERNAME 类型字符串
 * @param originalIdentifier 身份原始值
 * @param canonicalIdentifier 与原始值及类型一致的规范化身份键
 * @param createdAt 数据库创建时间
 */
public record AccountRow(
        UUID id,
        UUID userId,
        String identityType,
        String originalIdentifier,
        String canonicalIdentifier,
        OffsetDateTime createdAt) {}
