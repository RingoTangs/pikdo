package io.github.ringotangs.pikdo.server.shared.web.request;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;

class ClientIpResolverTest {
    MockHttpServletRequest request(String remote, String... headers) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        for (String header : headers) request.addHeader("X-Forwarded-For", header);
        return request;
    }

    @Test
    void missingRemoteAddressFallsBackWithoutTrustingForwardedHeaders() {
        var request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(null);
        assertEquals("unknown", new ClientIpResolver(List.of("10.0.0.0/8")).resolve(request));
        verify(request, never()).getHeaders(anyString());
    }

    @Test
    void emptyTrustAndUntrustedPeerIgnoreSpoofedHeaders() {
        assertEquals(
                "192.0.2.8",
                new ClientIpResolver(List.of()).resolve(request("192.0.2.8", "203.0.113.9")));
        assertEquals(
                "192.0.2.8",
                new ClientIpResolver(List.of("10.0.0.0/8"))
                        .resolve(request("192.0.2.8", "203.0.113.9")));
    }

    @Test
    void followsOnlyContiguousTrustedSuffix() {
        var resolver = new ClientIpResolver(List.of("10.0.0.0/8", "2001:db8:1::/48"));
        assertEquals(
                "203.0.113.9",
                resolver.resolve(request("10.1.1.1", "203.0.113.9, 10.2.2.2", "2001:db8:1::2")));
        assertEquals(
                "192.0.2.8",
                resolver.resolve(request("10.1.1.1", "203.0.113.9, 192.0.2.8, 10.2.2.2")));
        assertEquals("10.2.2.2", resolver.resolve(request("10.1.1.1", "10.2.2.2")));
    }

    @Test
    void supportsIpv6AndNormalizesMappedIpv4() {
        var resolver = new ClientIpResolver(List.of("::1", "10.0.0.0/8"));
        assertEquals("2001:db8:0:0:0:0:0:9", resolver.resolve(request("::1", "2001:0db8::9")));
        assertEquals("192.0.2.8", resolver.resolve(request("::ffff:10.0.0.1", "::ffff:192.0.2.8")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "unknown",
                "example.com",
                "192.0.2.1:1234",
                "[2001:db8::1]:443",
                "fe80::1%lo0",
                "192.0.2.1,",
                "192.0.2.1,,10.0.0.2",
                "999.0.0.1",
                "010.0.0.1",
                "1::2::3",
                "192.0.2.1, garbage"
            })
    void invalidHeaderFallsBackToDirectPeer(String header) {
        assertEquals(
                "10.0.0.1",
                new ClientIpResolver(List.of("10.0.0.0/8")).resolve(request("10.0.0.1", header)));
    }

    @Test
    void missingOversizedAndExcessiveHopsFallBack() {
        var resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
        assertEquals("10.0.0.1", resolver.resolve(request("10.0.0.1")));
        assertEquals("10.0.0.1", resolver.resolve(request("10.0.0.1", " ".repeat(4097))));
        assertEquals(
                "10.0.0.1",
                resolver.resolve(
                        request(
                                "10.0.0.1",
                                String.join(",", Collections.nCopies(33, "192.0.2.1")))));
    }

    @Test
    void ignoresOtherForwardingHeaders() {
        var request = request("10.0.0.1");
        request.addHeader("Forwarded", "for=203.0.113.9");
        request.addHeader("X-Real-IP", "203.0.113.9");
        assertEquals("10.0.0.1", new ClientIpResolver(List.of("10.0.0.1")).resolve(request));
    }

    @Test
    void cidrBoundariesAndExactAddresses() {
        var resolver = new ClientIpResolver(List.of("10.0.0.0/24", "2001:db8::/127"));
        assertEquals("192.0.2.1", resolver.resolve(request("10.0.0.255", "192.0.2.1")));
        assertEquals("10.0.1.0", resolver.resolve(request("10.0.1.0", "192.0.2.1")));
        assertEquals("192.0.2.1", resolver.resolve(request("2001:db8::1", "192.0.2.1")));
        assertEquals("2001:db8:0:0:0:0:0:2", resolver.resolve(request("2001:db8::2", "192.0.2.1")));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "10.0.0.1/33",
                "::1/129",
                "10.0.0.1/-1",
                "10.0.0.1/",
                "10.0.0.1/24/8",
                "fe80::1%eth0",
                "10.0.0.01"
            })
    void rejectsInvalidConfiguration(String proxy) {
        assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(List.of(proxy)));
    }

    @Test
    void mappedIpv4CidrMatchesCanonicalIpv4() {
        assertEquals(
                "203.0.113.8",
                new ClientIpResolver(List.of("::ffff:192.0.2.0/120"))
                        .resolve(request("192.0.2.8", "203.0.113.8")));
    }
}
