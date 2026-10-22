package io.github.ringotangs.pikdo.server.identity.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface AccountMapper {
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
