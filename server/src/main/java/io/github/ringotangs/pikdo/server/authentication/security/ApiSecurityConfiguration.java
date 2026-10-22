package io.github.ringotangs.pikdo.server.authentication.security;

import io.github.ringotangs.pikdo.server.authentication.application.AuthenticationProperties;
import io.github.ringotangs.pikdo.server.authentication.application.AuthenticationRateLimiter;
import io.github.ringotangs.pikdo.server.identity.domain.UserRepository;
import io.github.ringotangs.pikdo.server.shared.web.error.CommonError;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorMessageResolver;
import io.github.ringotangs.pikdo.server.shared.web.request.ClientIpResolver;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class ApiSecurityConfiguration {
    @Bean
    ClientIpResolver clientIpResolver(Environment environment) {
        var proxies =
                Binder.get(environment)
                        .bind("pikdo.auth.trusted-proxies", Bindable.listOf(String.class))
                        .orElse(List.of());
        return new ClientIpResolver(proxies);
    }

    @Bean
    AuthenticationManager authenticationManager() {
        // 不启用 Boot 默认用户；验证码认证只通过受控应用服务建立上下文。
        return authentication -> {
            throw new BadCredentialsException("Unsupported authentication mechanism");
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
            UserRepository users,
            Clock clock,
            AuthenticationRateLimiter rates,
            AuthenticationProperties properties)
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
                // 仅加入 Security 链，不声明 Servlet Filter Bean，避免自动重复注册。
                .addFilterBefore(
                        new ApiRequestProtectionFilter(clientIps, rates, json),
                        SecurityContextHolderFilter.class)
                .addFilterBefore(
                        new ActiveUserSessionFilter(users, properties, clock, json),
                        AuthorizationFilter.class);
        return http.build();
    }
}
