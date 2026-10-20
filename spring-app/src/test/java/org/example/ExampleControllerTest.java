package org.example;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class ExampleControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void successReturnsJson() throws Exception {
        mockMvc.perform(get("/examples/success"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"message":"Hello World!"}
                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    void independentRequestsReceiveDistinctServerIds() throws Exception {
        String first =
                mockMvc.perform(
                                get("/examples/success")
                                        .header("X-Request-ID", "malicious-client-id"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-ID");
        String second =
                mockMvc.perform(get("/examples/success"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-ID");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(4, java.util.UUID.fromString(first).version());
        assertEquals(4, java.util.UUID.fromString(second).version());
        assertNotEquals("malicious-client-id", first);
        assertNotEquals(first, second);
        assertNull(org.slf4j.MDC.get("requestId"));
    }

    @Test
    void businessErrorUsesGlobalHandler() throws Exception {
        mockMvc.perform(get("/examples/business-error"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"COMMON_NOT_FOUND","message":"Resource not found"}
                        """,
                                        JsonCompareMode.STRICT));
    }

    @Test
    void unexpectedErrorUsesFallbackHandler() throws Exception {
        mockMvc.perform(get("/examples/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(
                        content()
                                .json(
                                        """
                        {"code":"COMMON_INTERNAL_SERVER_ERROR","message":"Internal server error"}
                        """,
                                        JsonCompareMode.STRICT));
    }
}
