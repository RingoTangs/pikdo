package io.github.ringotangs.pikdo.server.identity.web;

import io.github.ringotangs.pikdo.server.identity.web.dto.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 邮箱认证接口：仅负责请求校验、路由与 Web 用例委托。 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "pikdo.auth.enabled", havingValue = "true", matchIfMissing = true)
public final class EmailAuthenticationController {
    private final EmailAuthenticationWebService service;

    public EmailAuthenticationController(EmailAuthenticationWebService service) {
        this.service = service;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(HttpServletRequest request) {
        return service.csrf(request);
    }

    @PostMapping("/registration-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IssuedCodeResponse registrationCode(
            @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        return service.registrationCode(body, request);
    }

    @PostMapping("/login-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IssuedCodeResponse loginCode(
            @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        return service.loginCode(body, request);
    }

    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            @Valid @RequestBody VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.register(body, request, response);
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(
            @Valid @RequestBody VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.login(body, request, response);
    }

    @GetMapping("/me")
    public CurrentUserResponse me() {
        return service.me();
    }
}
