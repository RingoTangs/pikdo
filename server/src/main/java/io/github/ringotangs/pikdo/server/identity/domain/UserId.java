package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;
import java.util.UUID;

/** Stable user identity; identifier generation belongs to the caller. */
public record UserId(UUID value) {
    public UserId {
        Objects.requireNonNull(value, "value");
    }
}
