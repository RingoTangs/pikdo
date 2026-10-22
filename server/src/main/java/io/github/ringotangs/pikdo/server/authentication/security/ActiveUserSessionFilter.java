package io.github.ringotangs.pikdo.server.authentication.security;

import io.github.ringotangs.pikdo.server.authentication.application.AuthenticationProperties;
import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.shared.web.error.CommonError;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

public final class ActiveUserSessionFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final AuthenticationProperties properties;
    private final Clock clock;
    private final SecurityJson json;

    public ActiveUserSessionFilter(
            UserRepository users,
            AuthenticationProperties properties,
            Clock clock,
            SecurityJson json) {
        this.users = users;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal()
                        instanceof UserPrincipal(String userId, long authenticatedAt)) {
            var user = users.findById(new UserId(UUID.fromString(userId)));
            if (user.isEmpty()
                    || user.get().getStatus() != UserStatus.ACTIVE
                    || clock.millis() - authenticatedAt
                            >= properties.absoluteSessionLifetime().toMillis()) {
                var session = req.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                json.write(req, res, CommonError.UNAUTHORIZED);
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
