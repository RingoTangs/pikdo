package io.github.ringotangs.pikdo.server.authentication.web.dto;

public record CsrfResponse(String headerName, String token) {}
