package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/**
 * 用户名登录标识，保留原始拼写并以 ASCII 小写比较身份；构造不占用用户名。
 *
 * @param username 已通过校验的用户名值对象
 */
public record UsernameIdentifier(Username username) implements LoginIdentifier {
    /**
     * 保存非空的值对象组件，不生成新标识或执行持久化。
     *
     * @param username 已通过校验的用户名值对象
     * @throws NullPointerException 组件为 null
     */
    public UsernameIdentifier {
        Objects.requireNonNull(username, "username");
    }

    /** {@inheritDoc} */
    @Override
    public IdentityType getType() {
        return IdentityType.USERNAME;
    }

    /** {@inheritDoc} */
    @Override
    public String getOriginalIdentifier() {
        return username.getOriginalUsername();
    }

    /** {@inheritDoc} */
    @Override
    public String getCanonicalIdentifier() {
        return username.getCanonicalUsername();
    }
}
