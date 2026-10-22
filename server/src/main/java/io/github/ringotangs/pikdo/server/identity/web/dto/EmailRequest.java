package io.github.ringotangs.pikdo.server.identity.web.dto;

import jakarta.validation.constraints.*;

/** 邮箱认证 HTTP 数据契约。 */
public record EmailRequest(@NotBlank @Size(max = 254) String email) {}
