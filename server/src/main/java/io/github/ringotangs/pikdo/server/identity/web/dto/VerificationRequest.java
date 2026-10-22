package io.github.ringotangs.pikdo.server.identity.web.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record VerificationRequest(
        @NotNull UUID challengeId,
        @NotBlank @Size(max = 254) String email,
        @NotNull @Pattern(regexp = "[0-9]{6}") String code) {
    @Override
    public String toString() {
        return "VerificationRequest[redacted]";
    }
}
