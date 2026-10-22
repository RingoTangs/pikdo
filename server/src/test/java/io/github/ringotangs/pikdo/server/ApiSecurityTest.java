package io.github.ringotangs.pikdo.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.ringotangs.pikdo.server.authentication.application.AuthenticationCleanup;
import io.github.ringotangs.pikdo.server.authentication.application.AuthenticationProperties;
import io.github.ringotangs.pikdo.server.authentication.application.AuthenticationRateLimiter;
import io.github.ringotangs.pikdo.server.authentication.application.EmailAuthenticationService;
import io.github.ringotangs.pikdo.server.authentication.application.VerificationCodes;
import io.github.ringotangs.pikdo.server.authentication.mail.AsyncEmailDelivery;
import io.github.ringotangs.pikdo.server.authentication.mail.EmailCodeSender;
import io.github.ringotangs.pikdo.server.authentication.security.ActiveUserSessionFilter;
import io.github.ringotangs.pikdo.server.authentication.security.ApiRequestProtectionFilter;
import io.github.ringotangs.pikdo.server.authentication.security.AuthenticationSessionService;
import io.github.ringotangs.pikdo.server.authentication.web.EmailAuthenticationController;
import io.github.ringotangs.pikdo.server.authentication.web.EmailAuthenticationWebService;
import io.github.ringotangs.pikdo.server.identity.domain.AccountRepository;
import io.github.ringotangs.pikdo.server.identity.domain.UserRepository;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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

    @Autowired ApplicationContext context;

    @Test
    void authenticationComponentsRemainRegisteredWithoutDatabase() throws Exception {
        for (Class<?> type :
                List.of(
                        EmailAuthenticationController.class,
                        EmailAuthenticationWebService.class,
                        AuthenticationSessionService.class,
                        EmailAuthenticationService.class,
                        AuthenticationRateLimiter.class,
                        AuthenticationCleanup.class,
                        VerificationCodes.class,
                        AsyncEmailDelivery.class,
                        EmailCodeSender.class,
                        UserRepository.class,
                        AccountRepository.class,
                        AuthenticationProperties.class))
            Assertions.assertTrue(context.getBeansOfType(type).size() == 1, type.getSimpleName());
        Assertions.assertTrue(context.containsBean("authenticationMailExecutor"));
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk());
    }

    @Autowired AuthenticationRateLimiter rates;
    @Autowired FilterChainProxy securityChain;

    @Test
    void filtersExistOnlyOnceInSecurityChainAndHaveNoServletBeanRegistration() throws Exception {
        Assertions.assertTrue(context.getBeansOfType(ApiRequestProtectionFilter.class).isEmpty());
        Assertions.assertTrue(context.getBeansOfType(ActiveUserSessionFilter.class).isEmpty());
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.setServletPath("/api/v1/auth/me");
        var chain =
                securityChain.getFilterChains().stream()
                        .filter(candidate -> candidate.matches(request))
                        .findFirst()
                        .orElseThrow();
        var filters = chain.getFilters();
        Assertions.assertEquals(
                1, filters.stream().filter(ApiRequestProtectionFilter.class::isInstance).count());
        Assertions.assertEquals(
                1, filters.stream().filter(ActiveUserSessionFilter.class::isInstance).count());
        int protection = -1, holder = -1, active = -1, authorization = -1;
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) instanceof ApiRequestProtectionFilter) protection = i;
            if (filters.get(i) instanceof SecurityContextHolderFilter) holder = i;
            if (filters.get(i) instanceof ActiveUserSessionFilter) active = i;
            if (filters.get(i) instanceof AuthorizationFilter) authorization = i;
        }
        Assertions.assertTrue(protection < holder && holder < active && active < authorization);
        for (var registration : context.getBeansOfType(FilterRegistrationBean.class).values())
            Assertions.assertFalse(
                    registration.getFilter() instanceof ApiRequestProtectionFilter
                            || registration.getFilter() instanceof ActiveUserSessionFilter);
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        Mockito.verify(rates, Mockito.times(1)).request("127.0.0.1", false);
        Mockito.verifyNoMoreInteractions(rates);
    }
}
