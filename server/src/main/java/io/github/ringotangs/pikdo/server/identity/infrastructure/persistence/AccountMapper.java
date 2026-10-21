package io.github.ringotangs.pikdo.server.identity.infrastructure.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface AccountMapper {
    @Insert(
            """
            INSERT INTO identity_accounts (id, user_id, original_address, canonical_address)
            VALUES (#{id}, #{userId}, #{originalAddress}, #{canonicalAddress})
            """)
    int insert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("originalAddress") String originalAddress,
            @Param("canonicalAddress") String canonicalAddress);

    @Select(
            """
            SELECT id, user_id, original_address, canonical_address, created_at
            FROM identity_accounts
            WHERE id = #{id}
            """)
    @Results(id = "accountRow")
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "user_id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "original_address", javaType = String.class),
        @Arg(column = "canonical_address", javaType = String.class),
        @Arg(column = "created_at", javaType = OffsetDateTime.class)
    })
    Optional<AccountRow> findById(@Param("id") UUID id);

    @Select(
            """
            SELECT id, user_id, original_address, canonical_address, created_at
            FROM identity_accounts
            WHERE canonical_address = #{canonicalAddress}
            """)
    @ResultMap("accountRow")
    Optional<AccountRow> findByCanonicalAddress(@Param("canonicalAddress") String canonicalAddress);
}
