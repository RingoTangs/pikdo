package org.example.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.example.exception.CommonError;
import org.example.exception.ErrorMessageResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.support.RequestContextUtils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class WebExceptionFallbackConfiguration implements WebMvcConfigurer {

    private static final Logger log =
            LoggerFactory.getLogger(WebExceptionFallbackConfiguration.class);

    private final ObjectMapper objectMapper;

    private final ErrorMessageResolver messages;

    public WebExceptionFallbackConfiguration(
            ObjectMapper objectMapper, ErrorMessageResolver messages) {
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(
                (request, response, ignoredHandler, exception) ->
                        resolveUnexpectedException(request, response, exception));
    }

    private @Nullable ModelAndView resolveUnexpectedException(
            HttpServletRequest request, HttpServletResponse response, Exception exception) {
        log.error("Unhandled exception during HTTP request", exception);
        if (response.isCommitted()) {
            return null;
        }
        try {
            CommonError error = CommonError.INTERNAL_SERVER_ERROR;
            byte[] body =
                    objectMapper.writeValueAsBytes(
                            messages.resolve(error, RequestContextUtils.getLocale(request)));
            response.resetBuffer();
            response.setStatus(error.getStatus().value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return new ModelAndView();
        } catch (JacksonException | IOException | IllegalStateException writeException) {
            log.error("Failed to write fallback error response", writeException);
            return null;
        }
    }
}
