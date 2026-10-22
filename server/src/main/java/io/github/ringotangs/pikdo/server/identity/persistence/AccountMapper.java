package io.github.ringotangs.pikdo.server.identity.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 登录身份表的参数化 SQL 映射。数据库约束负责唯一性与基础存储规则，完整领域校验由 Repository 实现执行。 */
@Mapper
public interface AccountMapper {
    /**
     * 使用参数化 SQL 创建一条记录，由数据库执行唯一性和外键校验。
     *
     * @param id 登录身份绑定的标识
     * @param userId 用户标识
     * @param identityType EMAIL、PHONE 或 USERNAME 类型字符串
     * @param originalIdentifier 身份原始值
     * @param canonicalIdentifier 与原始值及类型一致的规范化身份键
     * @return 插入影响的行数，正常为 1
     */
    @Insert(
            """
            INSERT INTO identity_accounts (id, user_id, identity_type, original_identifier, canonical_identifier)
            VALUES (#{id}, #{userId}, #{identityType}, #{originalIdentifier}, #{canonicalIdentifier})
            """)
    int insert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("identityType") String identityType,
            @Param("originalIdentifier") String originalIdentifier,
            @Param("canonicalIdentifier") String canonicalIdentifier);

    /**
     * 按标识查询记录。
     *
     * @param id 登录身份绑定的标识
     * @return 查询结果；不存在时返回 Optional.empty()
     */
    @Select(
            """
            SELECT id, user_id, identity_type, original_identifier, canonical_identifier, created_at
            FROM identity_accounts
            WHERE id = #{id}
            """)
    @Results(id = "accountRow")
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "user_id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "identity_type", javaType = String.class),
        @Arg(column = "original_identifier", javaType = String.class),
        @Arg(column = "canonical_identifier", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class)
    })
    Optional<AccountRow> findById(@Param("id") UUID id);

    /**
     * 按身份类型和规范化值查询，不合并不同类型的身份。
     *
     * @param identityType EMAIL、PHONE 或 USERNAME 类型字符串
     * @param canonicalIdentifier 与原始值及类型一致的规范化身份键
     * @return 查询结果；不存在时返回 Optional.empty()
     */
    @Select(
            """
            SELECT id, user_id, identity_type, original_identifier, canonical_identifier, created_at
            FROM identity_accounts
            WHERE identity_type = #{identityType} AND canonical_identifier = #{canonicalIdentifier}
            """)
    @ResultMap("accountRow")
    Optional<AccountRow> findByIdentifier(
            @Param("identityType") String identityType,
            @Param("canonicalIdentifier") String canonicalIdentifier);

    /**
     * 按用户标识查询记录。 按 EMAIL、PHONE、USERNAME 顺序返回。
     *
     * @param userId 用户标识
     * @return 该用户的绑定列表，无绑定时为空列表
     */
    @Select(
            """
            SELECT id, user_id, identity_type, original_identifier, canonical_identifier, created_at
            FROM identity_accounts
            WHERE user_id = #{userId}
            ORDER BY identity_type
            """)
    @ResultMap("accountRow")
    List<AccountRow> findByUserId(@Param("userId") UUID userId);
}
