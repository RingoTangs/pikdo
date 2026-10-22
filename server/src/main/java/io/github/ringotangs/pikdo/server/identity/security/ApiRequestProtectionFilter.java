package io.github.ringotangs.pikdo.server.identity.security;

import io.github.ringotangs.pikdo.server.identity.application.AuthenticationFailure;
import io.github.ringotangs.pikdo.server.identity.application.AuthenticationRateLimiter;
import io.github.ringotangs.pikdo.server.identity.web.AuthenticationError;
import io.github.ringotangs.pikdo.server.shared.web.error.CommonError;
import io.github.ringotangs.pikdo.server.shared.web.request.ClientIpResolver;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

public final class ApiRequestProtectionFilter extends OncePerRequestFilter {
    private final ClientIpResolver clientIps;
    private final AuthenticationRateLimiter rates;
    private final SecurityJson json;

    public ApiRequestProtectionFilter(
            ClientIpResolver clientIps, AuthenticationRateLimiter rates, SecurityJson json) {
        this.clientIps = clientIps;
        this.rates = rates;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Cache-Control", "no-store");
        if ("cross-site".equals(req.getHeader("Sec-Fetch-Site")) || !sameOrigin(req)) {
            json.write(req, res, CommonError.FORBIDDEN);
            return;
        }
        try {
            String path = req.getRequestURI();
            if ("POST".equals(req.getMethod())) {
                if (path.endsWith("/registration-codes") || path.endsWith("/login-codes"))
                    rates.request(clientIps.resolve(req), true);
                else if (path.endsWith("/registrations") || path.endsWith("/login"))
                    rates.request(clientIps.resolve(req), false);
            }
            chain.doFilter(req, res);
        } catch (AuthenticationFailure failure) {
            json.write(req, res, AuthenticationError.RATE_LIMITED);
        } catch (DataAccessException failure) {
            SecurityContextHolder.clearContext();
            json.write(req, res, AuthenticationError.UNAVAILABLE);
        }
    }

    private static boolean sameOrigin(HttpServletRequest request) {
        var value = request.getHeader("Origin");
        if (value == null) return true;
        try {
            var origin = URI.create(value);
            int port =
                    origin.getPort() < 0
                            ? ("https".equals(origin.getScheme()) ? 443 : 80)
                            : origin.getPort();
            return request.getScheme().equals(origin.getScheme())
                    && request.getServerName().equalsIgnoreCase(origin.getHost())
                    && request.getServerPort() == port
                    && origin.getUserInfo() == null
                    && (origin.getPath() == null || origin.getPath().isEmpty())
                    && origin.getQuery() == null
                    && origin.getFragment() == null;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
