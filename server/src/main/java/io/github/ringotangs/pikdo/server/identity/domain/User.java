package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/** 用户主体，维护稳定的用户标识与启用状态；不承担登录认证或密码管理。 */
public final class User {
    /** 稳定的用户主体标识。 */
    private final UserId id;

    /** 当前启用状态，仅显式调用持久化方法后才会写入数据库。 */
    private UserStatus status;

    /**
     * 校验并保存构造参数，不生成新标识或执行持久化操作。
     *
     * @param id 用户主体标识
     * @throws NullPointerException 必要参数为 null
     */
    private User(UserId id) {
        this.id = Objects.requireNonNull(id, "id");
        this.status = UserStatus.ACTIVE;
    }

    /**
     * 创建 ACTIVE 用户，不执行注册、认证或持久化。
     *
     * @param id 用户主体标识
     * @return 通过校验的领域对象
     * @throws NullPointerException 必要参数为 null
     */
    public static User create(UserId id) {
        return new User(id);
    }

    /**
     * 获取稳定标识。
     *
     * @return 稳定标识
     */
    public UserId getId() {
        return id;
    }

    /**
     * 获取当前用户启用状态。
     *
     * @return 当前用户启用状态
     */
    public UserStatus getStatus() {
        return status;
    }

    /** 将用户标记为 DISABLED；重复调用保持该状态，不删除数据、更换标识或自动撤销会话。 */
    public void disable() {
        status = UserStatus.DISABLED;
    }

    /** 将用户标记为 ACTIVE；重复调用保持该状态，不自动恢复会话或旧绑定。 */
    public void restore() {
        status = UserStatus.ACTIVE;
    }
}
