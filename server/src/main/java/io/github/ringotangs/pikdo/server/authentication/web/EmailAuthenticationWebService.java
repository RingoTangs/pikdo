package io.github.ringotangs.pikdo.server.authentication.web;

import io.github.ringotangs.pikdo.server.authentication.application.*;
import io.github.ringotangs.pikdo.server.authentication.domain.CodePurpose;
import io.github.ringotangs.pikdo.server.authentication.security.AuthenticationSessionService;
import io.github.ringotangs.pikdo.server.authentication.web.dto.*;
import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;
import io.github.ringotangs.pikdo.server.shared.web.request.ClientIpResolver;

import jakarta.servlet.http.*;

import org.springframework.stereotype.Service;

@Service
public final class EmailAuthenticationWebService {
    private final EmailAuthenticationService service;
    private final AuthenticationSessionService sessions;
    private final AuthenticationRateLimiter rates;
    private final ClientIpResolver clientIps;

    public EmailAuthenticationWebService(
            EmailAuthenticationService service,
            AuthenticationSessionService sessions,
            AuthenticationRateLimiter rates,
            ClientIpResolver clientIps) {
        this.service = service;
        this.sessions = sessions;
        this.rates = rates;
        this.clientIps = clientIps;
    }

    public CsrfResponse csrf(HttpServletRequest request) {
        rates.csrf(clientIps.resolve(request));
        var token = sessions.csrfToken(request);
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    public IssuedCodeResponse registrationCode(EmailRequest body, HttpServletRequest request) {
        return send(CodePurpose.REGISTER, body, request);
    }

    public IssuedCodeResponse loginCode(EmailRequest body, HttpServletRequest request) {
        return send(CodePurpose.LOGIN, body, request);
    }

    public void register(
            VerificationRequest body, HttpServletRequest request, HttpServletResponse response) {
        complete(CodePurpose.REGISTER, body, request, response);
    }

    public void login(
            VerificationRequest body, HttpServletRequest request, HttpServletResponse response) {
        complete(CodePurpose.LOGIN, body, request, response);
    }

    public CurrentUserResponse me() {
        return new CurrentUserResponse(sessions.currentPrincipal().userId(), "ACTIVE");
    }

    private IssuedCodeResponse send(
            CodePurpose purpose, EmailRequest body, HttpServletRequest request) {
        sessions.requireAnonymous();
        String flow = sessions.getOrCreateFlow(request);
        var issued =
                service.send(
                        purpose,
                        EmailAddress.create(body.email()),
                        flow,
                        clientIps.resolve(request));
        return new IssuedCodeResponse(
                issued.challengeId(), issued.expiresAt(), issued.retryAfterSeconds());
    }

    private void complete(
            CodePurpose purpose,
            VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        sessions.requireAnonymous();
        String flow = sessions.requireFlow(request);
        var user =
                service.complete(
                        purpose,
                        body.challengeId(),
                        EmailAddress.create(body.email()),
                        body.code(),
                        flow,
                        clientIps.resolve(request));
        // 核心事务已经提交，Session 保存失败不能回滚已创建的用户。
        sessions.establish(user, request, response);
    }
}
