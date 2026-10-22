package io.github.ringotangs.pikdo.server.identity.mail;

import io.github.ringotangs.pikdo.server.identity.application.CodePurpose;

public interface EmailCodeSender {
    void send(String recipient, CodePurpose purpose, String code);
}
