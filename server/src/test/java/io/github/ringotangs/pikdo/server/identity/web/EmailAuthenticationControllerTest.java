package io.github.ringotangs.pikdo.server.identity.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.ringotangs.pikdo.server.identity.security.ApiSecurityConfiguration;
import io.github.ringotangs.pikdo.server.identity.web.dto.*;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorMessageResolver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

/** 真实 MVC 参数绑定及校验；协调服务替身不会访问数据库或建立真实认证。 */
@WebMvcTest(value = EmailAuthenticationController.class, properties = "pikdo.auth.enabled=true")
@Import({ApiSecurityConfiguration.class, ErrorMessageResolver.class})
class EmailAuthenticationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EmailAuthenticationWebService service;

    @Test
    void publicGetDelegatesAndPreservesResponse() throws Exception {
        when(service.csrf(any())).thenReturn(new CsrfResponse("X-CSRF-TOKEN", "token"));
        mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        "{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"token\"}",
                                        JsonCompareMode.STRICT));
        verify(service).csrf(any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void codeRequestsDelegateToTheirSeparateUseCases() throws Exception {
        var id = UUID.randomUUID();
        var response = new IssuedCodeResponse(id, Instant.parse("2026-10-22T00:05:00Z"), 60);
        when(service.registrationCode(any(), any())).thenReturn(response);
        when(service.loginCode(any(), any())).thenReturn(response);
        for (String route : new String[] {"registration-codes", "login-codes"})
            mvc.perform(
                            post("/api/v1/auth/" + route)
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content("{\"email\":\"Original@Example.com\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(
                            content()
                                    .json(
                                            "{\"challengeId\":\""
                                                    + id
                                                    + "\",\"expiresAt\":\"2026-10-22T00:05:00Z\",\"retryAfterSeconds\":60}",
                                            JsonCompareMode.STRICT));
        verify(service).registrationCode(eq(new EmailRequest("Original@Example.com")), any());
        verify(service).loginCode(eq(new EmailRequest("Original@Example.com")), any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void completionDelegatesWithoutResponseBody() throws Exception {
        var id = UUID.randomUUID();
        String body =
                "{\"challengeId\":\"" + id + "\",\"email\":\"a@example.com\",\"code\":\"123456\"}";
        for (String route : new String[] {"registrations", "login"})
            mvc.perform(
                            post("/api/v1/auth/" + route)
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        var dto = new VerificationRequest(id, "a@example.com", "123456");
        verify(service).register(eq(dto), any(), any());
        verify(service).login(eq(dto), any(), any());
        verifyNoMoreInteractions(service);
    }

    @Test
    @WithMockUser
    void currentUserDelegates() throws Exception {
        when(service.me()).thenReturn(new CurrentUserResponse("user-id", "ACTIVE"));
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        "{\"userId\":\"user-id\",\"status\":\"ACTIVE\"}",
                                        JsonCompareMode.STRICT));
        verify(service).me();
    }

    @Test
    void malformedRequestsNeverReachCoordinator() throws Exception {
        for (String body :
                new String[] {"{}", "{\"email\":\"\"}", "{\"email\":\"" + "a".repeat(255) + "\"}"})
            mvc.perform(
                            post("/api/v1/auth/registration-codes")
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest());
        for (String code : new String[] {"12345", "abcdef", "１２３４５６"})
            mvc.perform(
                            post("/api/v1/auth/login")
                                    .with(csrf())
                                    .contentType("application/json")
                                    .content(
                                            "{\"challengeId\":\""
                                                    + UUID.randomUUID()
                                                    + "\",\"email\":\"a@example.com\",\"code\":\""
                                                    + code
                                                    + "\"}"))
                    .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
