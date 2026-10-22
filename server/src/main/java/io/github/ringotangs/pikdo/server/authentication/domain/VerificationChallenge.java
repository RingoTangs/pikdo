package io.github.ringotangs.pikdo.server.authentication.domain;

import io.github.ringotangs.pikdo.server.identity.domain.EmailAddress;

import org.jspecify.annotations.Nullable;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public final class VerificationChallenge {
    private final UUID id;
    private final CodePurpose purpose;
    private final EmailAddress email;
    private final byte[] flowDigest;
    private final byte[] codeDigest;
    private final boolean eligible;
    private int attempts;
    private final int maxAttempts;
    private final OffsetDateTime expiresAt;
    private @Nullable OffsetDateTime consumedAt;
    private @Nullable OffsetDateTime invalidatedAt;
    private final @Nullable UUID accountId;
    private final @Nullable UUID userId;
    private boolean verified;

    private VerificationChallenge(
            UUID id,
            CodePurpose purpose,
            EmailAddress email,
            byte[] flowDigest,
            byte[] codeDigest,
            boolean eligible,
            int attempts,
            int maxAttempts,
            OffsetDateTime expiresAt,
            @Nullable OffsetDateTime consumedAt,
            @Nullable OffsetDateTime invalidatedAt,
            @Nullable UUID accountId,
            @Nullable UUID userId) {
        this.id = Objects.requireNonNull(id, "id");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.email = Objects.requireNonNull(email, "email");
        this.flowDigest = Objects.requireNonNull(flowDigest, "flowDigest").clone();
        this.codeDigest = Objects.requireNonNull(codeDigest, "codeDigest").clone();
        if (flowDigest.length != 32 || codeDigest.length != 32)
            throw new IllegalArgumentException("Challenge digests must contain 32 bytes");
        if (maxAttempts <= 0 || attempts < 0 || attempts > maxAttempts)
            throw new IllegalArgumentException("Invalid challenge attempt budget");
        if (eligible && purpose == CodePurpose.LOGIN && (accountId == null || userId == null))
            throw new IllegalArgumentException("Eligible login requires an account and user");
        this.eligible = eligible;
        this.attempts = attempts;
        this.maxAttempts = maxAttempts;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.consumedAt = consumedAt;
        this.invalidatedAt = invalidatedAt;
        this.accountId = accountId;
        this.userId = userId;
    }

    public static VerificationChallenge create(
            UUID id,
            CodePurpose purpose,
            EmailAddress email,
            byte[] flowDigest,
            byte[] codeDigest,
            boolean eligible,
            int maxAttempts,
            OffsetDateTime expiresAt,
            @Nullable UUID accountId,
            @Nullable UUID userId) {
        return new VerificationChallenge(
                id,
                purpose,
                email,
                flowDigest,
                codeDigest,
                eligible,
                0,
                maxAttempts,
                expiresAt,
                null,
                null,
                accountId,
                userId);
    }

    public static VerificationChallenge restore(
            UUID id,
            CodePurpose purpose,
            EmailAddress email,
            byte[] flowDigest,
            byte[] codeDigest,
            boolean eligible,
            int attempts,
            int maxAttempts,
            OffsetDateTime expiresAt,
            @Nullable OffsetDateTime consumedAt,
            @Nullable OffsetDateTime invalidatedAt,
            @Nullable UUID accountId,
            @Nullable UUID userId) {
        return new VerificationChallenge(
                id,
                purpose,
                email,
                flowDigest,
                codeDigest,
                eligible,
                attempts,
                maxAttempts,
                expiresAt,
                consumedAt,
                invalidatedAt,
                accountId,
                userId);
    }

    public VerificationResult verify(
            CodePurpose purpose,
            EmailAddress email,
            byte[] flowDigest,
            byte[] codeDigest,
            OffsetDateTime now) {
        verified = false;
        // 其他用途、邮箱或浏览器不能消耗本挑战的错误次数。
        if (this.purpose != purpose
                || !this.email.getCanonicalAddress().equals(email.getCanonicalAddress())
                || !MessageDigest.isEqual(this.flowDigest, flowDigest)
                || !isUsable(now)) return VerificationResult.REJECTED;
        if (!MessageDigest.isEqual(this.codeDigest, codeDigest) || !eligible) {
            attempts++;
            if (attempts >= maxAttempts) invalidate(now);
            return VerificationResult.FAILED_ATTEMPT;
        }
        verified = true;
        return VerificationResult.VERIFIED;
    }

    private boolean isUsable(OffsetDateTime now) {
        return consumedAt == null
                && invalidatedAt == null
                && expiresAt.isAfter(now)
                && attempts < maxAttempts;
    }

    public boolean canDeliver(OffsetDateTime now) {
        return eligible && isUsable(now);
    }

    public boolean consume(OffsetDateTime now) {
        if (!verified || !isUsable(now)) return false;
        consumedAt = Objects.requireNonNull(now, "now");
        verified = false;
        return true;
    }

    public boolean invalidate(OffsetDateTime now) {
        if (consumedAt != null || invalidatedAt != null) return false;
        invalidatedAt = Objects.requireNonNull(now, "now");
        verified = false;
        return true;
    }

    public UUID id() {
        return id;
    }

    public CodePurpose purpose() {
        return purpose;
    }

    public String canonicalEmail() {
        return email.getCanonicalAddress();
    }

    public String originalEmail() {
        return email.getOriginalAddress();
    }

    public byte[] flowDigest() {
        return flowDigest.clone();
    }

    public byte[] codeDigest() {
        return codeDigest.clone();
    }

    public boolean eligible() {
        return eligible;
    }

    public int attempts() {
        return attempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public OffsetDateTime expiresAt() {
        return expiresAt;
    }

    public @Nullable OffsetDateTime consumedAt() {
        return consumedAt;
    }

    public @Nullable OffsetDateTime invalidatedAt() {
        return invalidatedAt;
    }

    public @Nullable UUID accountId() {
        return accountId;
    }

    public @Nullable UUID userId() {
        return userId;
    }

    @Override
    public String toString() {
        return "VerificationChallenge[redacted]";
    }
}
