package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/** 不可变的登录身份绑定。多种身份通过 UserId 共享密码凭据，不保存密码或验证码。构造对象不证明身份控制权、唯一性或绑定已经提交。 */
public final class Account {
    /** 当前绑定实例的独立标识。 */
    private final AccountId id;

    /** 绑定所属 UserId，用户名、邮箱和手机号通过它共享密码凭据。 */
    private final UserId userId;

    /** 当前绑定的不可变登录标识。 */
    private final LoginIdentifier identifier;

    /**
     * 校验并保存构造参数，不生成新标识或执行持久化操作。
     *
     * @param id 登录身份绑定的标识
     * @param userId 用户标识
     * @param identifier 登录标识
     * @throws NullPointerException 必要参数为 null
     */
    private Account(AccountId id, UserId userId, LoginIdentifier identifier) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
    }

    /**
     * 构造不可变绑定，不验证用户存在、控制权、唯一性或提交状态。
     *
     * @param id 登录身份绑定的标识
     * @param userId 用户标识
     * @param identifier 登录标识
     * @return 通过校验的领域对象
     * @throws NullPointerException 必要参数为 null
     */
    public static Account create(AccountId id, UserId userId, LoginIdentifier identifier) {
        return new Account(id, userId, identifier);
    }

    /**
     * 获取稳定标识。
     *
     * @return 稳定标识
     */
    public AccountId getId() {
        return id;
    }

    /**
     * 获取所属用户标识。
     *
     * @return 所属用户标识
     */
    public UserId getUserId() {
        return userId;
    }

    /**
     * 获取绑定的登录标识。
     *
     * @return 绑定的登录标识
     */
    public LoginIdentifier getIdentifier() {
        return identifier;
    }
}
