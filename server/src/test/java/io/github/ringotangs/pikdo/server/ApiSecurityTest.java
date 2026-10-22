package io.github.ringotangs.pikdo.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 安全过滤链本身保留运行，不依赖数据库或测试用户绕过认证。 */
@WithoutDatabase
@SpringBootTest
@AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void anonymousApiIsJsonUnauthorized() throws Exception {
        mvc.perform(get("/api/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-ID"));
    }

    @Test
    void publicAuthenticationPostsStillRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    }

    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void disabledAuthenticationDoesNotRegisterDedicatedComponentsOrEndpoints() throws Exception {
        for (Class<?> type :
                java.util.List.of(
                        io.github.ringotangs.pikdo.server.identity.web.EmailAuthenticationController
                                .class,
                        io.github.ringotangs.pikdo.server.identity.web.EmailAuthenticationWebService
                                .class,
                        io.github.ringotangs.pikdo.server.identity.security
                                .AuthenticationSessionService.class,
                        io.github.ringotangs.pikdo.server.identity.application
                                .EmailAuthenticationService.class,
                        io.github.ringotangs.pikdo.server.identity.application
                                .AuthenticationRateLimiter.class,
                        io.github.ringotangs.pikdo.server.identity.application.AuthenticationCleanup
                                .class,
                        io.github.ringotangs.pikdo.server.identity.application.VerificationCodes
                                .class,
                        io.github.ringotangs.pikdo.server.identity.mail.AsyncEmailDelivery.class,
                        io.github.ringotangs.pikdo.server.identity.mail.EmailCodeSender.class,
                        io.github.ringotangs.pikdo.server.identity.domain.UserRepository.class,
                        io.github.ringotangs.pikdo.server.identity.domain.AccountRepository.class,
                        io.github.ringotangs.pikdo.server.identity.application
                                .AuthenticationProperties.class))
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(type).isEmpty(), type.getSimpleName());
        org.junit.jupiter.api.Assertions.assertFalse(
                context.containsBean("authenticationMailExecutor"));
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNotFound());
    }
}
