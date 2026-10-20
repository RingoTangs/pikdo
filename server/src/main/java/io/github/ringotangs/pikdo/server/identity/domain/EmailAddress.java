package io.github.ringotangs.pikdo.server.identity.domain;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A mailbox representation with Pikdo identity equality. Equality does not authorize replacing a
 * verified delivery address or prove mailbox ownership.
 */
public final class EmailAddress {
    private static final int MAX_ADDRESS_LENGTH = 254;
    private static final int MAX_LOCAL_LENGTH = 64;
    private static final int MAX_DOMAIN_LABEL_LENGTH = 63;
    private static final String LOCAL_SPECIAL_CHARACTERS = "!#$%&'*+-/=?^_`{|}~";

    private final String originalAddress;
    private final String canonicalAddress;

    private EmailAddress(String originalAddress) {
        Objects.requireNonNull(originalAddress, "originalAddress");
        validate(originalAddress);
        this.originalAddress = originalAddress;
        this.canonicalAddress = foldAsciiCase(originalAddress);
    }

    public static EmailAddress create(String originalAddress) {
        return new EmailAddress(originalAddress);
    }

    public String getOriginalAddress() {
        return originalAddress;
    }

    public String getCanonicalAddress() {
        return canonicalAddress;
    }

    private static void validate(String address) {
        if (address.isEmpty() || address.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("Invalid email address length");
        }
        for (int i = 0; i < address.length(); i++) {
            char character = address.charAt(i);
            if (character < '!' || character > '~') {
                throw new IllegalArgumentException(
                        "Email address requires printable ASCII without spaces");
            }
        }
        int separator = address.indexOf('@');
        if (separator <= 0
                || separator != address.lastIndexOf('@')
                || separator == address.length() - 1) {
            throw new IllegalArgumentException(
                    "Email address requires one separator and nonempty parts");
        }
        validateLocalPart(address.substring(0, separator));
        validateDomain(address.substring(separator + 1));
    }

    private static void validateLocalPart(String localPart) {
        if (localPart.length() > MAX_LOCAL_LENGTH) {
            throw new IllegalArgumentException("Invalid email local part length");
        }
        boolean previousDot = true;
        for (int i = 0; i < localPart.length(); i++) {
            char character = localPart.charAt(i);
            if (character == '.') {
                if (previousDot) {
                    throw new IllegalArgumentException("Invalid email local part dots");
                }
                previousDot = true;
            } else {
                if (!isAsciiLetterOrDigit(character)
                        && LOCAL_SPECIAL_CHARACTERS.indexOf(character) < 0) {
                    throw new IllegalArgumentException("Invalid email local part character");
                }
                previousDot = false;
            }
        }
        if (previousDot) {
            throw new IllegalArgumentException("Invalid email local part dots");
        }
    }

    private static void validateDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException("Email domain requires at least two labels");
        }
        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > MAX_DOMAIN_LABEL_LENGTH
                    || !isAsciiLetterOrDigit(label.charAt(0))
                    || !isAsciiLetterOrDigit(label.charAt(label.length() - 1))) {
                throw new IllegalArgumentException("Invalid email domain label");
            }
            for (int i = 0; i < label.length(); i++) {
                char character = label.charAt(i);
                if (!isAsciiLetterOrDigit(character) && character != '-') {
                    throw new IllegalArgumentException("Invalid email domain character");
                }
            }
        }
        if (isIpv4Address(labels)) {
            throw new IllegalArgumentException("IP addresses are not supported as email domains");
        }
    }

    private static boolean isIpv4Address(String[] labels) {
        if (labels.length != 4) {
            return false;
        }
        // Check decimal segments locally, including leading zeros; never resolve a host name.
        for (String label : labels) {
            int value = 0;
            for (int i = 0; i < label.length(); i++) {
                char character = label.charAt(i);
                if (character < '0' || character > '9') {
                    return false;
                }
                value = value * 10 + character - '0';
                if (value > 255) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9';
    }

    private static String foldAsciiCase(String address) {
        char[] characters = address.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            if (characters[i] >= 'A' && characters[i] <= 'Z') {
                characters[i] = (char) (characters[i] + ('a' - 'A'));
            }
        }
        return new String(characters);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof EmailAddress email
                        && canonicalAddress.equals(email.canonicalAddress);
    }

    @Override
    public int hashCode() {
        return canonicalAddress.hashCode();
    }
}
