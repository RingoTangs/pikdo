package io.github.ringotangs.pikdo.server.identity.web.dto;

/** 邮箱认证 HTTP 数据契约。 */
public record CsrfResponse(String headerName, String token) {}
