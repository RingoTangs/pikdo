package io.github.ringotangs.pikdo.server.authentication.persistence;

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.ObjectTypeHandler;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// 行锁串行化消费，失败次数也必须提交。
@Mapper
public interface EmailChallengeMapper {
    @Insert(
            """
        INSERT INTO identity_email_challenges(id,purpose,canonical_email,original_email,flow_digest,code_digest,eligible,attempts,max_attempts,expires_at,account_id,user_id)
        VALUES(#{id,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler},#{purpose},#{canonicalEmail},#{originalEmail},#{flowDigest},#{codeDigest},#{eligible},0,#{maxAttempts},#{expiresAt},#{accountId,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler},#{userId,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler})
        """)
    int insert(EmailChallengeRow row);

    @Select(
            """
        SELECT id,purpose,canonical_email,original_email,flow_digest,code_digest,eligible,attempts,max_attempts,expires_at,consumed_at,invalidated_at,account_id,user_id
        FROM identity_email_challenges WHERE id=#{id,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler} FOR UPDATE
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "purpose", javaType = String.class),
        @Arg(column = "canonical_email", javaType = String.class),
        @Arg(column = "original_email", javaType = String.class),
        @Arg(column = "flow_digest", javaType = byte[].class),
        @Arg(column = "code_digest", javaType = byte[].class),
        @Arg(column = "eligible", javaType = boolean.class),
        @Arg(column = "attempts", javaType = int.class),
        @Arg(column = "max_attempts", javaType = int.class),
        @Arg(column = "expires_at", javaType = OffsetDateTime.class),
        @Arg(column = "consumed_at", javaType = OffsetDateTime.class),
        @Arg(column = "invalidated_at", javaType = OffsetDateTime.class),
        @Arg(column = "account_id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class),
        @Arg(column = "user_id", javaType = UUID.class, typeHandler = ObjectTypeHandler.class)
    })
    Optional<EmailChallengeRow> lockById(@Param("id") UUID id);

    @Update(
            "UPDATE identity_email_challenges SET invalidated_at=#{now} WHERE flow_digest=#{flow} AND canonical_email=#{email} AND purpose=#{purpose} AND consumed_at IS NULL AND invalidated_at IS NULL")
    int invalidateFlow(
            @Param("flow") byte[] flow,
            @Param("email") String email,
            @Param("purpose") String purpose,
            @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE identity_email_challenges SET invalidated_at=#{now} WHERE id=#{id,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler} AND consumed_at IS NULL")
    int invalidate(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE identity_email_challenges SET attempts=attempts+1, invalidated_at=CASE WHEN attempts+1>=max_attempts THEN #{now} ELSE invalidated_at END WHERE id=#{id,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler} AND attempts<max_attempts")
    int failedAttempt(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Update(
            "UPDATE identity_email_challenges SET consumed_at=#{now} WHERE id=#{id,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler} AND consumed_at IS NULL AND invalidated_at IS NULL AND attempts<max_attempts AND expires_at>#{now}")
    int consume(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    @Select(
            "SELECT id::text FROM identity_users WHERE id=#{id,jdbcType=OTHER,typeHandler=org.apache.ibatis.type.ObjectTypeHandler} FOR UPDATE")
    Optional<String> lockUser(@Param("id") UUID id);

    @Delete("DELETE FROM identity_email_challenges WHERE expires_at<#{cutoff}")
    int cleanup(OffsetDateTime cutoff);
}
