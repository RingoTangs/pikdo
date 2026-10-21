package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/** Username login identity; constructing it does not reserve or verify a binding. */
public record UsernameIdentifier(Username username) implements LoginIdentifier {
    public UsernameIdentifier {
        Objects.requireNonNull(username, "username");
    }

    @Override
    public IdentityType getType() {
        return IdentityType.USERNAME;
    }

    @Override
    public String getOriginalIdentifier() {
        return username.getOriginalUsername();
    }

    @Override
    public String getCanonicalIdentifier() {
        return username.getCanonicalUsername();
    }
}
