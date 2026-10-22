package io.github.ringotangs.pikdo.server.identity.domain;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class Username {
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,31}");

    private static final Set<String> RESERVED = Set.of("admin", "system", "support");

    private final String originalUsername;

    private final String canonicalUsername;

    private Username(String originalUsername) {
        Objects.requireNonNull(originalUsername, "originalUsername");
        if (!FORMAT.matcher(originalUsername).matches()) {
            throw new IllegalArgumentException("Invalid username format");
        }
        // 仅折叠 ASCII 大小写，不依赖系统 Locale，也不修剪输入。
        char[] characters = originalUsername.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            if (characters[i] >= 'A' && characters[i] <= 'Z') {
                characters[i] = (char) (characters[i] + ('a' - 'A'));
            }
        }
        String canonical = new String(characters);
        if (RESERVED.contains(canonical)) {
            throw new IllegalArgumentException("Reserved username");
        }
        this.originalUsername = originalUsername;
        this.canonicalUsername = canonical;
    }

    public static Username create(String originalUsername) {
        return new Username(originalUsername);
    }

    public String getOriginalUsername() {
        return originalUsername;
    }

    public String getCanonicalUsername() {
        return canonicalUsername;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof Username username
                        && canonicalUsername.equals(username.canonicalUsername);
    }

    @Override
    public int hashCode() {
        return canonicalUsername.hashCode();
    }
}
