package io.github.ringotangs.pikdo.server.identity.domain;

/** A login identity value; its presence does not prove ownership. */
public sealed interface LoginIdentifier permits EmailIdentifier, PhoneIdentifier {
    IdentityType getType();

    String getOriginalIdentifier();

    String getCanonicalIdentifier();
}
