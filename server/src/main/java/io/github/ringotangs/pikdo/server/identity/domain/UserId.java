package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record UserId(UUID value) {
    public UserId {
        Objects.requireNonNull(value, "value");
    }
}
