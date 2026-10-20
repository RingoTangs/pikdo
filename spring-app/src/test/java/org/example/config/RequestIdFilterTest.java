package org.example.config;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void ignoresClientHeadersAndPreservesMdcAcrossIndependentRequests() throws Exception {
        String previousId = null;
        for (String supplied :
                new String[] {"malicious-client-id", "bad\r\nheader", "x".repeat(20000)}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            request.addHeader(RequestIdFilter.HEADER_NAME, supplied);
            MDC.put("other", "retained");
            filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                        String id = response.getHeader(RequestIdFilter.HEADER_NAME);
                        assertNotNull(id);
                        UUID uuid = UUID.fromString(id);
                        assertEquals(4, uuid.version());
                        assertEquals(2, uuid.variant());
                        assertEquals(uuid.toString(), id);
                        assertEquals(id, MDC.get(RequestIdFilter.MDC_KEY));
                        assertEquals(id, req.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE));
                        assertNotEquals(supplied, id);
                        assertEquals("retained", MDC.get("other"));
                    });
            String id = response.getHeader(RequestIdFilter.HEADER_NAME);
            assertNotEquals(previousId, id);
            previousId = id;
            assertNull(MDC.get(RequestIdFilter.MDC_KEY));
            assertEquals("retained", MDC.get("other"));
        }
    }

    @Test
    void restoresPreviousIdOnSuccessAndFailure() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "outer-context");
        filter.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                (req, res) -> {
                    assertNotEquals("outer-context", MDC.get(RequestIdFilter.MDC_KEY));
                });
        assertEquals("outer-context", MDC.get(RequestIdFilter.MDC_KEY));
        assertThrows(
                ServletException.class,
                () ->
                        filter.doFilter(
                                new MockHttpServletRequest(),
                                new MockHttpServletResponse(),
                                (req, res) -> {
                                    assertNotEquals(
                                            "outer-context", MDC.get(RequestIdFilter.MDC_KEY));
                                    throw new ServletException("controlled failure");
                                }));
        assertEquals("outer-context", MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void removesIdWhenChainFailsWithoutPreviousContext() {
        assertThrows(
                ServletException.class,
                () ->
                        filter.doFilter(
                                new MockHttpServletRequest(),
                                new MockHttpServletResponse(),
                                (req, res) -> {
                                    assertNotNull(MDC.get(RequestIdFilter.MDC_KEY));
                                    throw new ServletException("controlled failure");
                                }));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @ParameterizedTest
    @EnumSource(
            value = DispatcherType.class,
            names = {"ERROR", "ASYNC"})
    void redispatchReusesIdAndRestoresDispatchThreadContext(DispatcherType type) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse initial = new MockHttpServletResponse();
        filter.doFilter(request, initial, (req, res) -> {});
        String id = initial.getHeader(RequestIdFilter.HEADER_NAME);
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        request.setDispatcherType(type);
        if (type == DispatcherType.ERROR) {
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/controlled-error");
        }
        MockHttpServletResponse redispatched = new MockHttpServletResponse();
        MDC.put(RequestIdFilter.MDC_KEY, "dispatch-thread-context");
        filter.doFilter(
                request,
                redispatched,
                (req, res) -> {
                    assertEquals(id, MDC.get(RequestIdFilter.MDC_KEY));
                    assertEquals(id, redispatched.getHeader(RequestIdFilter.HEADER_NAME));
                });
        assertEquals("dispatch-thread-context", MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void nestedErrorReusesIdEvenWhenResponseHeadersWereReset() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    String id = MDC.get(RequestIdFilter.MDC_KEY);
                    response.reset();
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/controlled-error");
                    filter.doFilter(
                            request,
                            response,
                            (errorRequest, errorResponse) -> {
                                assertEquals(id, MDC.get(RequestIdFilter.MDC_KEY));
                                assertEquals(id, response.getHeader(RequestIdFilter.HEADER_NAME));
                            });
                    assertEquals(id, MDC.get(RequestIdFilter.MDC_KEY));
                });
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void committedResponseIsNotRewritten() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        String id = response.getHeader(RequestIdFilter.HEADER_NAME);
        response.flushBuffer();
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/controlled-error");
        filter.doFilter(
                request,
                response,
                (req, res) -> {
                    assertTrue(response.isCommitted());
                    assertEquals(id, MDC.get(RequestIdFilter.MDC_KEY));
                    assertEquals(id, response.getHeader(RequestIdFilter.HEADER_NAME));
                });
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
