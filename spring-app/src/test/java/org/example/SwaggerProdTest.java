package org.example;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.example.config.RequestIdFilter;
import org.example.controller.ExampleController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class SwaggerProdTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void swaggerIsDisabledInProduction() throws Exception {
        for (String path :
                new String[] {
                    "/v3/api-docs",
                    "/v3/api-docs.yaml",
                    "/v3/api-docs/swagger-config",
                    "/swagger-ui.html",
                    "/swagger-ui/index.html"
                }) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Autowired private ApplicationContext context;

    @Test
    void examplesAreNotRegisteredAndRequestIdsRemainAvailable() throws Exception {
        assertTrue(context.getBeansOfType(ExampleController.class).isEmpty());
        var registration =
                context.getBean("requestIdFilterRegistration", FilterRegistrationBean.class);
        assertInstanceOf(RequestIdFilter.class, registration.getFilter());
        for (String path :
                new String[] {
                    "/examples/success",
                    "/examples/business-error",
                    "/examples/unexpected-error",
                    "/examples/validation/query"
                }) {
            var response =
                    mockMvc.perform(get(path))
                            .andExpect(status().isNotFound())
                            .andReturn()
                            .getResponse();
            String id = response.getHeader("X-Request-ID");
            assertNotNull(id);
            assertEquals(4, java.util.UUID.fromString(id).version());
        }
        mockMvc.perform(
                        post("/examples/validation/body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isNotFound());
    }
}
