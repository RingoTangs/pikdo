package io.github.ringotangs.pikdo.server.identity.security;

import java.io.Serializable;
import java.security.Principal;

public record UserPrincipal(String userId, long authenticatedAt)
        implements Principal, Serializable {
    @Override
    public String getName() {
        return userId;
    }
}
