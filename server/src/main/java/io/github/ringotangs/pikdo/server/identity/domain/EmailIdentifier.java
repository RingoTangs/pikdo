package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/**
 * 邮箱登录标识，复用 EmailAddress 的验证和相等语义，不合并提供商专用的点号或标签规则。
 *
 * @param email 已通过校验的邮箱值对象
 */
public record EmailIdentifier(EmailAddress email) implements LoginIdentifier {
    /**
     * 保存非空的值对象组件，不生成新标识或执行持久化。
     *
     * @param email 已通过校验的邮箱值对象
     * @throws NullPointerException 组件为 null
     */
    public EmailIdentifier {
        Objects.requireNonNull(email, "email");
    }

    /** {@inheritDoc} */
    @Override
    public IdentityType getType() {
        return IdentityType.EMAIL;
    }

    /** {@inheritDoc} */
    @Override
    public String getOriginalIdentifier() {
        return email.getOriginalAddress();
    }

    /** {@inheritDoc} */
    @Override
    public String getCanonicalIdentifier() {
        return email.getCanonicalAddress();
    }
}
