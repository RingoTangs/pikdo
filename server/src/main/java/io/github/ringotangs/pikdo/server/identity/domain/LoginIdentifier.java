package io.github.ringotangs.pikdo.server.identity.domain;

public sealed interface LoginIdentifier
        permits EmailIdentifier, PhoneIdentifier, UsernameIdentifier {
    IdentityType getType();

    String getOriginalIdentifier();

    String getCanonicalIdentifier();
}
