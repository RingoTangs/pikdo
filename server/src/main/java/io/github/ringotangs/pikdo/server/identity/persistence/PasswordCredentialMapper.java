package io.github.ringotangs.pikdo.server.identity.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 按 UserId 管理密码记录的参数化 SQL 映射。多种登录身份共享同一条记录；认证版本条件更新在数据库中原子完成。 */
@Mapper
public interface PasswordCredentialMapper {
    /**
     * 使用参数化 SQL 创建一条记录，由数据库执行唯一性和外键校验。认证版本使用数据库默认值 0。
     *
     * @param userId 用户标识
     * @param passwordHash 已由调用方计算的密码哈希，不得传入明文密码
     * @param passwordInputVersion 密码输入处理版本的不透明标识，不解释具体策略
     * @return 插入影响的行数，正常为 1
     */
    @Insert(
            """
            INSERT INTO identity_password_credentials (user_id, password_hash, password_input_version)
            VALUES (#{userId}, #{passwordHash}, #{passwordInputVersion})
            """)
    int insert(
            @Param("userId") UUID userId,
            @Param("passwordHash") String passwordHash,
            @Param("passwordInputVersion") String passwordInputVersion);

    /**
     * 按用户标识查询记录。
     *
     * @param userId 用户标识
     * @return 查询结果；不存在时返回 Optional.empty()
     */
    @Select(
            """
            SELECT user_id, password_hash, authentication_version, password_input_version, created_at, updated_at
            FROM identity_password_credentials
            WHERE user_id = #{userId}
            """)
    @ConstructorArgs({
        @Arg(column = "user_id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "password_hash", javaType = String.class),
        @Arg(column = "authentication_version", javaType = long.class),
        @Arg(column = "password_input_version", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "updated_at", javaType = OffsetDateTime.class)
    })
    Optional<PasswordCredentialRow> findByUserId(@Param("userId") UUID userId);

    /**
     * 按预期版本原子替换密码哈希和输入版本并递增认证版本，不自动重试，不修改已有领域对象。
     *
     * @param userId 用户标识
     * @param expectedVersion 待比较的预期认证版本
     * @param passwordHash 已由调用方计算的密码哈希，不得传入明文密码
     * @param passwordInputVersion 密码输入处理版本的不透明标识，不解释具体策略
     * @return 影响行数：匹配时为 1，记录不存在或版本不匹配时为 0
     */
    @Update(
            """
            UPDATE identity_password_credentials
            SET password_hash = #{passwordHash},
                password_input_version = #{passwordInputVersion},
                authentication_version = authentication_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND authentication_version = #{expectedVersion}
            """)
    int updateIfVersionMatches(
            @Param("userId") UUID userId,
            @Param("expectedVersion") long expectedVersion,
            @Param("passwordHash") String passwordHash,
            @Param("passwordInputVersion") String passwordInputVersion);
}
