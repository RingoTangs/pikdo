package io.github.ringotangs.pikdo.server.identity.security;

import io.github.ringotangs.pikdo.server.shared.web.error.ErrorCode;
import io.github.ringotangs.pikdo.server.shared.web.error.ErrorMessageResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Security Filter 的失败也沿用统一 JSON 和 Locale，不重定向或输出异常详情。 */
public final class SecurityJson {
    private final ErrorMessageResolver messages;
    private final ObjectMapper json;

    public SecurityJson(ErrorMessageResolver messages, ObjectMapper json) {
        this.messages = messages;
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode error)
            throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(error.getStatus().value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream()
                .write(json.writeValueAsBytes(messages.resolve(error, request.getLocale())));
    }
}
