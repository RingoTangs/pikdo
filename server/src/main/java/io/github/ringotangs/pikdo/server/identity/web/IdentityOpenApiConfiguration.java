package io.github.ringotangs.pikdo.server.identity.web;

import io.github.ringotangs.pikdo.server.shared.web.error.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class IdentityOpenApiConfiguration {
    @Bean
    OpenApiCustomizer identityAuthenticationDocumentation(Environment environment) {
        return api -> {
            if (api.getComponents() == null) api.setComponents(new Components());
            var components = api.getComponents();
            ModelConverters.getInstance().read(ErrorResponse.class).forEach(components::addSchemas);
            Map.of(
                            "BadRequest",
                            "请求参数不合法",
                            "Unauthorized",
                            "未登录、用户不可用或验证码验证失败",
                            "Forbidden",
                            "CSRF 校验或同源校验失败",
                            "Conflict",
                            "当前已登录，请先退出",
                            "TooManyRequests",
                            "请求过于频繁，请稍后重试",
                            "Unavailable",
                            "认证服务暂不可用")
                    .forEach(
                            (name, description) ->
                                    components.addResponses(
                                            "Identity" + name,
                                            new ApiResponse()
                                                    .description(description)
                                                    .content(
                                                            new Content()
                                                                    .addMediaType(
                                                                            "application/json",
                                                                            new MediaType()
                                                                                    .schema(
                                                                                            new Schema<>()
                                                                                                    .$ref(
                                                                                                            "#/components/schemas/ErrorResponse"))))));
            components.addSecuritySchemes(
                    "SessionCookie",
                    new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.COOKIE)
                            .name(
                                    environment.getProperty(
                                            "server.servlet.session.cookie.name",
                                            "__Host-PIKDOSESSION"))
                            .description("浏览器自动携带的 HttpOnly Session Cookie，无需手动填写或复制。"));
            if (api.getPaths() == null) api.setPaths(new Paths());
            // 此操作由真实的 Security LogoutFilter 提供，不注册 MVC 路由。
            var logout =
                    new Operation()
                            .operationId("logout")
                            .summary("退出登录")
                            .description(
                                    "需要有效 CSRF Token；销毁当前 Session 并清除认证，匿名会话同样返回 204。退出后重新获取 CSRF Token。")
                            .tags(List.of("邮箱认证"))
                            .addParametersItem(
                                    new Parameter()
                                            .name("X-CSRF-TOKEN")
                                            .in("header")
                                            .required(true)
                                            .description("通过获取 CSRF Token 接口取得的值")
                                            .schema(new StringSchema()))
                            .responses(
                                    new ApiResponses()
                                            .addApiResponse(
                                                    "204",
                                                    new ApiResponse().description("已退出，无响应体"))
                                            .addApiResponse(
                                                    "403",
                                                    new ApiResponse()
                                                            .$ref(
                                                                    "#/components/responses/IdentityForbidden"))
                                            .addApiResponse(
                                                    "503",
                                                    new ApiResponse()
                                                            .$ref(
                                                                    "#/components/responses/IdentityUnavailable")));
            var path =
                    api.getPaths()
                            .computeIfAbsent("/api/v1/auth/logout", ignored -> new PathItem());
            path.setPost(logout);
        };
    }
}
