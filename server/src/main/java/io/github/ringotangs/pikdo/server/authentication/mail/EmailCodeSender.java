package io.github.ringotangs.pikdo.server.authentication.mail;

import io.github.ringotangs.pikdo.server.authentication.domain.CodePurpose;

public interface EmailCodeSender {
    void send(String recipient, CodePurpose purpose, String code);
}
