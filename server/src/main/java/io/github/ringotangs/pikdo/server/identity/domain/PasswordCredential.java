package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Objects;

/** 归属于 UserId 的不可变密码凭据，由多个 Account 共享。不保存明文密码，不实现哈希算法、密码验证或安全 rehash。 */
public final class PasswordCredential {
    /** 密码凭据所属用户，也是持久化主键。 */
    private final UserId userId;

    /** 密码哈希不透明字符串，不保存明文，也不应输出到日志。 */
    private final String passwordHash;

    /** 用于原子条件更新的非负认证版本。 */
    private final long authenticationVersion;

    /** 密码输入处理策略版本标识，不在此解释策略或哈希算法。 */
    private final String passwordInputVersion;

    /**
     * 校验并保存构造参数，不生成新标识或执行持久化操作。
     *
     * @param userId 用户标识
     * @param passwordHash 已由调用方计算的密码哈希，不得传入明文密码
     * @param authenticationVersion 非负认证版本
     * @param passwordInputVersion 密码输入处理版本的不透明标识，不解释具体策略
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 哈希或输入版本为空白，或认证版本为负数
     */
    private PasswordCredential(
            UserId userId,
            String passwordHash,
            long authenticationVersion,
            String passwordInputVersion) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.passwordInputVersion =
                Objects.requireNonNull(passwordInputVersion, "passwordInputVersion");
        if (passwordHash.isBlank() || passwordInputVersion.isBlank()) {
            throw new IllegalArgumentException("Password credential strings must not be blank");
        }
        if (authenticationVersion < 0) {
            throw new IllegalArgumentException("Authentication version must not be negative");
        }
        this.authenticationVersion = authenticationVersion;
    }

    /**
     * 创建认证版本为 0 的不可变初始凭据，不计算或验证密码哈希。
     *
     * @param userId 用户标识
     * @param passwordHash 已由调用方计算的密码哈希，不得传入明文密码
     * @param passwordInputVersion 密码输入处理版本的不透明标识，不解释具体策略
     * @return 通过校验的领域对象
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 哈希或输入版本为空白，或认证版本为负数
     */
    public static PasswordCredential create(
            UserId userId, String passwordHash, String passwordInputVersion) {
        return new PasswordCredential(userId, passwordHash, 0, passwordInputVersion);
    }

    /**
     * 还原完整的不可变密码状态，不递增版本或修改持久化记录。
     *
     * @param userId 用户标识
     * @param passwordHash 已由调用方计算的密码哈希，不得传入明文密码
     * @param authenticationVersion 非负认证版本
     * @param passwordInputVersion 密码输入处理版本的不透明标识，不解释具体策略
     * @return 还原的密码凭据
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 哈希或输入版本为空白，或认证版本为负数
     */
    public static PasswordCredential restore(
            UserId userId,
            String passwordHash,
            long authenticationVersion,
            String passwordInputVersion) {
        return new PasswordCredential(
                userId, passwordHash, authenticationVersion, passwordInputVersion);
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
     * 获取未做算法解析的密码哈希，不应输出到日志。
     *
     * @return 未做算法解析的密码哈希，不应输出到日志
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * 获取当前非负认证版本。
     *
     * @return 当前非负认证版本
     */
    public long getAuthenticationVersion() {
        return authenticationVersion;
    }

    /**
     * 获取密码输入处理版本标识。
     *
     * @return 密码输入处理版本标识
     */
    public String getPasswordInputVersion() {
        return passwordInputVersion;
    }

    /**
     * {@inheritDoc} 返回固定脱敏表示，不包含密码哈希或其他字段值。
     *
     * @return 脱敏字符串
     */
    @Override
    public String toString() {
        // 避免日志或调试输出暴露密码哈希，不使用包含字段值的默认表示。
        return "PasswordCredential[redacted]";
    }
}
