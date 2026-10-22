package io.github.ringotangs.pikdo.server.identity.domain;

/** 封闭的登录标识契约，统一表达类型、原始值与规范化键；标识存在不代表已验证控制权。 */
public sealed interface LoginIdentifier
        permits EmailIdentifier, PhoneIdentifier, UsernameIdentifier {
    /**
     * 获取登录标识类型。
     *
     * @return 登录标识类型
     */
    IdentityType getType();

    /**
     * 获取原始标识，保留邮箱及用户名的输入大小写。
     *
     * @return 原始标识，保留邮箱及用户名的输入大小写
     */
    String getOriginalIdentifier();

    /**
     * 获取与类型共同用于唯一查询的规范化身份键。
     *
     * @return 与类型共同用于唯一查询的规范化身份键
     */
    String getCanonicalIdentifier();
}
