package io.github.ringotangs.pikdo.server.identity.security;

import java.io.Serializable;
import java.security.Principal;

/** Session 只保存稳定 UserId 和认证时间，不序列化领域对象或认证秘密。 */
public record UserPrincipal(String userId, long authenticatedAt)
        implements Principal, Serializable {
    @Override
    public String getName() {
        return userId;
    }
}
