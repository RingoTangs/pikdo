package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.List;
import java.util.Optional;

/** 登录身份绑定的领域持久化契约。数据库保证每用户每类型最多一条绑定，以及同类型规范化身份全局唯一；事务由调用方负责。 */
public interface AccountRepository {
    /**
     * 创建记录，唯一性与外键约束失败继续传播；不自行提交事务。
     *
     * @param account 待持久化的登录身份绑定
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 插入未影响恰好一行
     */
    void insert(Account account);

    /**
     * 按标识查询记录。
     *
     * @param id 登录身份绑定的标识
     * @return 查询结果；不存在时返回 Optional.empty()
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 持久化记录无法通过领域还原校验
     */
    Optional<Account> findById(AccountId id);

    /**
     * 按身份类型和规范化值查询，不合并不同类型的身份。
     *
     * @param identifier 登录标识
     * @return 查询结果；不存在时返回 Optional.empty()
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 持久化记录无法通过领域还原校验
     */
    Optional<Account> findByIdentifier(LoginIdentifier identifier);

    /**
     * 按用户标识查询记录。 按 EMAIL、PHONE、USERNAME 顺序返回。
     *
     * @param userId 用户标识
     * @return 该用户的绑定列表，无绑定时为空列表
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 持久化记录无法通过领域还原校验
     */
    List<Account> findByUserId(UserId userId);
}
