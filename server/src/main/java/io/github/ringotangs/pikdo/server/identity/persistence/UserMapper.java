package io.github.ringotangs.pikdo.server.identity.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface UserMapper {
    @Insert(
            """
            INSERT INTO identity_users (id, status)
            VALUES (#{id}, #{status})
            """)
    int insert(@Param("id") UUID id, @Param("status") String status);

    // PostgreSQL JDBC returns native UUID values via getObject; no custom ID handler is needed.
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

    @Update(
            """
            UPDATE identity_users
            SET status = #{status},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status);
}
