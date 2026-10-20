package io.github.ringotangs.pikdo.server.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String getCode();

    HttpStatus getStatus();

    String getMessageKey();
}
