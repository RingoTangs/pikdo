package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

public record PhoneIdentifier(PhoneNumber phone) implements LoginIdentifier {
    public PhoneIdentifier {
        Objects.requireNonNull(phone, "phone");
    }

    @Override
    public IdentityType getType() {
        return IdentityType.PHONE;
    }

    @Override
    public String getOriginalIdentifier() {
        return phone.getValue();
    }

    @Override
    public String getCanonicalIdentifier() {
        return phone.getValue();
    }
}
