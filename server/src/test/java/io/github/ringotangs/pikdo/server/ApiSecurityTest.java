package io.github.ringotangs.pikdo.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 安全过滤链本身保留运行，不依赖数据库或测试用户绕过认证。 */
@WithoutDatabase
@SpringBootTest
@AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void anonymousApiIsJsonUnauthorized() throws Exception {
        mvc.perform(get("/api/private"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_UNAUTHORIZED"))
                .andExpect(header().exists("X-Request-ID"));
    }

    @Test
    void publicAuthenticationPostsStillRequireCsrf() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    }
}
