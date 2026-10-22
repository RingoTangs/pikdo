package io.github.ringotangs.pikdo.server.identity.domain;

/** 用户主体的启用状态。状态切换不删除用户或改变 UserId，也不代表会话操作。 */
public enum UserStatus {
    /** 用户处于启用状态，不代表已建立认证会话。 */
    ACTIVE,
    /** 用户已禁用，保留标识、绑定和凭据记录。 */
    DISABLED
}
