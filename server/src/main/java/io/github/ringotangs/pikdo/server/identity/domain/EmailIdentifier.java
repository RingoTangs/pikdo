package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

public record EmailIdentifier(EmailAddress email) implements LoginIdentifier {
    public EmailIdentifier {
        Objects.requireNonNull(email, "email");
    }

    @Override
    public IdentityType getType() {
        return IdentityType.EMAIL;
    }

    @Override
    public String getOriginalIdentifier() {
        return email.getOriginalAddress();
    }

    @Override
    public String getCanonicalIdentifier() {
        return email.getCanonicalAddress();
    }
}
