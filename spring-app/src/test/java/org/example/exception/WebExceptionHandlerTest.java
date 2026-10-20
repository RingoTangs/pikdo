package org.example.exception;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.example.config.RequestIdConfiguration;
import org.example.config.RequestIdFilter;
import org.example.config.WebExceptionFallbackConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        value = WebExceptionHandlerTest.TestController.class,
        properties = "spring.messages.basename=messages,test-messages")
@Import({
    RequestIdConfiguration.class,
    WebExceptionHandler.class,
    ErrorMessageResolver.class,
    WebExceptionFallbackConfiguration.class,
    WebExceptionHandlerTest.TestController.class
})
class WebExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @ParameterizedTest
    @EnumSource(
            value = HttpStatus.class,
            names = {"BAD_REQUEST", "NOT_FOUND", "CONFLICT"})
    void returnsBusinessStatusAndErrorBody(HttpStatus httpStatus) throws Exception {
        mockMvc.perform(get("/test/business-error").param("status", httpStatus.name()))
                .andExpect(status().is(httpStatus.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"BUSINESS_ERROR","message":"Business operation failed"}
                        """,
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void returnsJsonForPlainTextEndpoint() throws Exception {
        mockMvc.perform(get("/test/plain-text-error").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"USER_NOT_FOUND","message":"User alice not found"}
                        """,
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/dynamic-error")
        String dynamicError(@RequestParam String userId) {
            throw BusinessException.of(TestError.USER_NOT_FOUND, userId);
        }

        @GetMapping("/test/missing-key")
        String missingKey() {
            throw BusinessException.of(
                    new ErrorCode() {
                        public String getCode() {
                            return "MISSING_TRANSLATION";
                        }

                        public HttpStatus getStatus() {
                            return HttpStatus.NOT_FOUND;
                        }

                        public String getMessageKey() {
                            return "error.test.missing_translation";
                        }
                    });
        }

        @GetMapping("/test/common-error")
        String commonError() {
            throw BusinessException.of(CommonError.NOT_FOUND);
        }

        @GetMapping("/test/custom-error")
        String customError() {
            throw BusinessException.of(TestError.USER_NOT_FOUND, "alice");
        }

        @GetMapping("/test/business-error")
        String businessError(@RequestParam HttpStatus status) {
            throw BusinessException.of(
                    new ErrorCode() {
                        public String getCode() {
                            return "BUSINESS_ERROR";
                        }

                        public HttpStatus getStatus() {
                            return status;
                        }

                        public String getMessageKey() {
                            return "error.test.business_error";
                        }
                    });
        }

        @GetMapping(value = "/test/plain-text-error", produces = MediaType.TEXT_PLAIN_VALUE)
        String plainTextError() {
            throw BusinessException.of(TestError.USER_NOT_FOUND, "alice");
        }

        @GetMapping({"/test/unexpected-error", "/test/unexpected-error/{ignored}"})
        String unexpectedError() {
            throw new IllegalStateException("internal-secret-details");
        }

        @GetMapping(
                value = "/test/plain-text-unexpected-error",
                produces = MediaType.TEXT_PLAIN_VALUE)
        String plainTextUnexpectedError() {
            throw new IllegalStateException("internal-secret-details");
        }
    }

    @Test
    void unexpectedErrorReturnsSafeJson() throws Exception {
        mockMvc.perform(get("/test/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"COMMON_INTERNAL_SERVER_ERROR","message":"Internal server error"}
                        """,
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void unexpectedPlainTextErrorReturnsJson() throws Exception {
        mockMvc.perform(get("/test/plain-text-unexpected-error").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"COMMON_INTERNAL_SERVER_ERROR","message":"Internal server error"}
                        """,
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void frameworkErrorsKeepTheirHttpStatus() throws Exception {
        mockMvc.perform(get("/test/business-error")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/test/business-error").param("status", "invalid"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/test/missing")).andExpect(status().isNotFound());
        mockMvc.perform(post("/test/business-error")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void commonErrorFactoryReturnsExpectedResponse() throws Exception {
        mockMvc.perform(get("/test/common-error"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"COMMON_NOT_FOUND","message":"Resource not found"}
                        """,
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void dynamicMessageKeepsEnumStatusAndCode() throws Exception {
        mockMvc.perform(get("/test/dynamic-error").param("userId", "alice"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"USER_NOT_FOUND","message":"User alice not found"}
                        """,
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void customErrorFactoryReturnsExpectedResponse() throws Exception {
        mockMvc.perform(get("/test/custom-error"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"USER_NOT_FOUND","message":"User alice not found"}
                        """,
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "zh-CN,资源不存在,服务器内部错误,用户 alice 不存在",
        "en-US,Resource not found,Internal server error,User alice not found",
        "fr-FR,Resource not found,Internal server error,User alice not found"
    })
    void localizesBusinessAndFallbackResponses(
            String language, String notFound, String serverError, String dynamic) throws Exception {
        assertLocalized("/test/common-error", language, 404, "COMMON_NOT_FOUND", notFound);
        assertLocalized(
                "/test/dynamic-error?userId=alice", language, 404, "USER_NOT_FOUND", dynamic);
        assertLocalized(
                "/test/unexpected-error",
                language,
                500,
                "COMMON_INTERNAL_SERVER_ERROR",
                serverError);
        assertLocalized("/test/missing-key", language, 404, "MISSING_TRANSLATION", serverError);
        for (String path :
                new String[] {"/test/plain-text-error", "/test/plain-text-unexpected-error"}) {
            boolean unexpected = path.contains("unexpected");
            mockMvc.perform(
                            get(path)
                                    .accept(MediaType.TEXT_PLAIN)
                                    .header("Accept-Language", language))
                    .andExpect(status().is(unexpected ? 500 : 404))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(
                            content()
                                    .json(
                                            "{\"code\":\"%s\",\"message\":\"%s\"}"
                                                    .formatted(
                                                            unexpected
                                                                    ? "COMMON_INTERNAL_SERVER_ERROR"
                                                                    : "USER_NOT_FOUND",
                                                            unexpected ? serverError : dynamic),
                                            org.springframework.test.json.JsonCompareMode.STRICT));
        }
    }

    private void assertLocalized(
            String path, String language, int statusCode, String code, String message)
            throws Exception {
        mockMvc.perform(get(path).header("Accept-Language", language))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        "{\"code\":\"%s\",\"message\":\"%s\"}"
                                                .formatted(code, message),
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    void defaultLanguageDoesNotDependOnSystemLocale() throws Exception {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
            mockMvc.perform(get("/test/common-error"))
                    .andExpect(status().isNotFound())
                    .andExpect(
                            content()
                                    .json(
                                            "{\"code\":\"COMMON_NOT_FOUND\",\"message\":\"Resource not found\"}"));
            assertLocalized(
                    "/test/common-error", "de-DE", 404, "COMMON_NOT_FOUND", "Resource not found");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void unexpectedErrorLogsThrowableWithoutRawRequestContext() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(WebExceptionFallbackConfiguration.class);
        ListAppender<ILoggingEvent> appender =
                new ListAppender<>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        event.prepareForDeferredProcessing();
                        super.append(event);
                    }
                };
        appender.start();
        logger.addAppender(appender);
        try {
            var result =
                    mockMvc.perform(
                                    get("/test/unexpected-error/private-uri-marker")
                                            .queryParam("token", "private-query-marker")
                                            .header(
                                                    "Authorization",
                                                    "Bearer private-header-marker"))
                            .andExpect(status().isInternalServerError())
                            .andExpect(
                                    content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                            .andExpect(
                                    content()
                                            .json(
                                                    "{\"code\":\"COMMON_INTERNAL_SERVER_ERROR\",\"message\":\"Internal server error\"}",
                                                    org.springframework.test.json.JsonCompareMode
                                                            .STRICT))
                            .andReturn();
            String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER_NAME);
            assertNotNull(requestId);
            assertEquals(4, java.util.UUID.fromString(requestId).version());
            assertNull(MDC.get(RequestIdFilter.MDC_KEY));
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.ERROR, event.getLevel());
            assertEquals(requestId, event.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY));
            assertEquals("Unhandled exception during HTTP request", event.getFormattedMessage());
            assertTrue(event.getArgumentArray() == null || event.getArgumentArray().length == 0);
            var throwable = event.getThrowableProxy();
            assertNotNull(throwable);
            assertEquals(IllegalStateException.class.getName(), throwable.getClassName());
            assertEquals("internal-secret-details", throwable.getMessage());
            assertTrue(throwable.getStackTraceElementProxyArray().length > 0);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void expectedBusinessAndRequestErrorsDoNotLogErrorStacks() throws Exception {
        Logger handler = (Logger) LoggerFactory.getLogger(WebExceptionHandler.class);
        Logger fallback = (Logger) LoggerFactory.getLogger(WebExceptionFallbackConfiguration.class);
        ListAppender<ILoggingEvent> appender =
                new ListAppender<>() {
                    @Override
                    protected void append(ILoggingEvent event) {
                        event.prepareForDeferredProcessing();
                        super.append(event);
                    }
                };
        appender.start();
        handler.addAppender(appender);
        fallback.addAppender(appender);
        try {
            mockMvc.perform(get("/test/common-error")).andExpect(status().isNotFound());
            mockMvc.perform(get("/test/business-error")).andExpect(status().isBadRequest());
            mockMvc.perform(get("/test/business-error").param("status", "invalid"))
                    .andExpect(status().isBadRequest());
            assertTrue(
                    appender.list.stream()
                            .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)));
        } finally {
            handler.detachAppender(appender);
            fallback.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "/test/common-error,404",
        "/test/business-error,400",
        "/test/missing,404"
    })
    void errorResponsesIncludeRequestId(String path, int expectedStatus) throws Exception {
        var response =
                mockMvc.perform(get(path))
                        .andExpect(status().is(expectedStatus))
                        .andReturn()
                        .getResponse();
        String id = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertNotNull(id);
        assertEquals(4, java.util.UUID.fromString(id).version());
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void methodNotAllowedIncludesRequestId() throws Exception {
        var response =
                mockMvc.perform(post("/test/business-error"))
                        .andExpect(status().isMethodNotAllowed())
                        .andReturn()
                        .getResponse();
        assertNotNull(response.getHeader(RequestIdFilter.HEADER_NAME));
    }

    private enum TestError implements ErrorCode {
        USER_NOT_FOUND;

        @Override
        public String getCode() {
            return "USER_NOT_FOUND";
        }

        @Override
        public HttpStatus getStatus() {
            return HttpStatus.NOT_FOUND;
        }

        @Override
        public String getMessageKey() {
            return "error.test.user_not_found";
        }
    }
}
