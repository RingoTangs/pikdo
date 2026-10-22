package io.github.ringotangs.pikdo.server.authentication.security;

import java.io.Serializable;
import java.security.Principal;

public record UserPrincipal(String userId, long authenticatedAt)
        implements Principal, Serializable {
    @Override
    public String getName() {
        return userId;
    }
}
