package io.github.ringotangs.pikdo.server.identity.mail;

import io.github.ringotangs.pikdo.server.identity.application.AuthenticationProperties;
import io.github.ringotangs.pikdo.server.identity.application.CodePurpose;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Set;

public final class SmtpEmailCodeSender implements EmailCodeSender {
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;

    public SmtpEmailCodeSender(AuthenticationProperties properties, boolean dev) {
        if (properties.smtpHost().isBlank() || properties.mailFrom().isBlank())
            throw new IllegalArgumentException("SMTP host and mail sender must be configured");
        if (dev && !Set.of("localhost", "127.0.0.1", "::1").contains(properties.smtpHost()))
            throw new IllegalArgumentException("Development SMTP must use loopback");
        sender.setHost(properties.smtpHost());
        sender.setPort(properties.smtpPort());
        sender.setUsername(properties.smtpUsername());
        sender.setPassword(properties.smtpPassword());
        var mail = sender.getJavaMailProperties();
        mail.setProperty("mail.debug", "false");
        mail.setProperty("mail.smtp.connectiontimeout", "5000");
        mail.setProperty("mail.smtp.timeout", "5000");
        mail.setProperty("mail.smtp.writetimeout", "5000");
        mail.setProperty("mail.smtp.auth", Boolean.toString(!properties.smtpUsername().isBlank()));
        mail.setProperty("mail.smtp.starttls.enable", Boolean.toString(!dev));
        mail.setProperty("mail.smtp.starttls.required", Boolean.toString(!dev));
        from = properties.mailFrom();
    }

    @Override
    public void send(String recipient, CodePurpose purpose, String code) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(
                purpose == CodePurpose.REGISTER ? "Pikdo registration code" : "Pikdo login code");
        message.setText(
                "Your Pikdo verification code is "
                        + code
                        + ". It expires in 5 minutes. If you did not request it, ignore this email.");
        sender.send(message);
    }
}
