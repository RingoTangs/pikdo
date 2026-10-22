package io.github.ringotangs.pikdo.server.authentication.security;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.convert.ConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.io.IOException;
import java.io.ObjectStreamClass;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

class JdbcSessionConfigurationTest {
    final ConversionService conversion =
            new JdbcSessionConfiguration().springSessionConversionService();

    static byte[] legacyContext() throws IOException {
        try (var input =
                JdbcSessionConfigurationTest.class.getResourceAsStream(
                        "/authentication/legacy-security-context.ser")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    @Test
    void legacyContextRestoresTheNewPrincipalWithUnchangedIdentityAndAuthorities()
            throws Exception {
        var context = (SecurityContext) conversion.convert(legacyContext(), Object.class);
        assertNotNull(context);
        var authentication = context.getAuthentication();
        var principal = assertInstanceOf(UserPrincipal.class, authentication.getPrincipal());
        assertEquals("0198a7b2-1234-7000-8000-000000000001", principal.userId());
        assertEquals(
                Instant.parse("2026-10-22T00:00:00Z").toEpochMilli(), principal.authenticatedAt());
        assertTrue(authentication.isAuthenticated());
        assertNull(authentication.getCredentials());
        assertEquals(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), authentication.getAuthorities());
        assertEquals(0L, ObjectStreamClass.lookup(UserPrincipal.class).getSerialVersionUID());
    }

    @Test
    void newContextsUseTheNewClassNameAndRoundTrip() {
        var principal = new UserPrincipal("test-user", 1234L);
        var context =
                new SecurityContextImpl(
                        UsernamePasswordAuthenticationToken.authenticated(
                                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var bytes = conversion.convert(context, byte[].class);
        assertNotNull(bytes);
        String serialized = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(serialized.contains(UserPrincipal.class.getName()));
        assertFalse(serialized.contains("server.identity.security.UserPrincipal"));
        assertEquals(context, conversion.convert(bytes, Object.class));
    }

    @Test
    void flowStringsAndCsrfTokensKeepStandardSerialization() {
        assertEquals(
                "flow", conversion.convert(conversion.convert("flow", byte[].class), Object.class));
        var token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token");
        var restored =
                (DefaultCsrfToken)
                        conversion.convert(conversion.convert(token, byte[].class), Object.class);
        assertNotNull(restored);
        assertEquals(token.getHeaderName(), restored.getHeaderName());
        assertEquals(token.getParameterName(), restored.getParameterName());
        assertEquals(token.getToken(), restored.getToken());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void incompatibleLegacyDescriptorIsRejected(boolean version) throws Exception {
        var bytes = legacyContext();
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        int index =
                content.indexOf(
                        "io.github.ringotangs.pikdo.server.identity.security.UserPrincipal");
        assertTrue(index >= 0);
        if (version)
            bytes[
                            index
                                    + "io.github.ringotangs.pikdo.server.identity.security.UserPrincipal"
                                            .length()
                                    + 7] =
                    1;
        else bytes[content.indexOf("authenticatedAt", index)] = 'x';
        assertThrows(RuntimeException.class, () -> conversion.convert(bytes, Object.class));
    }

    @Test
    void corruptedDataIsNotSilentlyConvertedToAnAnonymousSession() {
        assertThrows(
                RuntimeException.class,
                () -> conversion.convert(new byte[] {1, 2, 3}, Object.class));
    }
}
