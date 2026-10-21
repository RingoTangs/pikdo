package io.github.ringotangs.pikdo.server.shared.web.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String getCode();

    HttpStatus getStatus();

    String getMessageKey();
}
