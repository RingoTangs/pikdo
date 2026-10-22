package io.github.ringotangs.pikdo.server.identity.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.github.ringotangs.pikdo.server.Application;
import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.identity.support.AuthenticationTestConfiguration;
import io.github.ringotangs.pikdo.server.identity.support.AuthenticationTestConfiguration.*;
import io.github.ringotangs.pikdo.server.shared.id.UuidV7Generator;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 真正的 PostgreSQL、Flyway 和 JDBC Session；仅邮件投递及时间由测试控制。 */
@SpringBootTest(
        classes = Application.class,
        properties = {
            "pikdo.auth.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "pikdo.auth.smtp-host=localhost",
            "pikdo.auth.mail-from=noreply@pikdo.invalid"
        })
@AutoConfigureMockMvc
@Import({AuthenticationTestConfiguration.class, EmailAuthenticationIT.Database.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailAuthenticationIT {
    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean
        @Primary
        TestSecurityContexts testSecurityContexts() {
            return new TestSecurityContexts();
        }

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:18.6")
                    .withDatabaseName("pikdo_auth_test")
                    .withUsername("auth_test")
                    .withPassword(UUID.randomUUID().toString());
        }
    }

    static final class TestSecurityContexts
            extends org.springframework.security.web.context.HttpSessionSecurityContextRepository {
        volatile boolean fail;

        @Override
        public void saveContext(
                org.springframework.security.core.context.SecurityContext context,
                jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response) {
            if (fail)
                throw new org.springframework.dao.DataAccessResourceFailureException(
                        "Test session store failure");
            super.saveContext(context, request, response);
        }
    }

    @Autowired TestSecurityContexts contexts;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestMail mail;
    @Autowired TestClock clock;
    @Autowired EmailAuthenticationService service;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;

    @BeforeEach
    void reset() {
        for (String table :
                List.of(
                        "spring_session",
                        "identity_email_challenges",
                        "identity_auth_rate_limits",
                        "identity_accounts",
                        "identity_password_credentials",
                        "identity_users")) jdbc.update("DELETE FROM " + table);
        contexts.fail = false;
        clock.reset();
        mail.clear();
    }

    final class Browser {
        final Map<String, Cookie> cookies = new HashMap<>();
        String token;
        String header;

        MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
            if (!cookies.isEmpty()) builder.cookie(cookies.values().toArray(Cookie[]::new));
            var result = mvc.perform(builder).andReturn();
            for (var cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) cookies.remove(cookie.getName());
                else cookies.put(cookie.getName(), cookie);
            }
            return result;
        }

        void csrf() throws Exception {
            var response = perform(get("/api/v1/auth/csrf"));
            assertEquals(200, response.getResponse().getStatus());
            var body = json.readTree(response.getResponse().getContentAsString());
            token = body.get("token").asText();
            header = body.get("headerName").asText();
        }

        MvcResult post(String path, Object body) throws Exception {
            return perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/auth/" + path)
                            .header(header, token)
                            .contentType("application/json")
                            .content(json.writeValueAsBytes(body)));
        }

        String send(String purpose, String email) throws Exception {
            var result = post(purpose + "-codes", Map.of("email", email));
            assertEquals(
                    202,
                    result.getResponse().getStatus(),
                    result.getResponse().getContentAsString());
            var body = json.readTree(result.getResponse().getContentAsString());
            assertEquals(
                    Set.of("challengeId", "expiresAt", "retryAfterSeconds"), body.propertyNames());
            return body.get("challengeId").asText();
        }

        MvcResult verify(String purpose, String id, String email, String code) throws Exception {
            return post(purpose, Map.of("challengeId", id, "email", email, "code", code));
        }
    }

    Browser browser() throws Exception {
        var browser = new Browser();
        browser.csrf();
        return browser;
    }

    UserId seed(String email) {
        var id = new UserId(UuidV7Generator.generate());
        users.insert(User.create(id));
        accounts.insert(
                Account.create(
                        new AccountId(UuidV7Generator.generate()),
                        id,
                        new EmailIdentifier(EmailAddress.create(email))));
        return id;
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void registrationCreatesOnlyUserAndAccountAndRotatesSession() throws Exception {
        var browser = browser();
        var original = browser.cookies.get("__Host-PIKDOSESSION").getValue();
        var id = browser.send("registration", "Alice@Example.com");
        var code = mail.code();
        assertEquals(
                204,
                browser.verify("registrations", id, "alice@example.com", code)
                        .getResponse()
                        .getStatus());
        assertNotEquals(original, browser.cookies.get("__Host-PIKDOSESSION").getValue());
        assertEquals(1, count("identity_users"));
        assertEquals(1, count("identity_accounts"));
        assertEquals(0, count("identity_password_credentials"));
        var me = browser.perform(get("/api/v1/auth/me"));
        assertEquals(200, me.getResponse().getStatus());
        assertEquals(
                "ACTIVE",
                json.readTree(me.getResponse().getContentAsString()).get("status").asText());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_email_challenges WHERE consumed_at IS NOT NULL",
                        Integer.class));
        browser.csrf();
        assertEquals(204, browser.post("logout", Map.of()).getResponse().getStatus());
        assertEquals(401, browser.perform(get("/api/v1/auth/me")).getResponse().getStatus());
    }

    @Test
    void loginUsesStoredOriginalAddressAndRejectsReplay() throws Exception {
        var user = seed("Original@Example.com");
        var browser = browser();
        var id = browser.send("login", "ORIGINAL@example.com");
        var code = mail.code();
        assertEquals("Original@Example.com", mail.deliveries.getLast().recipient());
        assertEquals(
                204,
                browser.verify("login", id, "original@example.com", code)
                        .getResponse()
                        .getStatus());
        assertEquals(
                user.value().toString(),
                json.readTree(
                                browser.perform(get("/api/v1/auth/me"))
                                        .getResponse()
                                        .getContentAsString())
                        .get("userId")
                        .asText());
        browser.csrf();
        browser.post("logout", Map.of());
        browser.csrf();
        assertEquals(
                401,
                browser.verify("login", id, "original@example.com", code)
                        .getResponse()
                        .getStatus());
    }

    @Test
    void wrongCodesCommitAttemptBudgetAndEventuallyInvalidate() throws Exception {
        var browser = browser();
        var id = browser.send("registration", "wrong@example.com");
        var correct = mail.code();
        var wrong = correct.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 5; i++)
            assertEquals(
                    401,
                    browser.verify("registrations", id, "wrong@example.com", wrong)
                            .getResponse()
                            .getStatus());
        assertEquals(
                5,
                jdbc.queryForObject(
                        "SELECT attempts FROM identity_email_challenges", Integer.class));
        assertEquals(
                401,
                browser.verify("registrations", id, "wrong@example.com", correct)
                        .getResponse()
                        .getStatus());
        assertEquals(0, count("identity_users"));
    }

    @Test
    void expiryPurposeAndBrowserBindingAreEnforced() throws Exception {
        var first = browser();
        var id = first.send("registration", "expiry@example.com");
        var code = mail.code();
        assertEquals(
                401,
                first.verify("login", id, "expiry@example.com", code).getResponse().getStatus());
        assertEquals(
                401,
                browser()
                        .verify("registrations", id, "expiry@example.com", code)
                        .getResponse()
                        .getStatus());
        clock.advance(Duration.ofMinutes(5));
        assertEquals(
                401,
                first.verify("registrations", id, "expiry@example.com", code)
                        .getResponse()
                        .getStatus());
        assertEquals(0, count("identity_users"));
    }

    @Test
    void registeredUnknownAndDisabledRequestsHaveSamePublicShape() throws Exception {
        var user = seed("existing@example.com");
        var browser = browser();
        browser.send("registration", "existing@example.com");
        clock.advance(Duration.ofSeconds(61));
        browser.send("login", "unknown@example.com");
        var disabled = users.findById(user).orElseThrow();
        disabled.disable();
        users.updateStatus(disabled);
        clock.advance(Duration.ofSeconds(61));
        browser.send("login", "existing@example.com");
        assertTrue(mail.deliveries.isEmpty());
        assertEquals(1, count("identity_users"));
    }

    @Test
    void disablingAfterIssuePreventsLogin() throws Exception {
        var user = seed("disabled@example.com");
        var browser = browser();
        var id = browser.send("login", "disabled@example.com");
        var code = mail.code();
        jdbc.update("UPDATE identity_users SET status='DISABLED' WHERE id=?", user.value());
        assertEquals(
                401,
                browser.verify("login", id, "disabled@example.com", code)
                        .getResponse()
                        .getStatus());
    }

    @Test
    void csrfCrossSiteCookiesAndCooldownAreEnforced() throws Exception {
        assertEquals(
                403,
                mvc.perform(
                                post("/api/v1/auth/registration-codes")
                                        .contentType("application/json")
                                        .content("{\"email\":\"csrf@example.com\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(
                403,
                mvc.perform(get("/api/v1/auth/csrf").header("Sec-Fetch-Site", "cross-site"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        var browser = browser();
        var cookie = browser.cookies.get("__Host-PIKDOSESSION");
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.getSecure());
        assertEquals("/", cookie.getPath());
        browser.send("registration", "rate@example.com");
        assertEquals(
                429,
                browser.post("login-codes", Map.of("email", "RATE@example.com"))
                        .getResponse()
                        .getStatus());
        assertEquals(1, mail.deliveries.size());
    }

    @Test
    void concurrentConsumptionOnlySucceedsOnce() throws Exception {
        seed("race@example.com");
        String flow = "test-flow";
        var challenge =
                service.send(
                        CodePurpose.LOGIN,
                        EmailAddress.create("race@example.com"),
                        flow,
                        "source-send");
        String code = mail.code();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task =
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        try {
                            service.complete(
                                    CodePurpose.LOGIN,
                                    challenge.challengeId(),
                                    EmailAddress.create("race@example.com"),
                                    code,
                                    flow,
                                    UUID.randomUUID().toString());
                            return true;
                        } catch (AuthenticationFailure failure) {
                            assertEquals(AuthenticationFailure.Reason.INVALID, failure.reason());
                            return false;
                        }
                    };
            var first = executor.submit(task);
            var second = executor.submit(task);
            assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, count("identity_users"));
        assertEquals(0, count("identity_password_credentials"));
    }

    @Autowired VerificationCodes verificationCodes;
    @Autowired AuthenticationRateLimiter limiter;
    @Autowired AuthenticationProperties properties;

    @Autowired
    io.github.ringotangs.pikdo.server.identity.persistence.EmailChallengeMapper challenges;

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void registrationFailureRollsBackUserAndChallengeConsumption() {
        var email = EmailAddress.create("rollback@example.com");
        var challenge = service.send(CodePurpose.REGISTER, email, "flow", "send");
        var code = mail.code();
        AccountRepository failing =
                new AccountRepository() {
                    public void insert(Account account) {
                        throw new org.springframework.dao.DataIntegrityViolationException(
                                "Test failure");
                    }

                    public Optional<Account> findById(AccountId id) {
                        return accounts.findById(id);
                    }

                    public Optional<Account> findByIdentifier(LoginIdentifier identifier) {
                        return accounts.findByIdentifier(identifier);
                    }

                    public List<Account> findByUserId(UserId id) {
                        return accounts.findByUserId(id);
                    }
                };
        var broken =
                new EmailAuthenticationService(
                        users,
                        failing,
                        challenges,
                        verificationCodes,
                        limiter,
                        mail,
                        properties,
                        new org.springframework.transaction.support.TransactionTemplate(
                                transactions),
                        clock);
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        broken.complete(
                                CodePurpose.REGISTER,
                                challenge.challengeId(),
                                email,
                                code,
                                "flow",
                                "verify"));
        assertEquals(0, count("identity_users"));
        assertEquals(0, count("identity_accounts"));
        assertNull(
                jdbc.queryForObject(
                        "SELECT consumed_at FROM identity_email_challenges",
                        java.time.OffsetDateTime.class));
        assertNotNull(
                service.complete(
                        CodePurpose.REGISTER,
                        challenge.challengeId(),
                        email,
                        code,
                        "flow",
                        "retry"));
    }

    @Test
    void concurrentRegistrationCannotCreateDuplicateOrOrphanUsers() throws Exception {
        var email = EmailAddress.create("registration-race@example.com");
        var first = service.send(CodePurpose.REGISTER, email, "first", "send-first");
        var firstCode = mail.code();
        clock.advance(Duration.ofSeconds(61));
        var second = service.send(CodePurpose.REGISTER, email, "second", "send-second");
        var secondCode = mail.code();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> firstTask =
                    () ->
                            completeAtBarrier(
                                    barrier, first.challengeId(), email, firstCode, "first");
            Callable<Boolean> secondTask =
                    () ->
                            completeAtBarrier(
                                    barrier, second.challengeId(), email, secondCode, "second");
            var a = executor.submit(firstTask);
            var b = executor.submit(secondTask);
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, count("identity_users"));
        assertEquals(1, count("identity_accounts"));
        assertEquals(0, count("identity_password_credentials"));
    }

    private boolean completeAtBarrier(
            CyclicBarrier barrier, UUID id, EmailAddress email, String code, String flow)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            service.complete(CodePurpose.REGISTER, id, email, code, flow, flow);
            return true;
        } catch (AuthenticationFailure failure) {
            assertEquals(AuthenticationFailure.Reason.INVALID, failure.reason());
            return false;
        }
    }

    @Test
    void authenticatedSessionChecksUserStatusAndAbsoluteLifetime() throws Exception {
        var user = seed("session@example.com");
        var browser = browser();
        var id = browser.send("login", "session@example.com");
        assertEquals(
                204,
                browser.verify("login", id, "session@example.com", mail.code())
                        .getResponse()
                        .getStatus());
        browser.csrf();
        assertEquals(
                409,
                browser.post("registration-codes", Map.of("email", "other@example.com"))
                        .getResponse()
                        .getStatus());
        clock.advance(Duration.ofHours(12));
        assertEquals(401, browser.perform(get("/api/v1/auth/me")).getResponse().getStatus());
        var next = browser();
        id = next.send("login", "session@example.com");
        assertEquals(
                204,
                next.verify("login", id, "session@example.com", mail.code())
                        .getResponse()
                        .getStatus());
        jdbc.update("UPDATE identity_users SET status='DISABLED' WHERE id=?", user.value());
        assertEquals(401, next.perform(get("/api/v1/auth/me")).getResponse().getStatus());
    }

    @Test
    void smtpFailureKeepsGenericResponseAndInvalidatesChallenge() throws Exception {
        mail.fail = true;
        var browser = browser();
        var id = browser.send("registration", "mail-failure@example.com");
        assertTrue(mail.deliveries.isEmpty());
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT invalidated_at FROM identity_email_challenges",
                        java.time.OffsetDateTime.class));
        assertEquals(
                401,
                browser.verify("registrations", id, "mail-failure@example.com", "000000")
                        .getResponse()
                        .getStatus());
    }

    @Test
    void emailHourlyAndSourceBudgetsAreSharedAcrossPurposes() {
        var email = EmailAddress.create("hourly@example.com");
        for (int i = 0; i < 5; i++) {
            service.send(
                    i % 2 == 0 ? CodePurpose.REGISTER : CodePurpose.LOGIN, email, "flow", "source");
            clock.advance(Duration.ofSeconds(61));
        }
        assertEquals(
                AuthenticationFailure.Reason.RATE_LIMITED,
                assertThrows(
                                AuthenticationFailure.class,
                                () -> service.send(CodePurpose.REGISTER, email, "flow", "source"))
                        .reason());
        for (int i = 0; i < 30; i++) limiter.verify("verification-source");
        assertEquals(
                AuthenticationFailure.Reason.RATE_LIMITED,
                assertThrows(
                                AuthenticationFailure.class,
                                () -> limiter.verify("verification-source"))
                        .reason());
        for (int i = 0; i < 60; i++) limiter.csrf("csrf-source");
        assertThrows(AuthenticationFailure.class, () -> limiter.csrf("csrf-source"));
    }

    @Test
    void sessionSaveFailureDoesNotReportSuccessfulAuthentication() throws Exception {
        var browser = browser();
        var id = browser.send("registration", "session-failure@example.com");
        contexts.fail = true;
        assertEquals(
                503,
                browser.verify("registrations", id, "session-failure@example.com", mail.code())
                        .getResponse()
                        .getStatus());
        contexts.fail = false;
        assertEquals(1, count("identity_users"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM identity_email_challenges WHERE consumed_at IS NOT NULL",
                        Integer.class));
        assertEquals(401, browser.perform(get("/api/v1/auth/me")).getResponse().getStatus());
    }

    @Test
    void expiredJdbcSessionCannotAuthenticate() throws Exception {
        seed("idle@example.com");
        var browser = browser();
        var id = browser.send("login", "idle@example.com");
        assertEquals(
                204,
                browser.verify("login", id, "idle@example.com", mail.code())
                        .getResponse()
                        .getStatus());
        jdbc.update("UPDATE spring_session SET last_access_time=0, expiry_time=0");
        assertEquals(401, browser.perform(get("/api/v1/auth/me")).getResponse().getStatus());
    }

    @Test
    void deliveryExceptionsNeverLeakCodeOrEmailToLogs() {
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(EmailAuthenticationService.class);
        var appender =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var secret = new java.util.concurrent.atomic.AtomicReference<String>();
        io.github.ringotangs.pikdo.server.identity.mail.EmailCodeSender failing =
                (recipient, purpose, code) -> {
                    secret.set(code);
                    throw new IllegalStateException("Sensitive mail: " + recipient + " " + code);
                };
        try {
            var failingService =
                    new EmailAuthenticationService(
                            users,
                            accounts,
                            challenges,
                            verificationCodes,
                            limiter,
                            failing,
                            properties,
                            new org.springframework.transaction.support.TransactionTemplate(
                                    transactions),
                            clock);
            var issued =
                    failingService.send(
                            CodePurpose.REGISTER,
                            EmailAddress.create("secret@example.com"),
                            "private-flow",
                            "source");
            assertEquals(1, appender.list.size());
            var event = appender.list.getFirst();
            assertEquals("Email verification delivery failed", event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
            assertFalse(event.getFormattedMessage().contains(secret.get()));
            assertFalse(issued.toString().contains(secret.get()));
            assertEquals(
                    32,
                    jdbc.queryForObject(
                            "SELECT octet_length(code_digest) FROM identity_email_challenges",
                            Integer.class));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
