package io.github.ringotangs.pikdo.server.identity.persistence;

import org.apache.ibatis.annotations.*;

import java.time.OffsetDateTime;
import java.util.Optional;

// 冷却时间独立于小时预算，避免窗口边界连续发码。
@Mapper
public interface AuthenticationRateMapper {
    @Select(
            affectData = true,
            value =
                    """
        INSERT INTO identity_auth_rate_limits AS budget(scope,subject_digest,window_started_at,expires_at,next_allowed_at,request_count)
        VALUES(#{scope},#{subject},#{now},#{end},#{next},1)
        ON CONFLICT(scope,subject_digest) DO UPDATE SET
            window_started_at=CASE WHEN budget.expires_at<=#{now} THEN #{now} ELSE budget.window_started_at END,
            expires_at=CASE WHEN budget.expires_at<=#{now} THEN #{end} ELSE budget.expires_at END,
            request_count=CASE WHEN budget.expires_at<=#{now} THEN 1 ELSE budget.request_count+1 END,
            next_allowed_at=#{next}
        WHERE budget.next_allowed_at<=#{now} AND (budget.expires_at<=#{now} OR budget.request_count<#{limit})
        RETURNING request_count
        """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Optional<Integer> reserve(
            @Param("scope") String scope,
            @Param("subject") byte[] subject,
            @Param("now") OffsetDateTime now,
            @Param("end") OffsetDateTime end,
            @Param("next") OffsetDateTime next,
            @Param("limit") int limit);

    @Delete(
            "DELETE FROM identity_auth_rate_limits WHERE expires_at<#{now} AND next_allowed_at<#{now}")
    int cleanup(OffsetDateTime now);
}
