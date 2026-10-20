package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.example.controller.ExampleController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SwaggerDevTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void documentsExampleResponses() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$.paths['/examples/success'].get.responses['200']"
                                                + ".content['application/json'].schema['$ref']")
                                .value("#/components/schemas/SuccessResponse"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/examples/business-error'].get.responses['404']"
                                                + ".content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/examples/unexpected-error'].get.responses['500']"
                                                + ".content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse"));
    }

    @Test
    void documentsValidationExamples() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                        "$.paths['/examples/validation/body'].post.responses['400']"
                                                + ".content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse"))
                .andExpect(
                        jsonPath(
                                        "$.paths['/examples/validation/query'].get.responses['400']"
                                                + ".content['application/json'].schema['$ref']")
                                .value("#/components/schemas/ErrorResponse"));
    }

    @Test
    void swaggerUiIsAvailable() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }

    @Autowired private ApplicationContext context;

    @Test
    void examplesAreRegisteredInDevelopment() throws Exception {
        assertEquals(1, context.getBeansOfType(ExampleController.class).size());
        mockMvc.perform(get("/examples/success"))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .json(
                                        "{\"message\":\"Hello World!\"}",
                                        org.springframework.test.json.JsonCompareMode.STRICT));
    }
}
