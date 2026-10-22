package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/**
 * 手机号登录标识。PhoneNumber 已要求规范输入，因此原始值与规范化值相同。
 *
 * @param phone 规范手机号值对象
 */
public record PhoneIdentifier(PhoneNumber phone) implements LoginIdentifier {
    /**
     * 保存非空的值对象组件，不生成新标识或执行持久化。
     *
     * @param phone 规范手机号值对象
     * @throws NullPointerException 组件为 null
     */
    public PhoneIdentifier {
        Objects.requireNonNull(phone, "phone");
    }

    /** {@inheritDoc} */
    @Override
    public IdentityType getType() {
        return IdentityType.PHONE;
    }

    /** {@inheritDoc} */
    @Override
    public String getOriginalIdentifier() {
        return phone.getValue();
    }

    /** {@inheritDoc} */
    @Override
    public String getCanonicalIdentifier() {
        return phone.getValue();
    }
}
