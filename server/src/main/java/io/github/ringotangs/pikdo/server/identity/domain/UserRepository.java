package io.github.ringotangs.pikdo.server.identity.domain;

import java.util.Optional;

/** 用户领域持久化契约。事务由调用方负责，修改内存对象不会自动写入数据库。 */
public interface UserRepository {
    /**
     * 创建记录，唯一性与外键约束失败继续传播；不自行提交事务。
     *
     * @param user 待持久化的用户
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 插入未影响恰好一行
     */
    void insert(User user);

    /**
     * 按标识查询记录。
     *
     * @param id 用户主体标识
     * @return 查询结果；不存在时返回 Optional.empty()
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 持久化记录无法通过领域还原校验
     */
    Optional<User> findById(UserId id);

    /**
     * 显式更新用户状态，不改变用户标识；调用方负责事务。
     *
     * @param user 待持久化的用户
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalStateException 更新未影响恰好一行
     */
    void updateStatus(User user);
}
