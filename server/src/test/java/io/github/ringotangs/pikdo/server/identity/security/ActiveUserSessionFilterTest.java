package io.github.ringotangs.pikdo.server.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.ringotangs.pikdo.server.identity.application.AuthenticationProperties;
import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorMessageResolver;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

class ActiveUserSessionFilterTest {
    final UserRepository users = mock(UserRepository.class);
    final AuthenticationProperties properties = mock(AuthenticationProperties.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
    final FilterChain chain = mock(FilterChain.class);
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final ObjectMapper mapper = new ObjectMapper();
    ActiveUserSessionFilter filter;
    final UserId id = new UserId(UUID.randomUUID());

    @BeforeEach
    void setup() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        when(properties.absoluteSessionLifetime()).thenReturn(Duration.ofHours(2));
        filter =
                new ActiveUserSessionFilter(
                        users,
                        properties,
                        clock,
                        new SecurityJson(new ErrorMessageResolver(messages), mapper));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    void authenticate(long age) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                new UserPrincipal(id.value().toString(), clock.millis() - age),
                                null,
                                List.of()));
    }

    @Test
    void activeUserBeforeConfiguredDeadlinePasses() throws Exception {
        authenticate(Duration.ofHours(2).toMillis() - 1);
        when(users.findById(id)).thenReturn(Optional.of(User.create(id)));
        filter.doFilter(request, response, chain);
        verify(users).findById(id);
        verify(chain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getSession(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "disabled", "expired", "deadline"})
    void invalidUserOrExpiredSessionIsCleared(String reason) throws Exception {
        var user = User.create(id);
        if (reason.equals("disabled")) user.disable();
        when(users.findById(id))
                .thenReturn(reason.equals("missing") ? Optional.empty() : Optional.of(user));
        authenticate(
                reason.equals("expired")
                        ? Duration.ofHours(3).toMillis()
                        : reason.equals("deadline") ? Duration.ofHours(2).toMillis() : 0);
        var session = request.getSession();
        filter.doFilter(request, response, chain);
        assertTrue(((MockHttpSession) session).isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, response.getStatus());
        assertEquals(
                "COMMON_UNAUTHORIZED",
                mapper.readTree(response.getContentAsString()).path("code").asText());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verifyNoInteractions(chain);
    }

    @Test
    void rejectionDoesNotCreateANewSession() throws Exception {
        authenticate(0);
        when(users.findById(id)).thenReturn(Optional.empty());
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertNull(request.getSession(false));
    }

    @Test
    void anonymousAndOtherPrincipalTypesKeepTheirOriginalHandling() throws Exception {
        filter.doFilter(request, response, chain);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "other-principal", null, List.of()));
        filter.doFilter(request, response, chain);
        verifyNoInteractions(users);
        verify(chain, times(2)).doFilter(request, response);
    }

    @Test
    void nestedDispatchOnlyChecksTheUserOnce() throws Exception {
        authenticate(0);
        when(users.findById(id)).thenReturn(Optional.of(User.create(id)));
        FilterChain nested = (req, res) -> filter.doFilter(req, res, chain);
        filter.doFilter(request, response, nested);
        verify(users).findById(id);
        verify(chain).doFilter(request, response);
    }
}
