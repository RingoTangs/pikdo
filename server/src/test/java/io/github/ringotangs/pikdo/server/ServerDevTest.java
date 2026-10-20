package io.github.ringotangs.pikdo.server;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.ringotangs.pikdo.server.controller.ExampleController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Exercises the development application without importing any controller fixtures. */
@WithoutDatabase
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ServerDevTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;

    @Test
    void healthHidesDetailsAndOtherManagementEndpointsRemainHidden() throws Exception {
        HealthEndpointAssertions.assertHealthAndExposure(mockMvc);
    }

    @Test
    void testControllersAreNotScanned() throws Exception {
        assertTrue(context.getBeansOfType(ExampleController.class).isEmpty());
        var mappings =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        assertTrue(
                mappings.getHandlerMethods().keySet().stream()
                        .flatMap(mapping -> mapping.getPatternValues().stream())
                        .noneMatch(
                                path -> path.startsWith("/examples") || path.startsWith("/test/")));
        for (String path :
                new String[] {
                    "/examples/success",
                    "/examples/business-error",
                    "/examples/unexpected-error",
                    "/examples/validation/query",
                    "/test/validation/header"
                }) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void openApiIsAvailableWithoutExampleOperations() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isString())
                .andExpect(jsonPath("$.paths['/examples/success']").doesNotExist())
                .andExpect(jsonPath("$.paths['/examples/business-error']").doesNotExist())
                .andExpect(jsonPath("$.paths['/examples/validation/body']").doesNotExist());
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
