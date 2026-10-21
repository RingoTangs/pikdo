package io.github.ringotangs.pikdo.server.identity.infrastructure.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface PasswordCredentialMapper {
    @Insert(
            """
            INSERT INTO identity_password_credentials (user_id, password_hash, password_input_version)
            VALUES (#{userId}, #{passwordHash}, #{passwordInputVersion})
            """)
    int insert(
            @Param("userId") UUID userId,
            @Param("passwordHash") String passwordHash,
            @Param("passwordInputVersion") String passwordInputVersion);

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

    /** Returns zero when the user is absent or the expected version is stale; never retries. */
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
