package io.github.ringotangs.pikdo.server.identity.application;

import io.github.ringotangs.pikdo.server.identity.mail.EmailCodeSender;
import io.github.ringotangs.pikdo.server.identity.mail.SmtpEmailCodeSender;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthenticationProperties.class)
@EnableScheduling
public class IdentityAuthenticationConfiguration {
    @Bean
    CookieSerializer authenticationCookieSerializer(Environment environment) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName(
                environment.getProperty(
                        "server.servlet.session.cookie.name", "__Host-PIKDOSESSION"));
        cookie.setUseSecureCookie(
                environment.getProperty(
                        "server.servlet.session.cookie.secure", Boolean.class, true));
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        cookie.setCookiePath("/");
        return cookie;
    }

    @Bean
    @ConditionalOnMissingBean(EmailCodeSender.class)
    EmailCodeSender emailCodeSender(AuthenticationProperties properties, Environment environment) {
        return new SmtpEmailCodeSender(properties, environment.acceptsProfiles(Profiles.of("dev")));
    }

    @Bean
    ThreadPoolTaskExecutor authenticationMailExecutor(AuthenticationProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.mailWorkers());
        executor.setMaxPoolSize(properties.mailWorkers());
        executor.setQueueCapacity(properties.mailQueueCapacity());
        executor.setThreadNamePrefix("authentication-mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 关闭时取消排队任务，避免积压邮件延长进程退出；挑战仍受原有效期限制。
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
