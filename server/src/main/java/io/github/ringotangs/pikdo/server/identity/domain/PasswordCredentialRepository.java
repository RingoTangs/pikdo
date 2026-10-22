package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Optional;

/** 用户密码凭据的领域持久化契约，以 UserId 作为唯一归属，不按 Account 拆分密码。事务由调用方负责。 */
public interface PasswordCredentialRepository {
    /**
     * 创建记录，唯一性与外键约束失败继续传播；不自行提交事务。认证版本必须为 0，避免插入 SQL 静默丢弃已有版本。
     *
     * @param credential 待创建的初始密码凭据
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 插入未影响恰好一行
     * @throws IllegalArgumentException 认证版本不为 0
     */
    void insert(PasswordCredential credential);

    /**
     * 按用户标识查询记录。
     *
     * @param userId 用户标识
     * @return 查询结果；不存在时返回 Optional.empty()
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 持久化记录无法通过领域还原校验
     */
    Optional<PasswordCredential> findByUserId(UserId userId);

    /**
     * 按预期版本原子替换密码哈希和输入版本并递增认证版本，不自动重试，不修改已有领域对象。
     *
     * @param userId 用户标识
     * @param expectedVersion 预期的非负认证版本
     * @param passwordHash 已由调用方计算的密码哈希，不得传入明文密码
     * @param passwordInputVersion 密码输入处理版本的不透明标识，不解释具体策略
     * @return 成功时为 true；记录不存在或版本不匹配时为 false
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 哈希或输入版本为空白，或预期认证版本为负数
     * @throws IllegalStateException 更新影响行数不是 0 或 1
     */
    boolean updateIfVersionMatches(
            UserId userId, long expectedVersion, String passwordHash, String passwordInputVersion);
}
