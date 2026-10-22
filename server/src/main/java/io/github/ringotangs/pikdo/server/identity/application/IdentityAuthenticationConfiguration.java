package io.github.ringotangs.pikdo.server.identity.application;

import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.identity.mail.*;
import io.github.ringotangs.pikdo.server.identity.persistence.*;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** 仅在实际认证用例装配现有 Repository，不改变其类或事务边界。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pikdo.auth.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AuthenticationProperties.class)
@EnableScheduling
public class IdentityAuthenticationConfiguration {
    @Bean
    org.springframework.session.web.http.CookieSerializer authenticationCookieSerializer(
            org.springframework.core.env.Environment environment) {
        var cookie = new org.springframework.session.web.http.DefaultCookieSerializer();
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
    VerificationCodes verificationCodes(AuthenticationProperties properties) {
        return new VerificationCodes(properties.hmacKey());
    }

    @Bean
    UserRepository authenticationUsers(UserMapper mapper) {
        return new UserRepositoryImpl(mapper);
    }

    @Bean
    AccountRepository authenticationAccounts(AccountMapper mapper) {
        return new AccountRepositoryImpl(mapper);
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

    @Bean
    AsyncEmailDelivery asyncEmailDelivery(
            @Qualifier("authenticationMailExecutor") Executor executor,
            EmailCodeSender sender,
            EmailChallengeMapper challenges,
            PlatformTransactionManager manager,
            Clock clock) {
        var transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new AsyncEmailDelivery(executor, sender, challenges, transactions, clock);
    }

    @Bean
    AuthenticationRateLimiter authenticationRateLimiter(
            AuthenticationRateMapper mapper,
            VerificationCodes codes,
            AuthenticationProperties properties,
            PlatformTransactionManager manager,
            Clock clock) {
        return new AuthenticationRateLimiter(
                mapper, codes, properties, new TransactionTemplate(manager), clock);
    }

    @Bean
    EmailAuthenticationService emailAuthenticationService(
            UserRepository users,
            AccountRepository accounts,
            EmailChallengeMapper challenges,
            VerificationCodes codes,
            AuthenticationRateLimiter rates,
            AsyncEmailDelivery delivery,
            AuthenticationProperties properties,
            PlatformTransactionManager manager,
            Clock clock) {
        return new EmailAuthenticationService(
                users,
                accounts,
                challenges,
                codes,
                rates,
                delivery,
                properties,
                new TransactionTemplate(manager),
                clock);
    }

    @Bean
    Cleanup authenticationCleanup(
            EmailChallengeMapper challenges, AuthenticationRateMapper rates, Clock clock) {
        return new Cleanup(challenges, rates, clock);
    }

    static final class Cleanup {
        private final EmailChallengeMapper challenges;
        private final AuthenticationRateMapper rates;
        private final Clock clock;

        Cleanup(EmailChallengeMapper challenges, AuthenticationRateMapper rates, Clock clock) {
            this.challenges = challenges;
            this.rates = rates;
            this.clock = clock;
        }

        @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
        public void clean() {
            var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            challenges.cleanup(now.minusHours(24));
            rates.cleanup(now);
        }
    }
}
