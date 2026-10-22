package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 稳定的用户标识值对象。由调用方生成标识，读取已有数据时不限制 UUID 版本。
 *
 * @param value 非空 UUID 标识，保留调用方提供的 UUID 版本
 */
public record UserId(UUID value) {
    /**
     * 保存非空的值对象组件，不生成新标识或执行持久化。
     *
     * @param value 非空 UUID 标识，保留调用方提供的 UUID 版本
     * @throws NullPointerException 组件为 null
     */
    public UserId {
        Objects.requireNonNull(value, "value");
    }
}
