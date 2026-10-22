package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 单次登录身份绑定的标识值对象，独立于用户标识，由调用方生成。
 *
 * @param value 非空 UUID 标识，保留调用方提供的 UUID 版本
 */
public record AccountId(UUID value) {
    /**
     * 保存非空的值对象组件，不生成新标识或执行持久化。
     *
     * @param value 非空 UUID 标识，保留调用方提供的 UUID 版本
     * @throws NullPointerException 组件为 null
     */
    public AccountId {
        Objects.requireNonNull(value, "value");
    }
}
