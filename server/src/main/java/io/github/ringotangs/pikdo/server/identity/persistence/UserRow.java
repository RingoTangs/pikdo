package io.github.ringotangs.pikdo.server.identity.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 用户数据库记录，不承载领域行为；创建和更新时间由数据库维护。
 *
 * @param id 用户主体标识
 * @param status 数据库状态字符串，合法值为 ACTIVE 或 DISABLED
 * @param createdAt 数据库创建时间
 * @param updatedAt 数据库最近更新时间
 */
public record UserRow(UUID id, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
