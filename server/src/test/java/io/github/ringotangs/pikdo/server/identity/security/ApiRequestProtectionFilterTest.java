package io.github.ringotangs.pikdo.server.identity.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.ringotangs.pikdo.server.identity.application.*;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorMessageResolver;
import io.github.ringotangs.pikdo.server.shared.web.request.ClientIpResolver;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

class ApiRequestProtectionFilterTest {
    final AuthenticationRateLimiter rates = mock(AuthenticationRateLimiter.class);
    final FilterChain chain = mock(FilterChain.class);
    final ObjectMapper mapper = new ObjectMapper();
    final SecurityJson json = new SecurityJson(new ErrorMessageResolver(messages()), mapper);
    final ApiRequestProtectionFilter filter =
            new ApiRequestProtectionFilter(
                    new ClientIpResolver(List.of("10.0.0.0/8")), rates, json);
    final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/private");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    static ResourceBundleMessageSource messages() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    void assertError(int status, String code) throws Exception {
        assertEquals(status, response.getStatus());
        assertEquals("application/json", response.getContentType().split(";")[0]);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals(
                Set.of("code", "message"),
                mapper.readTree(response.getContentAsString()).propertyNames());
        assertEquals(code, mapper.readTree(response.getContentAsString()).path("code").asText());
    }

    @Test
    void absentAndMatchingOriginsPassWithNoStore() throws Exception {
        request.addHeader("Origin", "http://LOCALHOST");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verifyNoInteractions(rates);
        request.removeHeader("Origin");
        filter.doFilter(request, response, chain);
        verify(chain, times(2)).doFilter(request, response);
    }

    @Test
    void matchingHttpsAndExplicitPortPass() throws Exception {
        request.setScheme("https");
        request.setServerName("example.com");
        request.setServerPort(8443);
        request.addHeader("Origin", "https://example.com:8443");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        request.setServerPort(443);
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://example.com");
        filter.doFilter(request, response, chain);
        verify(chain, times(2)).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "not a URI",
                "https://evil.example",
                "http://localhost:81",
                "https://localhost",
                "http://user@localhost",
                "http://localhost/",
                "http://localhost?x=1",
                "http://localhost#fragment"
            })
    void invalidOrCrossOriginRequestsAreForbidden(String origin) throws Exception {
        request.addHeader("Origin", origin);
        filter.doFilter(request, response, chain);
        assertError(403, "COMMON_FORBIDDEN");
        verifyNoInteractions(chain, rates);
    }

    @Test
    void crossSiteMetadataRejectsEvenMatchingOrigin() throws Exception {
        request.addHeader("Origin", "http://localhost");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        filter.doFilter(request, response, chain);
        assertError(403, "COMMON_FORBIDDEN");
        verifyNoInteractions(chain, rates);
    }

    @Test
    void routesUseResolvedIpAndTheirOriginalBudget() throws Exception {
        request.setMethod("POST");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "192.0.2.8, 10.0.0.2");
        for (String route :
                List.of("registration-codes", "login-codes", "registrations", "login")) {
            request.setRequestURI("/api/v1/auth/" + route);
            filter.doFilter(request, response, chain);
        }
        verify(rates, times(2)).request("192.0.2.8", true);
        verify(rates, times(2)).request("192.0.2.8", false);
        verifyNoMoreInteractions(rates);
        verify(chain, times(4)).doFilter(request, response);
    }

    @Test
    void unrelatedPostDoesNotUseAuthenticationBudgets() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/logout");
        filter.doFilter(request, response, chain);
        verifyNoInteractions(rates);
        verify(chain).doFilter(request, response);
    }

    @Test
    void limiterFailureIsSanitizedAndStopsTheChain() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        doThrow(new AuthenticationFailure(AuthenticationFailure.Reason.RATE_LIMITED))
                .when(rates)
                .request(anyString(), eq(false));
        filter.doFilter(request, response, chain);
        assertError(429, "IDENTITY_RATE_LIMITED");
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @CsvSource({
        "INVALID, 401, IDENTITY_VERIFICATION_FAILED",
        "RATE_LIMITED, 429, IDENTITY_RATE_LIMITED",
        "UNAVAILABLE, 503, IDENTITY_UNAVAILABLE",
        "ALREADY_AUTHENTICATED, 409, IDENTITY_ALREADY_AUTHENTICATED"
    })
    void downstreamAuthenticationFailureKeepsOriginalMapping(
            AuthenticationFailure.Reason reason, int status, String code) throws Exception {
        doThrow(new AuthenticationFailure(reason)).when(chain).doFilter(request, response);
        filter.doFilter(request, response, chain);
        assertError(status, code);
    }

    @ParameterizedTest
    @EnumSource(AuthenticationFailure.Reason.class)
    void committedResponseIsNotRewrittenForAuthenticationFailures(
            AuthenticationFailure.Reason reason) throws Exception {
        assertCommittedResponsePreserved(new AuthenticationFailure(reason));
    }

    @Test
    void committedResponseIsNotRewrittenForDatabaseFailure() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated("user", null, List.of()));
        assertCommittedResponsePreserved(new DataAccessResourceFailureException("secret"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    void assertCommittedResponsePreserved(RuntimeException failure) throws Exception {
        doAnswer(
                        invocation -> {
                            response.setStatus(202);
                            response.getWriter().write("original-response");
                            response.flushBuffer();
                            throw failure;
                        })
                .when(chain)
                .doFilter(request, response);
        filter.doFilter(request, response, chain);
        assertTrue(response.isCommitted());
        assertEquals(202, response.getStatus());
        assertEquals("original-response", response.getContentAsString());
    }

    @Test
    void limiterUnavailableFailureIsNotReportedAsRateLimited() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        doThrow(new AuthenticationFailure(AuthenticationFailure.Reason.UNAVAILABLE))
                .when(rates)
                .request(anyString(), eq(false));
        filter.doFilter(request, response, chain);
        assertError(503, "IDENTITY_UNAVAILABLE");
        verifyNoInteractions(chain);
    }

    @Test
    void downstreamDatabaseFailureClearsContextAndReturns503() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated("user", null, List.of()));
        doThrow(new DataAccessResourceFailureException("secret-internal-detail"))
                .when(chain)
                .doFilter(request, response);
        filter.doFilter(request, response, chain);
        assertError(503, "IDENTITY_UNAVAILABLE");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertFalse(response.getContentAsString().contains("secret-internal-detail"));
    }

    @Test
    void limiterDatabaseFailureReturns503BeforeTheChain() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login-codes");
        doThrow(new DataAccessResourceFailureException("secret"))
                .when(rates)
                .request(anyString(), eq(true));
        filter.doFilter(request, response, chain);
        assertError(503, "IDENTITY_UNAVAILABLE");
        verifyNoInteractions(chain);
    }

    @Test
    void nestedDispatchOnlyRunsProtectionOnce() throws Exception {
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        FilterChain nested = (req, res) -> filter.doFilter(req, res, chain);
        filter.doFilter(request, response, nested);
        verify(rates).request("127.0.0.1", false);
        verify(chain).doFilter(request, response);
    }
}
