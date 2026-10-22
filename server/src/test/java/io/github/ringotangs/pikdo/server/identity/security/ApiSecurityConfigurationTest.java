package io.github.ringotangs.pikdo.server.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.ringotangs.pikdo.server.identity.application.*;
import io.github.ringotangs.pikdo.server.identity.domain.UserRepository;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorMessageResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

class ApiSecurityConfigurationTest {
    ApplicationContextRunner context(Set<String> included) {
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(ApiSecurityConfiguration.class)
                        .withBean(HttpSecurity.class, ApiSecurityConfigurationTest::http)
                        .withBean(
                                ErrorMessageResolver.class, () -> mock(ErrorMessageResolver.class))
                        .withBean(ObjectMapper.class, ObjectMapper::new);
        if (included.contains("users"))
            runner = runner.withBean(UserRepository.class, () -> mock(UserRepository.class));
        if (included.contains("rates"))
            runner =
                    runner.withBean(
                            AuthenticationRateLimiter.class,
                            () -> mock(AuthenticationRateLimiter.class));
        if (included.contains("properties"))
            runner =
                    runner.withBean(
                            AuthenticationProperties.class,
                            () -> mock(AuthenticationProperties.class));
        return runner;
    }

    static HttpSecurity http() {
        try {
            var http = mock(HttpSecurity.class, RETURNS_SELF);
            when(http.build())
                    .thenReturn(new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE));
            return http;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void allRequiredDependenciesAllowStartup() {
        context(Set.of("users", "rates", "properties"))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            assertEquals(
                                    1, context.getBeansOfType(SecurityFilterChain.class).size());
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "rates", "properties"})
    void missingSecurityDependencyPreventsStartup(String missing) {
        var included = new HashSet<>(Set.of("users", "rates", "properties"));
        included.remove(missing);
        context(included)
                .run(
                        context -> {
                            Throwable failure = context.getStartupFailure();
                            assertNotNull(failure);
                            while (failure.getCause() != null) failure = failure.getCause();
                            assertInstanceOf(NoSuchBeanDefinitionException.class, failure);
                            String expected =
                                    switch (missing) {
                                        case "users" -> UserRepository.class.getName();
                                        case "rates" -> AuthenticationRateLimiter.class.getName();
                                        default -> AuthenticationProperties.class.getName();
                                    };
                            assertTrue(failure.getMessage().contains(expected));
                        });
    }
}
