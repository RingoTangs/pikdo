package io.github.ringotangs.pikdo.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.ringotangs.pikdo.server.config.RequestIdFilter;

import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

/** The same public management boundary must hold in default, dev and prod contexts. */
final class HealthEndpointAssertions {
    private HealthEndpointAssertions() {}

    static void assertHealthAndExposure(MockMvc mockMvc) throws Exception {
        var response =
                mockMvc.perform(
                                get("/actuator/health")
                                        .accept(MediaType.APPLICATION_JSON)
                                        .header(RequestIdFilter.HEADER_NAME, "untrusted-client-id"))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(
                                content()
                                        .json(
                                                "{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                                                JsonCompareMode.STRICT))
                        .andReturn()
                        .getResponse();
        String id = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertNotNull(id);
        assertEquals(4, UUID.fromString(id).version());
        assertNotEquals("untrusted-client-id", id);
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        for (String endpoint :
                new String[] {
                    "env", "beans", "metrics", "configprops", "info", "loggers", "prometheus"
                }) {
            mockMvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
        }
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
