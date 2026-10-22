package io.github.ringotangs.pikdo.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.ringotangs.pikdo.server.controller.ExampleController;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@WithoutDatabase
@SpringBootTest
@Import(ExampleController.class)
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
    void explicitlyImportedTestFixtureIsRegistered() throws Exception {
        assertEquals(1, context.getBeansOfType(ExampleController.class).size());
        mockMvc.perform(get("/examples/success"))
                .andExpect(status().isOk())
                .andExpect(
                        content().json("{\"message\":\"Hello World!\"}", JsonCompareMode.STRICT));
    }

    @Autowired ObjectMapper json;

    @Test
    void documentsRealAuthenticationOperationsAndErrorSchemas() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var api = json.readTree(result.getResponse().getContentAsString());
        var expected =
                Map.of(
                        "csrf", List.of("200", "403", "429", "503"),
                        "registration-codes", List.of("202", "400", "403", "409", "429", "503"),
                        "login-codes", List.of("202", "400", "403", "409", "429", "503"),
                        "registrations", List.of("204", "400", "401", "403", "409", "429", "503"),
                        "login", List.of("204", "400", "401", "403", "409", "429", "503"),
                        "me", List.of("200", "401", "403", "503"));
        for (var entry : expected.entrySet()) {
            String route = entry.getKey();
            boolean get = route.equals("csrf") || route.equals("me");
            var operation =
                    api.path("paths").path("/api/v1/auth/" + route).path(get ? "get" : "post");
            Assertions.assertFalse(operation.isMissingNode());
            Assertions.assertEquals("邮箱认证", operation.path("tags").get(0).asText());
            Assertions.assertTrue(operation.path("summary").asText().matches(".*[\\p{IsHan}].*"));
            Assertions.assertFalse(operation.path("description").asText().isBlank());
            for (String status : entry.getValue()) {
                var response = operation.path("responses").path(status);
                Assertions.assertFalse(response.isMissingNode(), route + ":" + status);
                if (status.equals("200") || status.equals("202")) {
                    String schema =
                            route.equals("csrf")
                                    ? "CsrfResponse"
                                    : route.equals("me")
                                            ? "CurrentUserResponse"
                                            : "IssuedCodeResponse";
                    Assertions.assertEquals(
                            "#/components/schemas/" + schema,
                            response.path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("$ref")
                                    .asText());
                }
                if (status.startsWith("4") || status.equals("503")) {
                    String reference = response.path("$ref").asText();
                    Assertions.assertTrue(reference.startsWith("#/components/responses/Identity"));
                    var definition =
                            api.path("components")
                                    .path("responses")
                                    .path(reference.substring(reference.lastIndexOf('/') + 1));
                    Assertions.assertFalse(definition.path("description").asText().isBlank());
                    Assertions.assertEquals(
                            "#/components/schemas/ErrorResponse",
                            definition
                                    .path("content")
                                    .path("application/json")
                                    .path("schema")
                                    .path("$ref")
                                    .asText());
                }
                if (status.equals("204"))
                    Assertions.assertTrue(
                            response.path("content").isMissingNode()
                                    || response.path("content").isEmpty());
            }
            if (!get) {
                var headers = new ArrayList<JsonNode>();
                operation.path("parameters").forEach(headers::add);
                var csrf =
                        headers.stream()
                                .filter(node -> node.path("name").asText().equals("X-CSRF-TOKEN"))
                                .findFirst()
                                .orElseThrow();
                Assertions.assertEquals("header", csrf.path("in").asText());
                Assertions.assertTrue(csrf.path("required").asBoolean());
                Assertions.assertEquals("string", csrf.path("schema").path("type").asText());
            }
        }
        Assertions.assertEquals(
                Set.of("code", "message"),
                api.path("components")
                        .path("schemas")
                        .path("ErrorResponse")
                        .path("properties")
                        .propertyNames());
        Assertions.assertEquals(
                "#/components/schemas/VerificationRequest",
                api.path("paths")
                        .path("/api/v1/auth/login")
                        .path("post")
                        .path("requestBody")
                        .path("content")
                        .path("application/json")
                        .path("schema")
                        .path("$ref")
                        .asText());
        Assertions.assertEquals(
                "[0-9]{6}",
                api.path("components")
                        .path("schemas")
                        .path("VerificationRequest")
                        .path("properties")
                        .path("code")
                        .path("pattern")
                        .asText());
        Assertions.assertTrue(
                api.path("components")
                        .path("schemas")
                        .path("VerificationRequest")
                        .path("properties")
                        .path("code")
                        .path("example")
                        .isMissingNode());
        var cookie = api.path("components").path("securitySchemes").path("SessionCookie");
        Assertions.assertEquals("cookie", cookie.path("in").asText());
        Assertions.assertEquals("PIKDOSESSION", cookie.path("name").asText());
        Assertions.assertFalse(
                api.path("paths")
                        .path("/api/v1/auth/me")
                        .path("get")
                        .path("security")
                        .get(0)
                        .path("SessionCookie")
                        .isMissingNode());
    }

    @Test
    void logoutDocumentationDoesNotCreateAnMvcRouteAndMatchesRealFilter() throws Exception {
        var api =
                json.readTree(
                        mockMvc.perform(get("/v3/api-docs"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        var operation = api.path("paths").path("/api/v1/auth/logout").path("post");
        Assertions.assertEquals("退出登录", operation.path("summary").asText());
        Assertions.assertTrue(operation.path("requestBody").isMissingNode());
        for (String status : List.of("204", "403", "503"))
            Assertions.assertFalse(operation.path("responses").path(status).isMissingNode());
        Assertions.assertTrue(
                operation.path("responses").path("204").path("content").isMissingNode());
        var mappings =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Assertions.assertTrue(
                mappings.getHandlerMethods().keySet().stream()
                        .flatMap(mapping -> mapping.getPatternValues().stream())
                        .noneMatch(path -> path.equals("/api/v1/auth/logout")));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/auth/logout")
                                .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());
    }
}
