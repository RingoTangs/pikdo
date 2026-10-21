package io.github.ringotangs.pikdo.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ringotangs.pikdo.server.controller.ExampleController;
import io.github.ringotangs.pikdo.server.shared.web.request.RequestIdFilter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@WithoutDatabase
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void healthHidesDetailsAndOtherManagementEndpointsRemainHidden() throws Exception {
        HealthEndpointAssertions.assertHealthAndExposure(mockMvc);
    }

    @Test
    void webRegressionContextHasNoDatabaseInfrastructure() {
        assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(org.flywaydb.core.Flyway.class).isEmpty());
        assertTrue(
                context.getBeansOfType(org.apache.ibatis.session.SqlSessionFactory.class)
                        .isEmpty());
    }

    @Test
    void contextLoads() {
        assertEquals(
                "pikdo-server", context.getEnvironment().getProperty("spring.application.name"));
        assertEquals("io.github.ringotangs.pikdo.server", Application.class.getPackageName());
    }

    @Test
    void swaggerIsDisabledByDefault() throws Exception {
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
        var mappings =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        assertTrue(
                mappings.getHandlerMethods().keySet().stream()
                        .flatMap(mapping -> mapping.getPatternValues().stream())
                        .noneMatch(
                                path -> path.startsWith("/examples") || path.startsWith("/test/")));
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
