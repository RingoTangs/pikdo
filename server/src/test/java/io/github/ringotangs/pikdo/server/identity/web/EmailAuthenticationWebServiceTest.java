package io.github.ringotangs.pikdo.server.identity.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.ringotangs.pikdo.server.identity.application.*;
import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.identity.security.*;
import io.github.ringotangs.pikdo.server.identity.web.dto.*;
import io.github.ringotangs.pikdo.server.shared.web.request.ClientIpResolver;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.time.Instant;
import java.util.UUID;

class EmailAuthenticationWebServiceTest {
    final EmailAuthenticationService core = mock(EmailAuthenticationService.class);
    final AuthenticationSessionService sessions = mock(AuthenticationSessionService.class);
    final AuthenticationRateLimiter rates = mock(AuthenticationRateLimiter.class);
    final ClientIpResolver ips = mock(ClientIpResolver.class);
    final EmailAuthenticationWebService service =
            new EmailAuthenticationWebService(core, sessions, rates, ips);
    final MockHttpServletRequest request = new MockHttpServletRequest();
    final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void sendChecksAnonymousAndFlowBeforeCallingCoreAndMapsResponse() {
        when(sessions.getOrCreateFlow(request)).thenReturn("flow");
        when(ips.resolve(request)).thenReturn("192.0.2.1");
        var result =
                new EmailAuthenticationService.IssuedCode(UUID.randomUUID(), Instant.now(), 60);
        when(core.send(any(), any(), any(), any())).thenReturn(result);
        assertEquals(
                new IssuedCodeResponse(result.challengeId(), result.expiresAt(), 60),
                service.registrationCode(new EmailRequest("Original@Example.com"), request));
        var order = inOrder(sessions, ips, core);
        order.verify(sessions).requireAnonymous();
        order.verify(sessions).getOrCreateFlow(request);
        order.verify(ips).resolve(request);
        order.verify(core)
                .send(
                        CodePurpose.REGISTER,
                        EmailAddress.create("Original@Example.com"),
                        "flow",
                        "192.0.2.1");
        service.loginCode(new EmailRequest("Original@Example.com"), request);
        verify(core).send(eq(CodePurpose.LOGIN), any(), eq("flow"), eq("192.0.2.1"));
    }

    @Test
    void sessionIsEstablishedOnlyAfterSuccessfulCoreCompletion() {
        var user = new UserId(UUID.randomUUID());
        var dto = new VerificationRequest(UUID.randomUUID(), "a@example.com", "123456");
        when(sessions.requireFlow(request)).thenReturn("flow");
        when(ips.resolve(request)).thenReturn("192.0.2.1");
        when(core.complete(any(), any(), any(), any(), any(), any())).thenReturn(user);
        service.register(dto, request, response);
        var order = inOrder(sessions, core);
        order.verify(sessions).requireAnonymous();
        order.verify(sessions).requireFlow(request);
        order.verify(core)
                .complete(
                        CodePurpose.REGISTER,
                        dto.challengeId(),
                        EmailAddress.create(dto.email()),
                        dto.code(),
                        "flow",
                        "192.0.2.1");
        order.verify(sessions).establish(user, request, response);
        service.login(dto, request, response);
        verify(core).complete(eq(CodePurpose.LOGIN), any(), any(), any(), any(), any());
    }

    @Test
    void failedCoreTransactionNeverEstablishesSession() {
        when(sessions.requireFlow(request)).thenReturn("flow");
        when(ips.resolve(request)).thenReturn("192.0.2.1");
        when(core.complete(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AuthenticationFailure(AuthenticationFailure.Reason.INVALID));
        assertThrows(
                AuthenticationFailure.class,
                () ->
                        service.login(
                                new VerificationRequest(
                                        UUID.randomUUID(), "a@example.com", "123456"),
                                request,
                                response));
        verify(sessions, never()).establish(any(), any(), any());
    }

    @Test
    void anonymousFailureStopsFlowBeforeCore() {
        doThrow(new AuthenticationFailure(AuthenticationFailure.Reason.ALREADY_AUTHENTICATED))
                .when(sessions)
                .requireAnonymous();
        assertThrows(
                AuthenticationFailure.class,
                () -> service.registrationCode(new EmailRequest("a@example.com"), request));
        verifyNoInteractions(core, ips);
        verify(sessions, never()).getOrCreateFlow(any());
    }

    @Test
    void csrfUsesResolvedIpAndMeMapsTheExistingPrincipal() {
        when(ips.resolve(request)).thenReturn("192.0.2.1");
        when(sessions.csrfToken(request))
                .thenReturn(new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token"));
        assertEquals(new CsrfResponse("X-CSRF-TOKEN", "token"), service.csrf(request));
        var order = inOrder(rates, sessions);
        order.verify(rates).csrf("192.0.2.1");
        order.verify(sessions).csrfToken(request);
        when(sessions.currentPrincipal()).thenReturn(new UserPrincipal("user-id", 0));
        assertEquals(new CurrentUserResponse("user-id", "ACTIVE"), service.me());
    }
}
