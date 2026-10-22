package io.github.ringotangs.pikdo.server.authentication.security;

import io.github.ringotangs.pikdo.server.authentication.application.*;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;

import jakarta.servlet.http.*;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@Service
public final class AuthenticationSessionService {
    // 保留历史属性键，兼容重构前已经发送的验证码挑战。
    private static final String FLOW =
            "io.github.ringotangs.pikdo.server.identity.web.EmailAuthenticationController.flow";
    private final VerificationCodes codes;
    private final SessionAuthenticationStrategy strategy;
    private final SecurityContextRepository contexts;
    private final Clock clock;

    public AuthenticationSessionService(
            VerificationCodes codes,
            SessionAuthenticationStrategy strategy,
            SecurityContextRepository contexts,
            Clock clock) {
        this.codes = codes;
        this.strategy = strategy;
        this.contexts = contexts;
        this.clock = clock;
    }

    public void requireAnonymous() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal)
            throw new AuthenticationFailure(AuthenticationFailure.Reason.ALREADY_AUTHENTICATED);
    }

    public String getOrCreateFlow(HttpServletRequest request) {
        var session = request.getSession(true);
        var flow = session.getAttribute(FLOW);
        if (!(flow instanceof String)) {
            flow = codes.newFlow();
            session.setAttribute(FLOW, flow);
        }
        return (String) flow;
    }

    public String requireFlow(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(FLOW) instanceof String flow))
            throw new AuthenticationFailure(AuthenticationFailure.Reason.INVALID);
        return flow;
    }

    public CsrfToken csrfToken(HttpServletRequest request) {
        return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    }

    public UserPrincipal currentPrincipal() {
        return (UserPrincipal)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    public void establish(UserId user, HttpServletRequest request, HttpServletResponse response) {
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new UserPrincipal(user.value().toString(), clock.millis()),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
        try {
            strategy.onAuthentication(authentication, request, response);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.getSession().removeAttribute(FLOW);
            contexts.saveContext(context, request, response);
        } catch (RuntimeException failure) {
            SecurityContextHolder.clearContext();
            try {
                var current = request.getSession(false);
                if (current != null) current.invalidate();
            } catch (RuntimeException ignored) {
                /* 存储故障时仍拒绝认证成功响应。 */
            }
            throw new AuthenticationFailure(AuthenticationFailure.Reason.UNAVAILABLE);
        }
    }
}
