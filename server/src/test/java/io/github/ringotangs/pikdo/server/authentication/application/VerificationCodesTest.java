package io.github.ringotangs.pikdo.server.authentication.application;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Base64;

class VerificationCodesTest {
    private final VerificationCodes codes =
            new VerificationCodes(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void generatedCodesHaveSixAsciiDigitsAndFlowsHaveIndependentSecrets() {
        for (int i = 0; i < 1000; i++) assertTrue(codes.generate().matches("[0-9]{6}"));
        assertNotEquals(codes.newFlow(), codes.newFlow());
    }

    @Test
    void digestsAreKeyedAndBoundToEveryContextField() {
        var expected =
                codes.digest("code", "challenge", "REGISTER", "mail@example.com", "flow", "000001");
        assertEquals(32, expected.length);
        assertTrue(
                codes.matches(
                        expected,
                        codes.digest(
                                "code",
                                "challenge",
                                "REGISTER",
                                "mail@example.com",
                                "flow",
                                "000001")));
        for (var parts :
                new String[][] {
                    {"code", "other", "REGISTER", "mail@example.com", "flow", "000001"},
                    {"code", "challenge", "LOGIN", "mail@example.com", "flow", "000001"},
                    {"code", "challenge", "REGISTER", "other@example.com", "flow", "000001"},
                    {"code", "challenge", "REGISTER", "mail@example.com", "other", "000001"},
                    {"code", "challenge", "REGISTER", "mail@example.com", "flow", "000002"}
                }) assertFalse(codes.matches(expected, codes.digest(parts)));
        assertFalse(codes.matches(codes.digest("ab", "c"), codes.digest("a", "bc")));
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        assertFalse(
                codes.matches(
                        expected,
                        new VerificationCodes(Base64.getEncoder().encodeToString(otherKey))
                                .digest(
                                        "code",
                                        "challenge",
                                        "REGISTER",
                                        "mail@example.com",
                                        "flow",
                                        "000001")));
    }

    @Test
    void startupRejectsMissingWeakOrMalformedKey() {
        for (String value :
                new String[] {"", "short", "%%%", Base64.getEncoder().encodeToString(new byte[31])})
            assertThrows(IllegalArgumentException.class, () -> new VerificationCodes(value));
    }
}
