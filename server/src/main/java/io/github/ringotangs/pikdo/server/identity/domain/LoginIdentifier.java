package io.github.ringotangs.pikdo.server.identity.domain;

/** A login identity value; its presence does not prove ownership. */
public sealed interface LoginIdentifier
        permits EmailIdentifier, PhoneIdentifier, UsernameIdentifier {
    IdentityType getType();

    // 保留输入表示；邮箱和用户名的原始大小写不会被规范化键覆盖。
    String getOriginalIdentifier();

    // 与类型共同组成身份查询键，绑定唯一性由数据库约束保证。
    String getCanonicalIdentifier();
}
