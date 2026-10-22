package io.github.ringotangs.pikdo.server.identity.web.dto;

/** 邮箱认证 HTTP 数据契约。 */
public record CurrentUserResponse(String userId, String status) {}
