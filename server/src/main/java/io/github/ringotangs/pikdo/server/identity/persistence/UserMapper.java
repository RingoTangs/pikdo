package io.github.ringotangs.pikdo.server.identity.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** 用户表的参数化 SQL 映射，显式还原数据库记录，审计时间由 PostgreSQL 维护。 */
@Mapper
public interface UserMapper {
    /**
     * 使用参数化 SQL 创建一条记录，由数据库执行唯一性和外键校验。
     *
     * @param id 用户主体标识
     * @param status 数据库状态字符串，合法值为 ACTIVE 或 DISABLED
     * @return 插入影响的行数，正常为 1
     */
    @Insert(
            """
            INSERT INTO identity_users (id, status)
            VALUES (#{id}, #{status})
            """)
    int insert(@Param("id") UUID id, @Param("status") String status);

    // PostgreSQL JDBC returns native UUID values via getObject; no custom ID handler is needed.
    /**
     * 按标识查询记录。
     *
     * @param id 用户主体标识
     * @return 查询结果；不存在时返回 Optional.empty()
     */
    @Select(
            """
            SELECT id, status, created_at, updated_at
            FROM identity_users
            WHERE id = #{id}
            """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "status", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class),
        @Arg(column = "updated_at", javaType = OffsetDateTime.class)
    })
    Optional<UserRow> findById(@Param("id") UUID id);

    /**
     * 显式更新用户状态并由数据库维护更新时间。
     *
     * @param id 用户主体标识
     * @param status 数据库状态字符串，合法值为 ACTIVE 或 DISABLED
     * @return 影响行数，不存在时为 0
     */
    @Update(
            """
            UPDATE identity_users
            SET status = #{status},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status);
}
