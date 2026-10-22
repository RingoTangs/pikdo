package io.github.ringotangs.pikdo.server;

import io.github.ringotangs.pikdo.server.identity.application.AuthenticationRateLimiter;
import io.github.ringotangs.pikdo.server.identity.mail.EmailCodeSender;
import io.github.ringotangs.pikdo.server.identity.persistence.AccountMapper;
import io.github.ringotangs.pikdo.server.identity.persistence.AuthenticationRateMapper;
import io.github.ringotangs.pikdo.server.identity.persistence.EmailChallengeMapper;
import io.github.ringotangs.pikdo.server.identity.persistence.UserMapper;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

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
@MockitoBean(
        types = {
            UserMapper.class,
            AccountMapper.class,
            EmailChallengeMapper.class,
            AuthenticationRateMapper.class,
            PlatformTransactionManager.class,
            EmailCodeSender.class,
            AuthenticationRateLimiter.class
        })
@interface WithoutDatabase {}
