package io.github.ringotangs.pikdo.server.identity.domain;

/** 登录身份类型，与规范化标识共同确定身份唯一键。 */
public enum IdentityType {
    /** 邮箱登录身份。 */
    EMAIL,
    /** 手机号登录身份。 */
    PHONE,
    /** 用户名登录身份。 */
    USERNAME
}
