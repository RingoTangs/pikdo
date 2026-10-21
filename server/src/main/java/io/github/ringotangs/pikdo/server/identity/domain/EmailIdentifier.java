package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

// 复用 EmailAddress 的验证、规范化及相等语义，不引入邮箱提供商专用的合并规则。
public record EmailIdentifier(EmailAddress email) implements LoginIdentifier {
    public EmailIdentifier {
        Objects.requireNonNull(email, "email");
    }

    @Override
    public IdentityType getType() {
        return IdentityType.EMAIL;
    }

    @Override
    public String getOriginalIdentifier() {
        return email.getOriginalAddress();
    }

    @Override
    public String getCanonicalIdentifier() {
        return email.getCanonicalAddress();
    }
}
