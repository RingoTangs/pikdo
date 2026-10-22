package io.github.ringotangs.pikdo.server.authentication.web;

import io.github.ringotangs.pikdo.server.authentication.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "邮箱认证", description = "邮箱验证码注册与登录，使用 Cookie Session 和 CSRF 防护")
@RestController
@RequestMapping("/api/v1/auth")
public final class EmailAuthenticationController {
    private final EmailAuthenticationWebService service;

    public EmailAuthenticationController(EmailAuthenticationWebService service) {
        this.service = service;
    }

    @Operation(
            summary = "获取 CSRF Token",
            description =
                    "先调用此接口并保留浏览器 Cookie，将响应中的 headerName 与 token 用于后续 POST 请求；认证成功或退出后需重新获取。")
    @ApiResponse(
            responseCode = "200",
            description = "返回 CSRF 请求头名称和 Token",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CsrfResponse.class)))
    @ApiResponse(responseCode = "403", ref = "#/components/responses/IdentityForbidden")
    @ApiResponse(responseCode = "429", ref = "#/components/responses/IdentityTooManyRequests")
    @ApiResponse(responseCode = "503", ref = "#/components/responses/IdentityUnavailable")
    @GetMapping("/csrf")
    public CsrfResponse csrf(HttpServletRequest request) {
        return service.csrf(request);
    }

    @Operation(
            summary = "发送注册验证码",
            description = "验证码五分钟有效。统一返回挑战信息，不表示邮箱是否已注册或邮件投递成功；验证码仅用于当前浏览器的注册流程。")
    @ApiResponse(
            responseCode = "202",
            description = "请求已受理，返回挑战信息",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = IssuedCodeResponse.class)))
    @ApiResponse(responseCode = "400", ref = "#/components/responses/IdentityBadRequest")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/IdentityForbidden")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/IdentityConflict")
    @ApiResponse(responseCode = "429", ref = "#/components/responses/IdentityTooManyRequests")
    @ApiResponse(responseCode = "503", ref = "#/components/responses/IdentityUnavailable")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            description = "从 CSRF 接口取得的 Token",
            schema = @Schema(type = "string"))
    @PostMapping("/registration-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IssuedCodeResponse registrationCode(
            @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        return service.registrationCode(body, request);
    }

    @Operation(summary = "发送登录验证码", description = "向已绑定的原始邮箱异步投递验证码；不存在或不可用的邮箱也返回相同结构，不公开账号状态。")
    @ApiResponse(
            responseCode = "202",
            description = "请求已受理，返回挑战信息",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = IssuedCodeResponse.class)))
    @ApiResponse(responseCode = "400", ref = "#/components/responses/IdentityBadRequest")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/IdentityForbidden")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/IdentityConflict")
    @ApiResponse(responseCode = "429", ref = "#/components/responses/IdentityTooManyRequests")
    @ApiResponse(responseCode = "503", ref = "#/components/responses/IdentityUnavailable")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            description = "从 CSRF 接口取得的 Token",
            schema = @Schema(type = "string"))
    @PostMapping("/login-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IssuedCodeResponse loginCode(
            @Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        return service.loginCode(body, request);
    }

    @Operation(
            summary = "完成邮箱注册",
            description = "验证当前浏览器的注册挑战，成功后自动登录；不创建密码凭据。认证成功后请重新获取 CSRF Token。")
    @ApiResponse(responseCode = "204", description = "注册成功并建立 Session，无响应体", content = @Content)
    @ApiResponse(responseCode = "400", ref = "#/components/responses/IdentityBadRequest")
    @ApiResponse(responseCode = "401", ref = "#/components/responses/IdentityUnauthorized")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/IdentityForbidden")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/IdentityConflict")
    @ApiResponse(responseCode = "429", ref = "#/components/responses/IdentityTooManyRequests")
    @ApiResponse(responseCode = "503", ref = "#/components/responses/IdentityUnavailable")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            description = "从 CSRF 接口取得的 Token",
            schema = @Schema(type = "string"))
    @PostMapping("/registrations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(
            @Valid @RequestBody VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.register(body, request, response);
    }

    @Operation(
            summary = "完成邮箱登录",
            description = "验证码必须匹配当前浏览器的登录挑战，且未过期或被使用；成功后轮换 Session ID，并重新获取 CSRF Token。")
    @ApiResponse(responseCode = "204", description = "登录成功并保存 Session，无响应体", content = @Content)
    @ApiResponse(responseCode = "400", ref = "#/components/responses/IdentityBadRequest")
    @ApiResponse(responseCode = "401", ref = "#/components/responses/IdentityUnauthorized")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/IdentityForbidden")
    @ApiResponse(responseCode = "409", ref = "#/components/responses/IdentityConflict")
    @ApiResponse(responseCode = "429", ref = "#/components/responses/IdentityTooManyRequests")
    @ApiResponse(responseCode = "503", ref = "#/components/responses/IdentityUnavailable")
    @Parameter(
            name = "X-CSRF-TOKEN",
            in = ParameterIn.HEADER,
            required = true,
            description = "从 CSRF 接口取得的 Token",
            schema = @Schema(type = "string"))
    @PostMapping("/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(
            @Valid @RequestBody VerificationRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        service.login(body, request, response);
    }

    @Operation(summary = "查询当前登录用户", description = "浏览器须携带登录后建立的 Session Cookie；返回 UserId 和启用状态。")
    @ApiResponse(
            responseCode = "200",
            description = "返回当前用户",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CurrentUserResponse.class)))
    @ApiResponse(responseCode = "401", ref = "#/components/responses/IdentityUnauthorized")
    @ApiResponse(responseCode = "403", ref = "#/components/responses/IdentityForbidden")
    @ApiResponse(responseCode = "503", ref = "#/components/responses/IdentityUnavailable")
    @SecurityRequirement(name = "SessionCookie")
    @GetMapping("/me")
    public CurrentUserResponse me() {
        return service.me();
    }
}
