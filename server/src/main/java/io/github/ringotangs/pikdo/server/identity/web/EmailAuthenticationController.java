package io.github.ringotangs.pikdo.server.identity.web;

import io.github.ringotangs.pikdo.server.identity.application.*;
import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;
import io.github.ringotangs.pikdo.server.identity.security.UserPrincipal;
import io.github.ringotangs.pikdo.server.shared.web.request.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** HTTP 适配：事务完成后才建立会话，验证码和流程秘密不写入响应。 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "pikdo.auth.enabled", havingValue = "true", matchIfMissing = true)
public final class EmailAuthenticationController {
    private static final String FLOW = EmailAuthenticationController.class.getName() + ".flow";
    private final EmailAuthenticationService service;
    private final VerificationCodes codes;
    private final AuthenticationRateLimiter rates;
    private final SessionAuthenticationStrategy strategy;
    private final SecurityContextRepository contexts;
    private final Clock clock;
    private final ClientIpResolver clientIps;

    public EmailAuthenticationController(
            EmailAuthenticationService service,
            VerificationCodes codes,
            AuthenticationRateLimiter rates,
            SessionAuthenticationStrategy strategy,
            SecurityContextRepository contexts,
            Clock clock,
            ClientIpResolver clientIps) {
        this.service = service;
        this.codes = codes;
        this.rates = rates;
        this.strategy = strategy;
        this.contexts = contexts;
        this.clock = clock;
        this.clientIps = clientIps;
    }

    public record EmailRequest(@NotBlank @Size(max = 254) String email) {}

    public record VerificationRequest(
            @NotNull UUID challengeId,
            @NotBlank @Size(max = 254) String email,
            @NotNull @Pattern(regexp = "[0-9]{6}") String code) {
        @Override
        public String toString() {
            return "VerificationRequest[redacted]";
        }
    }

    public record CsrfResponse(String headerName, String token) {}

    public record CurrentUser(String userId, String status) {}

    @GetMapping("/csrf")
    public CsrfResponse csrf(HttpServletRequest request) {
        rates.csrf(clientIps.resolve(request));
        var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/registration-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EmailAuthenticationService.IssuedCode registrationCode(
            @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        return send(CodePurpose.REGISTER, body, request);
    }

    @PostMapping("/login-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EmailAuthenticationService.IssuedCode loginCode(
            @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        return send(CodePurpose.LOGIN, body, request);
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            @Valid @RequestBody VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        complete(CodePurpose.REGISTER, body, request, response);
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(
            @Valid @RequestBody VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        complete(CodePurpose.LOGIN, body, request, response);
    }

    @GetMapping("/me")
    public CurrentUser me() {
        var principal =
                (UserPrincipal)
                        SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return new CurrentUser(principal.userId(), "ACTIVE");
    }

    private EmailAuthenticationService.IssuedCode send(
            CodePurpose purpose, EmailRequest body, HttpServletRequest request) {
        anonymous();
        var session = request.getSession(true);
        var flow = session.getAttribute(FLOW);
        if (!(flow instanceof String)) {
            flow = codes.newFlow();
            session.setAttribute(FLOW, flow);
        }
        return service.send(
                purpose,
                EmailAddress.create(body.email()),
                (String) flow,
                clientIps.resolve(request));
    }

    private void complete(
            CodePurpose purpose,
            VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        anonymous();
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(FLOW) instanceof String flow))
            throw new AuthenticationFailure(AuthenticationFailure.Reason.INVALID);
        var user =
                service.complete(
                        purpose,
                        body.challengeId(),
                        EmailAddress.create(body.email()),
                        body.code(),
                        flow,
                        clientIps.resolve(request));
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

    private void anonymous() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal)
            throw new AuthenticationFailure(AuthenticationFailure.Reason.ALREADY_AUTHENTICATED);
    }
}
