package io.github.ringotangs.pikdo.server.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.ringotangs.pikdo.server.identity.application.*;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.web.dto.VerificationRequest;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;

import java.time.*;
import java.util.*;

class AuthenticationSessionServiceTest {
    static final String OLD_FLOW =
            "io.github.ringotangs.pikdo.server.identity.web.EmailAuthenticationController.flow";
    final VerificationCodes codes = mock(VerificationCodes.class);
    final SessionAuthenticationStrategy strategy = mock(SessionAuthenticationStrategy.class);
    final SecurityContextRepository contexts = mock(SecurityContextRepository.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
    final AuthenticationSessionService service =
            new AuthenticationSessionService(codes, strategy, contexts, clock);
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reusesLegacyFlowKeyAndDoesNotRegenerateSecret() {
        request.getSession().setAttribute(OLD_FLOW, "existing-flow");
        assertEquals("existing-flow", service.getOrCreateFlow(request));
        assertEquals("existing-flow", service.requireFlow(request));
        verifyNoInteractions(codes);
    }

    @Test
    void createsFlowOnlyOnSendingAndRejectsMissingVerificationSession() {
        assertEquals(
                AuthenticationFailure.Reason.INVALID,
                assertThrows(AuthenticationFailure.class, () -> service.requireFlow(request))
                        .reason());
        assertNull(request.getSession(false));
        when(codes.newFlow()).thenReturn("new-flow");
        assertEquals("new-flow", service.getOrCreateFlow(request));
        assertEquals("new-flow", request.getSession().getAttribute(OLD_FLOW));
    }

    @Test
    void rotatesSessionInvalidatesCsrfAndPersistsCorrectPrincipal() {
        var csrf = new HttpSessionCsrfTokenRepository();
        csrf.saveToken(csrf.generateToken(request), request, response);
        request.getSession().setAttribute(OLD_FLOW, "flow");
        String oldId = request.getSession().getId();
        var realStrategy =
                new CompositeSessionAuthenticationStrategy(
                        List.of(
                                new ChangeSessionIdAuthenticationStrategy(),
                                new CsrfAuthenticationStrategy(csrf)));
        var sessions = new AuthenticationSessionService(codes, realStrategy, contexts, clock);
        var user = new UserId(UUID.randomUUID());
        sessions.establish(user, request, response);
        assertNotEquals(oldId, request.getSession().getId());
        assertNull(csrf.loadToken(request));
        assertNull(request.getSession().getAttribute(OLD_FLOW));
        var principal = sessions.currentPrincipal();
        assertEquals(user.value().toString(), principal.userId());
        assertEquals(clock.millis(), principal.authenticatedAt());
        verify(contexts)
                .saveContext(
                        same(SecurityContextHolder.getContext()), same(request), same(response));
        assertEquals(
                List.of("ROLE_USER"),
                SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .map(Object::toString)
                        .toList());
    }

    @Test
    void persistenceFailureClearsAuthenticationAndInvalidatesSession() {
        request.getSession().setAttribute(OLD_FLOW, "flow");
        doThrow(new IllegalStateException("internal-store-detail"))
                .when(contexts)
                .saveContext(any(), any(), any());
        var failure =
                assertThrows(
                        AuthenticationFailure.class,
                        () -> service.establish(new UserId(UUID.randomUUID()), request, response));
        assertEquals(AuthenticationFailure.Reason.UNAVAILABLE, failure.reason());
        assertFalse(failure.toString().contains("internal-store-detail"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(request.getSession(false));
    }

    @Test
    void invalidationFailureStillReturnsSanitizedFailure() {
        var req = mock(jakarta.servlet.http.HttpServletRequest.class);
        var session = mock(jakarta.servlet.http.HttpSession.class);
        when(req.getSession()).thenReturn(session);
        when(req.getSession(false)).thenReturn(session);
        doThrow(new IllegalStateException("cleanup-detail")).when(session).invalidate();
        doThrow(new IllegalStateException("store-detail"))
                .when(contexts)
                .saveContext(any(), any(), any());
        assertEquals(
                AuthenticationFailure.Reason.UNAVAILABLE,
                assertThrows(
                                AuthenticationFailure.class,
                                () ->
                                        service.establish(
                                                new UserId(UUID.randomUUID()), req, response))
                        .reason());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void anonymousCheckAndSensitiveDtoRemainProtected() {
        var principal = new UserPrincipal(UUID.randomUUID().toString(), clock.millis());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal, null, List.of()));
        assertEquals(
                AuthenticationFailure.Reason.ALREADY_AUTHENTICATED,
                assertThrows(AuthenticationFailure.class, service::requireAnonymous).reason());
        assertEquals(principal, service.currentPrincipal());
        assertFalse(
                new VerificationRequest(UUID.randomUUID(), "a@example.com", "123456")
                        .toString()
                        .contains("123456"));
    }
}
