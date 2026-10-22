package io.github.ringotangs.pikdo.server;

import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Isolates Web regression contexts without changing their application profiles. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(
        properties = {
            "pikdo.auth.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
@org.springframework.test.context.bean.override.mockito.MockitoBean(
        types = {
            io.github.ringotangs.pikdo.server.identity.persistence.UserMapper.class,
            io.github.ringotangs.pikdo.server.identity.persistence.AccountMapper.class,
            io.github.ringotangs.pikdo.server.identity.persistence.EmailChallengeMapper.class,
            io.github.ringotangs.pikdo.server.identity.persistence.AuthenticationRateMapper.class,
            org.springframework.transaction.PlatformTransactionManager.class,
            io.github.ringotangs.pikdo.server.identity.mail.EmailCodeSender.class,
            io.github.ringotangs.pikdo.server.identity.application.AuthenticationRateLimiter.class
        })
@interface WithoutDatabase {}
