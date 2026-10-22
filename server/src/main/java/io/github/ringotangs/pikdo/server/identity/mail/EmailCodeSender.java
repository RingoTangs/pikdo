package io.github.ringotangs.pikdo.server.identity.mail;

import io.github.ringotangs.pikdo.server.identity.application.CodePurpose;

/** 仅接收服务端确定的投递地址；测试实现不得进入生产 Artifact。 */
public interface EmailCodeSender {
    void send(String recipient, CodePurpose purpose, String code);
}
