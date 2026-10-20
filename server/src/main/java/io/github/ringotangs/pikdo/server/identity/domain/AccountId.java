package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;
import java.util.UUID;

/** Identifies one binding instance; identifier generation belongs to the caller. */
public record AccountId(UUID value) {
    public AccountId {
        Objects.requireNonNull(value, "value");
    }
}
