package org.example.config;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class RequestIdConfiguration {
    // 仅通过注册 Bean 创建 Filter，不再为 Filter 添加 @Component，避免重复注册。
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setName("requestIdFilter");
        // 尽早建立请求上下文，使后续 MVC 处理及异常日志都能获取 ID。
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        // 分派映射与 Filter 内的分派开关配合，确保额外分派仍经过 Filter。
        registration.setDispatcherTypes(
                DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        return registration;
    }
}
