package io.github.ringotangs.pikdo.server.identity.support;

import io.github.ringotangs.pikdo.server.identity.application.CodePurpose;
import io.github.ringotangs.pikdo.server.identity.mail.EmailCodeSender;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 测试邮件仅留在内存，不连接 SMTP，不输出验证码。 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthenticationTestConfiguration {
    @Bean
    @Primary
    public TestMail testMail() {
        return new TestMail();
    }

    @Bean
    @Primary
    public TestClock testClock() {
        return new TestClock();
    }

    public static final class TestMail implements EmailCodeSender {
        public record Delivery(String recipient, CodePurpose purpose, String code) {
            @Override
            public String toString() {
                return "Delivery[redacted]";
            }
        }

        public final CopyOnWriteArrayList<Delivery> deliveries = new CopyOnWriteArrayList<>();
        public volatile boolean fail;

        public void send(String recipient, CodePurpose purpose, String code) {
            if (fail) throw new IllegalStateException("Test delivery failed");
            deliveries.add(new Delivery(recipient, purpose, code));
        }

        public String code() {
            return deliveries.getLast().code();
        }

        public void clear() {
            deliveries.clear();
            fail = false;
        }
    }

    public static final class TestClock extends Clock {
        private volatile Instant current = Instant.now();

        public void reset() {
            current = Instant.now();
        }

        public void advance(Duration duration) {
            current = current.plus(duration);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return current;
        }
    }
}
