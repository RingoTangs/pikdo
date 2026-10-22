package io.github.ringotangs.pikdo.server.identity.web.dto;

import jakarta.validation.constraints.*;

public record EmailRequest(@NotBlank @Size(max = 254) String email) {}
