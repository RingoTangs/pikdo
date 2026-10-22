package io.github.ringotangs.pikdo.server.identity.security;

import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.domain.UserRepository;
import io.github.ringotangs.pikdo.server.identity.domain.UserStatus;
import io.github.ringotangs.pikdo.server.identity.web.AuthenticationError;
import io.github.ringotangs.pikdo.server.shared.web.error.CommonError;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorMessageResolver;
import io.github.ringotangs.pikdo.server.shared.web.request.ClientIpResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {
    @Bean
    ClientIpResolver clientIpResolver(Environment environment) {
        var proxies =
                Binder.get(environment)
                        .bind("pikdo.auth.trusted-proxies", Bindable.listOf(String.class))
                        .orElse(java.util.List.of());
        return new ClientIpResolver(proxies);
    }

    @Bean
    org.springframework.security.authentication.AuthenticationManager authenticationManager() {
        // 不启用 Boot 默认用户；验证码认证只通过受控应用服务建立上下文。
        return authentication -> {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "Unsupported authentication mechanism");
        };
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock authenticationClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecurityJson securityJson(ErrorMessageResolver messages, ObjectMapper json) {
        return new SecurityJson(messages, json);
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(
                List.of(
                        new ChangeSessionIdAuthenticationStrategy(),
                        new CsrfAuthenticationStrategy(csrf)));
    }

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ClientIpResolver clientIps,
            SecurityJson json,
            CsrfTokenRepository csrf,
            SecurityContextRepository contexts,
            ObjectProvider<UserRepository> users,
            Clock clock,
            ObjectProvider<
                            io.github.ringotangs.pikdo.server.identity.application
                                    .AuthenticationRateLimiter>
                    rates,
            ObjectProvider<
                            io.github.ringotangs.pikdo.server.identity.application
                                    .AuthenticationProperties>
                    properties)
            throws Exception {
        http.securityMatcher("/api/**")
                .csrf(c -> c.csrfTokenRepository(csrf))
                .securityContext(
                        c -> c.securityContextRepository(contexts).requireExplicitSave(true))
                .requestCache(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/api/v1/auth/csrf",
                                                "/api/v1/auth/registration-codes",
                                                "/api/v1/auth/registrations",
                                                "/api/v1/auth/login-codes",
                                                "/api/v1/auth/login")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                                (req, res, ex) ->
                                                        json.write(
                                                                req, res, CommonError.UNAUTHORIZED))
                                        .accessDeniedHandler(
                                                (req, res, ex) ->
                                                        json.write(
                                                                req, res, CommonError.FORBIDDEN)))
                .logout(
                        l ->
                                l.logoutUrl("/api/v1/auth/logout")
                                        .invalidateHttpSession(true)
                                        .clearAuthentication(true)
                                        .deleteCookies("__Host-PIKDOSESSION", "PIKDOSESSION")
                                        .logoutSuccessHandler(
                                                (req, res, auth) -> {
                                                    res.setHeader("Cache-Control", "no-store");
                                                    res.setStatus(204);
                                                }))
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            protected void doFilterInternal(
                                    HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
                                    throws ServletException, IOException {
                                res.setHeader("Cache-Control", "no-store");
                                if ("cross-site".equals(req.getHeader("Sec-Fetch-Site"))
                                        || !sameOrigin(req)) {
                                    json.write(req, res, CommonError.FORBIDDEN);
                                    return;
                                }
                                try {
                                    var limiter = rates.getIfAvailable();
                                    String path = req.getRequestURI();
                                    if (limiter != null && "POST".equals(req.getMethod())) {
                                        if (path.endsWith("/registration-codes")
                                                || path.endsWith("/login-codes"))
                                            limiter.request(clientIps.resolve(req), true);
                                        else if (path.endsWith("/registrations")
                                                || path.endsWith("/login"))
                                            limiter.request(clientIps.resolve(req), false);
                                    }
                                    chain.doFilter(req, res);
                                } catch (
                                        io.github.ringotangs.pikdo.server.identity.application
                                                        .AuthenticationFailure
                                                failure) {
                                    json.write(req, res, AuthenticationError.RATE_LIMITED);
                                } catch (DataAccessException failure) {
                                    SecurityContextHolder.clearContext();
                                    json.write(req, res, AuthenticationError.UNAVAILABLE);
                                }
                            }
                        },
                        SecurityContextHolderFilter.class)
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            protected void doFilterInternal(
                                    HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
                                    throws ServletException, IOException {
                                var authentication =
                                        SecurityContextHolder.getContext().getAuthentication();
                                if (authentication != null
                                        && authentication.getPrincipal()
                                                instanceof UserPrincipal principal) {
                                    var repository = users.getIfAvailable();
                                    var user =
                                            repository == null
                                                    ? java.util.Optional
                                                            .<io.github.ringotangs.pikdo.server
                                                                            .identity.domain.User>
                                                                    empty()
                                                    : repository.findById(
                                                            new UserId(
                                                                    UUID.fromString(
                                                                            principal.userId())));
                                    if (user.isEmpty()
                                            || user.get().getStatus() != UserStatus.ACTIVE
                                            || clock.millis() - principal.authenticatedAt()
                                                    >= (properties.getIfAvailable() == null
                                                                    ? Duration.ofHours(12)
                                                                    : properties
                                                                            .getObject()
                                                                            .absoluteSessionLifetime())
                                                            .toMillis()) {
                                        var session = req.getSession(false);
                                        if (session != null) session.invalidate();
                                        SecurityContextHolder.clearContext();
                                        json.write(req, res, CommonError.UNAUTHORIZED);
                                        return;
                                    }
                                }
                                chain.doFilter(req, res);
                            }
                        },
                        AuthorizationFilter.class);
        return http.build();
    }

    private static boolean sameOrigin(HttpServletRequest request) {
        var value = request.getHeader("Origin");
        if (value == null) return true;
        try {
            var origin = java.net.URI.create(value);
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
