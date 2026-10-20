package org.example.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;
import java.util.stream.Stream;

class ErrorMessageResolverTest {
    @Test
    void allProductionErrorsHaveExplicitTranslationsInBothBundles() throws Exception {
        for (String name : new String[] {"messages.properties", "messages_zh.properties"}) {
            java.util.Properties properties = new java.util.Properties();
            try (var stream = getClass().getClassLoader().getResourceAsStream(name)) {
                assertNotNull(stream);
                properties.load(
                        new java.io.InputStreamReader(
                                stream, java.nio.charset.StandardCharsets.UTF_8));
            }
            Stream.concat(Stream.of(CommonError.values()), Stream.of(RequestError.values()))
                    .forEach(
                            error ->
                                    assertFalse(
                                            properties
                                                    .getProperty(error.getMessageKey(), "")
                                                    .isBlank(),
                                            error.getMessageKey()));
        }
    }

    @Test
    void sameExceptionCanBeTranslatedAtDifferentResponseBoundaries() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("messages", "test-messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        ErrorMessageResolver resolver = new ErrorMessageResolver(source);
        ErrorCode error =
                new ErrorCode() {
                    public String getCode() {
                        return "USER_NOT_FOUND";
                    }

                    public org.springframework.http.HttpStatus getStatus() {
                        return org.springframework.http.HttpStatus.NOT_FOUND;
                    }

                    public String getMessageKey() {
                        return "error.test.user_not_found";
                    }
                };
        BusinessException exception = BusinessException.of(error, "alice");
        assertEquals(
                new ErrorResponse("USER_NOT_FOUND", "用户 alice 不存在"),
                resolver.resolve(
                        exception.getError(), Locale.SIMPLIFIED_CHINESE, exception.getArgs()));
        assertEquals(
                new ErrorResponse("USER_NOT_FOUND", "User alice not found"),
                resolver.resolve(exception.getError(), Locale.US, exception.getArgs()));
    }

    @Test
    void missingOrMalformedFallbackNeverLeaksKeysOrArguments() {
        StaticMessageSource source = new StaticMessageSource();
        source.setAlwaysUseMessageFormat(true);
        ErrorMessageResolver resolver = new ErrorMessageResolver(source);
        assertEquals(
                new ErrorResponse("COMMON_NOT_FOUND", "Internal server error"),
                resolver.resolve(CommonError.NOT_FOUND, Locale.US, "secret"));
        source.addMessage("error.common.not_found", Locale.US, "invalid {template");
        source.addMessage("error.common.internal_server_error", Locale.US, "invalid {fallback");
        assertEquals(
                "Internal server error",
                resolver.resolve(CommonError.NOT_FOUND, Locale.US, "secret").message());
    }
}
