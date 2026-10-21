package io.github.ringotangs.pikdo.server.identity.domain;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/** Strict international number syntax, without country inference or ownership validation. */
public final class PhoneNumber {
    private static final Pattern FORMAT = Pattern.compile("\\+[1-9][0-9]{1,14}");
    private final String value;

    private PhoneNumber(String value) {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Phone number requires canonical E.164 syntax");
        }
        this.value = value;
    }

    public static PhoneNumber create(String value) {
        return new PhoneNumber(value);
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || other instanceof PhoneNumber phone && value.equals(phone.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
