package io.github.ringotangs.pikdo.server.authentication.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.ringotangs.pikdo.server.Application;
import io.github.ringotangs.pikdo.server.authentication.domain.CodePurpose;
import io.github.ringotangs.pikdo.server.authentication.domain.VerificationChallenge;
import io.github.ringotangs.pikdo.server.authentication.domain.VerificationChallengeRepository;
import io.github.ringotangs.pikdo.server.authentication.mail.AsyncEmailDelivery;
import io.github.ringotangs.pikdo.server.authentication.mail.EmailCodeSender;
import io.github.ringotangs.pikdo.server.authentication.security.AuthenticationSessionService;
import io.github.ringotangs.pikdo.server.authentication.support.AuthenticationTestConfiguration;
import io.github.ringotangs.pikdo.server.authentication.support.AuthenticationTestConfiguration.*;
import io.github.ringotangs.pikdo.server.authentication.web.EmailAuthenticationWebService;
import io.github.ringotangs.pikdo.server.identity.domain.*;
import io.github.ringotangs.pikdo.server.shared.id.UuidV7Generator;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.awaitility.Awaitility;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 真正的 PostgreSQL、Flyway 和 JDBC Session；仅邮件投递及时间由测试控制。 */
@SpringBootTest(
        classes = Application.class,
        properties = {
            "pikdo.auth.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "pikdo.auth.smtp-host=localhost",
            "pikdo.auth.mail-from=noreply@pikdo.invalid",
            "pikdo.auth.mail-workers=1",
            "pikdo.auth.mail-queue-capacity=1",
            "pikdo.auth.trusted-proxies[0]=10.0.0.0/8",
            "pikdo.auth.trusted-proxies[1]=2001:db8:1::/48"
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

    static final class TestSecurityContexts extends HttpSessionSecurityContextRepository {
        volatile boolean fail;

        @Override
        public void saveContext(
                SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
            if (fail) throw new DataAccessResourceFailureException("Test session store failure");
            super.saveContext(context, request, response);
        }
    }

    @Autowired AsyncEmailDelivery delivery;

    @Autowired
    @Qualifier("authenticationMailExecutor")
    ThreadPoolTaskExecutor mailExecutor;

    void awaitMail() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () ->
                                mailExecutor.getActiveCount() == 0
                                        && mailExecutor.getQueueSize() == 0);
    }

    @AfterEach
    void drainMail() {
        awaitMail();
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
        awaitMail();
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
        String remote = "127.0.0.1";
        String forwarded;
        final Map<String, Cookie> cookies = new HashMap<>();
        String token;
        String header;

        MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
            builder.with(
                    request -> {
                        request.setRemoteAddr(remote);
                        return request;
                    });
            if (forwarded != null) builder.header("X-Forwarded-For", forwarded);
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
                    MockMvcRequestBuilders.post("/api/v1/auth/" + path)
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
            awaitMail();
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

    @Autowired AuthenticationCleanup cleanup;

    @Test
    void cleanupKeepsChallengeRetentionAndUnexpiredRateWindows() {
        var now = OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(
                status -> {
                    for (int hours : new int[] {25, 23})
                        challenges.insert(
                                VerificationChallenge.create(
                                        UuidV7Generator.generate(),
                                        CodePurpose.REGISTER,
                                        EmailAddress.create("cleanup" + hours + "@example.com"),
                                        new byte[32],
                                        new byte[32],
                                        true,
                                        5,
                                        now.minusHours(hours),
                                        null,
                                        null));
                });
        String insert =
                "INSERT INTO identity_auth_rate_limits(scope,subject_digest,window_started_at,expires_at,next_allowed_at,request_count) VALUES(?,?,?,?,?,1)";
        jdbc.update(
                insert,
                "cleanup-expired",
                new byte[32],
                now.minusHours(2),
                now.minusHours(1),
                now.minusHours(1));
        jdbc.update(
                insert, "cleanup-active", new byte[32], now, now.plusHours(1), now.minusSeconds(1));
        jdbc.update(
                insert,
                "cleanup-cooldown",
                new byte[32],
                now.minusHours(2),
                now.minusHours(1),
                now.plusHours(1));
        cleanup.clean();
        assertEquals(1, count("identity_email_challenges"));
        assertEquals(
                "cleanup23@example.com",
                jdbc.queryForObject(
                        "SELECT canonical_email FROM identity_email_challenges", String.class));
        assertEquals(
                Set.of("cleanup-active", "cleanup-cooldown"),
                new HashSet<>(
                        jdbc.queryForList(
                                "SELECT scope FROM identity_auth_rate_limits", String.class)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyJdbcSessionRemainsAuthenticatedAndStillEnforcesUserAndLifetimeChecks(
            boolean disableUser) throws Exception {
        var userId = new UserId(UUID.fromString("0198a7b2-1234-7000-8000-000000000001"));
        users.insert(User.create(userId));
        var browser = browser();
        byte[] legacy;
        try (var input =
                getClass().getResourceAsStream("/authentication/legacy-security-context.ser")) {
            assertNotNull(input);
            legacy = input.readAllBytes();
        }
        var primaryId = jdbc.queryForObject("SELECT primary_id FROM spring_session", String.class);
        jdbc.update(
                "INSERT INTO spring_session_attributes(session_primary_id,attribute_name,attribute_bytes) VALUES(?,?,?)",
                primaryId,
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                legacy);
        clock.advance(Duration.between(clock.instant(), Instant.parse("2026-10-22T00:00:30Z")));
        var current = browser.perform(get("/api/v1/auth/me"));
        assertEquals(200, current.getResponse().getStatus());
        var body = json.readTree(current.getResponse().getContentAsString());
        assertEquals(Set.of("userId", "status"), body.propertyNames());
        assertEquals(userId.value().toString(), body.path("userId").asText());
        assertEquals("ACTIVE", body.path("status").asText());
        if (disableUser) {
            var user = users.findById(userId).orElseThrow();
            user.disable();
            users.updateStatus(user);
        } else {
            clock.advance(Duration.ofHours(12));
        }
        assertEquals(401, browser.perform(get("/api/v1/auth/me")).getResponse().getStatus());
        assertEquals(0, count("spring_session"));
    }

    @Test
    void registrationCreatesOnlyUserAndAccountAndRotatesSession() throws Exception {
        var browser = browser();
        var original = browser.cookies.get("__Host-PIKDOSESSION").getValue();
        var id = browser.send("registration", "Alice@Example.com");
        var code = mail.code("Alice@Example.com", CodePurpose.REGISTER);
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
        var code = mail.code("Original@Example.com", CodePurpose.LOGIN);
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
        var correct = mail.code("wrong@example.com", CodePurpose.REGISTER);
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
    void failedAttemptIsRestoredBeforeSuccessfulConsumption() {
        var email = EmailAddress.create("retry-success@example.com");
        var issued = service.send(CodePurpose.REGISTER, email, "flow", "send-source");
        var code = mail.code(email.getOriginalAddress(), CodePurpose.REGISTER);
        var wrong = code.equals("000000") ? "000001" : "000000";
        var failure =
                assertThrows(
                        AuthenticationFailure.class,
                        () ->
                                service.complete(
                                        CodePurpose.REGISTER,
                                        issued.challengeId(),
                                        email,
                                        wrong,
                                        "flow",
                                        "verify-source"));
        assertEquals(AuthenticationFailure.Reason.INVALID, failure.reason());
        var transaction = new TransactionTemplate(transactions);
        var restored =
                transaction.execute(
                        status -> challenges.lockById(issued.challengeId()).orElseThrow());
        assertNotNull(restored);
        assertEquals(1, restored.attempts());
        assertNull(restored.consumedAt());
        var userId =
                service.complete(
                        CodePurpose.REGISTER,
                        issued.challengeId(),
                        email,
                        code,
                        "flow",
                        "verify-source");
        assertTrue(users.findById(userId).isPresent());
        var consumed =
                transaction.execute(
                        status -> challenges.lockById(issued.challengeId()).orElseThrow());
        assertNotNull(consumed);
        assertEquals(1, consumed.attempts());
        assertNotNull(consumed.consumedAt());
        var replay =
                assertThrows(
                        AuthenticationFailure.class,
                        () ->
                                service.complete(
                                        CodePurpose.REGISTER,
                                        issued.challengeId(),
                                        email,
                                        code,
                                        "flow",
                                        "verify-source"));
        assertEquals(AuthenticationFailure.Reason.INVALID, replay.reason());
        assertEquals(1, count("identity_users"));
    }

    @Test
    void expiryPurposeAndBrowserBindingAreEnforced() throws Exception {
        var first = browser();
        var id = first.send("registration", "expiry@example.com");
        var code = mail.code("expiry@example.com", CodePurpose.REGISTER);
        assertEquals(
                401,
                first.verify("login", id, "expiry@example.com", code).getResponse().getStatus());
        assertEquals(
                401,
                browser()
                        .verify("registrations", id, "expiry@example.com", code)
                        .getResponse()
                        .getStatus());
        assertEquals(
                401,
                first.verify("registrations", id, "other@example.com", code)
                        .getResponse()
                        .getStatus());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT attempts FROM identity_email_challenges", Integer.class));
        assertNull(
                jdbc.queryForObject(
                        "SELECT invalidated_at FROM identity_email_challenges",
                        OffsetDateTime.class));
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
        var code = mail.code("disabled@example.com", CodePurpose.LOGIN);
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
        String code = mail.code("race@example.com", CodePurpose.LOGIN);
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

    @Autowired VerificationChallengeRepository challenges;

    @Autowired PlatformTransactionManager transactions;

    @Test
    void registrationFailureRollsBackUserAndChallengeConsumption() {
        var email = EmailAddress.create("rollback@example.com");
        var challenge = service.send(CodePurpose.REGISTER, email, "flow", "send");
        var code = mail.code("rollback@example.com", CodePurpose.REGISTER);
        AccountRepository failing =
                new AccountRepository() {
                    public void insert(@NonNull Account account) {
                        throw new DataIntegrityViolationException("Test failure");
                    }

                    public Optional<Account> findById(@NonNull AccountId id) {
                        return accounts.findById(id);
                    }

                    public Optional<Account> findByIdentifier(@NonNull LoginIdentifier identifier) {
                        return accounts.findByIdentifier(identifier);
                    }

                    public List<Account> findByUserId(@NonNull UserId id) {
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
                        delivery,
                        properties,
                        transactions,
                        clock);
        assertThrows(
                DataIntegrityViolationException.class,
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
                        "SELECT consumed_at FROM identity_email_challenges", OffsetDateTime.class));
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
        var firstCode = mail.code("registration-race@example.com", CodePurpose.REGISTER);
        clock.advance(Duration.ofSeconds(61));
        var second = service.send(CodePurpose.REGISTER, email, "second", "send-second");
        awaitMail();
        var secondCode = mail.code("registration-race@example.com", CodePurpose.REGISTER);
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
                browser.verify(
                                "login",
                                id,
                                "session@example.com",
                                mail.code("session@example.com", CodePurpose.LOGIN))
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
                next.verify(
                                "login",
                                id,
                                "session@example.com",
                                mail.code("session@example.com", CodePurpose.LOGIN))
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
                        OffsetDateTime.class));
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
                browser.verify(
                                "registrations",
                                id,
                                "session-failure@example.com",
                                mail.code("session-failure@example.com", CodePurpose.REGISTER))
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
                browser.verify(
                                "login",
                                id,
                                "idle@example.com",
                                mail.code("idle@example.com", CodePurpose.LOGIN))
                        .getResponse()
                        .getStatus());
        jdbc.update("UPDATE spring_session SET last_access_time=0, expiry_time=0");
        assertEquals(401, browser.perform(get("/api/v1/auth/me")).getResponse().getStatus());
    }

    @Test
    void deliveryExceptionsNeverLeakCodeOrEmailToLogs() {
        var logger = (Logger) LoggerFactory.getLogger(AsyncEmailDelivery.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var secret = new AtomicReference<String>();
        EmailCodeSender failing =
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
                            newDelivery(failing),
                            properties,
                            transactions,
                            clock);
            var issued =
                    failingService.send(
                            CodePurpose.REGISTER,
                            EmailAddress.create("secret@example.com"),
                            "private-flow",
                            "source");
            awaitMail();
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

    private AsyncEmailDelivery newDelivery(EmailCodeSender sender) {
        return new AsyncEmailDelivery(mailExecutor, sender, challenges, transactions, clock);
    }

    @Test
    void smtpDoesNotBlockHttpAndIneligibleChallengesUseTheSameQueue() throws Exception {
        mail.block();
        var browser = browser();
        try (var requests = Executors.newSingleThreadExecutor()) {
            var response =
                    requests.submit(
                            () ->
                                    browser.post(
                                            "registration-codes",
                                            Map.of("email", "slow@example.com")));
            assertTrue(mail.entered.await(5, TimeUnit.SECONDS));
            var result = response.get(2, TimeUnit.SECONDS);
            assertEquals(202, result.getResponse().getStatus());
            var unknown =
                    browser.post("login-codes", Map.of("email", "unknown-queued@example.com"));
            assertEquals(202, unknown.getResponse().getStatus());
            assertEquals(1, mailExecutor.getQueueSize());
            assertEquals(
                    json.readTree(result.getResponse().getContentAsString()).propertyNames(),
                    json.readTree(unknown.getResponse().getContentAsString()).propertyNames());
            assertTrue(mail.deliveries.isEmpty());
        } finally {
            mail.unblock();
            awaitMail();
        }
        assertEquals(1, mail.deliveries.size());
    }

    @Test
    void fullQueueRejectsWithoutSmtpOnRequestThreadAndInvalidatesChallenge() throws Exception {
        mail.block();
        var browser = browser();
        try {
            assertEquals(
                    202,
                    browser.post("registration-codes", Map.of("email", "blocked@example.com"))
                            .getResponse()
                            .getStatus());
            assertTrue(mail.entered.await(5, TimeUnit.SECONDS));
            assertEquals(
                    202,
                    browser.post("registration-codes", Map.of("email", "queued@example.com"))
                            .getResponse()
                            .getStatus());
            var rejected =
                    browser.post("registration-codes", Map.of("email", "rejected@example.com"));
            assertEquals(202, rejected.getResponse().getStatus());
            assertEquals(
                    Set.of("challengeId", "expiresAt", "retryAfterSeconds"),
                    json.readTree(rejected.getResponse().getContentAsString()).propertyNames());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM identity_email_challenges WHERE canonical_email='rejected@example.com' AND invalidated_at IS NOT NULL",
                            Integer.class));
            assertTrue(mail.deliveries.isEmpty());
        } finally {
            mail.unblock();
            awaitMail();
        }
        assertEquals(2, mail.deliveries.size());
        assertTrue(
                mail.deliveries.stream()
                        .noneMatch(value -> value.recipient().equals("rejected@example.com")));
    }

    @Test
    void queuedChallengeDoesNotExtendExpiryAndIsNotSentAfterExpiration() throws Exception {
        mail.block();
        var browser = browser();
        try {
            browser.post("registration-codes", Map.of("email", "first-blocked@example.com"));
            assertTrue(mail.entered.await(5, TimeUnit.SECONDS));
            var response =
                    browser.post(
                            "registration-codes", Map.of("email", "expired-queued@example.com"));
            assertEquals(202, response.getResponse().getStatus());
            var expiry =
                    Instant.parse(
                            json.readTree(response.getResponse().getContentAsString())
                                    .get("expiresAt")
                                    .asText());
            assertEquals(clock.instant().plusSeconds(300), expiry);
            clock.advance(Duration.ofMinutes(5));
        } finally {
            mail.unblock();
            awaitMail();
        }
        assertEquals(1, mail.deliveries.size());
    }

    @Test
    void rollbackDoesNotScheduleMail() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(
                status -> {
                    service.send(
                            CodePurpose.REGISTER,
                            EmailAddress.create("not-committed@example.com"),
                            "flow",
                            "source");
                    assertEquals(0, mailExecutor.getActiveCount());
                    status.setRollbackOnly();
                });
        awaitMail();
        assertEquals(0, count("identity_email_challenges"));
        assertTrue(mail.deliveries.isEmpty());
    }

    @Test
    void spoofedXffCannotSplitAnyAuthenticationHttpBudget() throws Exception {
        for (String endpoint : List.of("registration-codes", "login")) {
            for (int i = 0; i < (endpoint.equals("login") ? 30 : 10); i++)
                assertEquals(
                        403,
                        mvc.perform(
                                        post("/api/v1/auth/" + endpoint)
                                                .with(
                                                        request -> {
                                                            request.setRemoteAddr("192.0.2.8");
                                                            return request;
                                                        })
                                                .header("X-Forwarded-For", "203.0.113." + (i + 1)))
                                .andReturn()
                                .getResponse()
                                .getStatus());
            assertEquals(
                    429,
                    mvc.perform(
                                    post("/api/v1/auth/" + endpoint)
                                            .with(
                                                    request -> {
                                                        request.setRemoteAddr("192.0.2.8");
                                                        return request;
                                                    })
                                            .header("X-Forwarded-For", "203.0.113.250"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        }
        for (int i = 0; i < 60; i++)
            assertEquals(
                    200,
                    mvc.perform(
                                    get("/api/v1/auth/csrf")
                                            .with(
                                                    request -> {
                                                        request.setRemoteAddr("192.0.2.9");
                                                        return request;
                                                    })
                                            .header("X-Forwarded-For", "203.0.113." + (i + 1)))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        assertEquals(
                429,
                mvc.perform(
                                get("/api/v1/auth/csrf")
                                        .with(
                                                request -> {
                                                    request.setRemoteAddr("192.0.2.9");
                                                    return request;
                                                })
                                        .header("X-Forwarded-For", "203.0.113.250"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }

    @Test
    void trustedProxyClientsUseIndependentAndCanonicalBudgets() throws Exception {
        for (int i = 0; i < 10; i++)
            assertEquals(
                    403,
                    mvc.perform(
                                    post("/api/v1/auth/registration-codes")
                                            .with(
                                                    request -> {
                                                        request.setRemoteAddr("10.0.0.1");
                                                        return request;
                                                    })
                                            .header("X-Forwarded-For", "192.0.2.8, 10.0.0.2"))
                            .andReturn()
                            .getResponse()
                            .getStatus());
        assertEquals(
                429,
                mvc.perform(
                                post("/api/v1/auth/registration-codes")
                                        .with(
                                                request -> {
                                                    request.setRemoteAddr("10.0.0.1");
                                                    return request;
                                                })
                                        .header("X-Forwarded-For", "::ffff:192.0.2.8, 10.0.0.2"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
        assertEquals(
                403,
                mvc.perform(
                                post("/api/v1/auth/registration-codes")
                                        .with(
                                                request -> {
                                                    request.setRemoteAddr("10.0.0.1");
                                                    return request;
                                                })
                                        .header("X-Forwarded-For", "192.0.2.9, 10.0.0.2"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }

    @Test
    void allAuthenticationEntrypointsShareTheResolvedClientAddress() throws Exception {
        var browser = new Browser();
        browser.remote = "10.0.0.1";
        browser.forwarded = "::ffff:192.0.2.8, 10.0.0.2";
        browser.csrf();
        var id = browser.send("registration", "proxy-flow@example.com");
        assertEquals(
                204,
                browser.verify(
                                "registrations",
                                id,
                                "proxy-flow@example.com",
                                mail.code("proxy-flow@example.com", CodePurpose.REGISTER))
                        .getResponse()
                        .getStatus());
        for (String scope :
                List.of(
                        "HTTP_SEND",
                        "SOURCE_SEND",
                        "HTTP_VERIFY",
                        "SOURCE_VERIFY",
                        "SOURCE_CSRF")) {
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM identity_auth_rate_limits WHERE scope=? AND subject_digest=?",
                            Integer.class,
                            scope,
                            verificationCodes.digest("rate", scope, "192.0.2.8")));
        }
    }

    @Autowired ApplicationContext context;

    @Test
    void scannedServicesAndRepositoriesAreUniqueAndRepositoriesSupportSpringProxies() {
        assertFalse(Mockito.mockingDetails(service).isMock());
        assertFalse(Mockito.mockingDetails(limiter).isMock());
        for (Class<?> type :
                List.of(
                        EmailAuthenticationService.class,
                        AuthenticationRateLimiter.class,
                        AuthenticationCleanup.class,
                        VerificationCodes.class,
                        EmailAuthenticationWebService.class,
                        AuthenticationSessionService.class,
                        AsyncEmailDelivery.class,
                        UserRepository.class,
                        AccountRepository.class,
                        VerificationChallengeRepository.class))
            assertEquals(1, context.getBeansOfType(type).size(), type.getSimpleName());
        assertTrue(AopUtils.isAopProxy(users));
        assertTrue(AopUtils.isAopProxy(accounts));
        assertTrue(AopUtils.isAopProxy(challenges));
        assertFalse(context.containsBean("authenticationUsers"));
        assertFalse(context.containsBean("authenticationAccounts"));
    }
}
