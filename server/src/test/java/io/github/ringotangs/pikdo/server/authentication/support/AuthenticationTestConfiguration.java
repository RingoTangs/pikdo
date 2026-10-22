package io.github.ringotangs.pikdo.server.authentication.support;

import io.github.ringotangs.pikdo.server.authentication.domain.CodePurpose;
import io.github.ringotangs.pikdo.server.authentication.mail.EmailCodeSender;

import org.awaitility.Awaitility;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.*;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        public volatile CountDownLatch entered = new CountDownLatch(0);
        public volatile CountDownLatch release = new CountDownLatch(0);

        public void block() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        public void unblock() {
            release.countDown();
        }

        public void send(String recipient, CodePurpose purpose, String code) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Test delivery timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test delivery interrupted");
            }
            if (fail) throw new IllegalStateException("Test delivery failed");
            deliveries.add(new Delivery(recipient, purpose, code));
        }

        public String code(String recipient, CodePurpose purpose) {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(
                            () ->
                                    deliveries.stream()
                                            .anyMatch(
                                                    value ->
                                                            value.recipient()
                                                                            .equalsIgnoreCase(
                                                                                    recipient)
                                                                    && value.purpose() == purpose));
            return deliveries.stream()
                    .filter(
                            value ->
                                    value.recipient().equalsIgnoreCase(recipient)
                                            && value.purpose() == purpose)
                    .reduce((first, last) -> last)
                    .orElseThrow()
                    .code();
        }

        public void clear() {
            unblock();
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
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
