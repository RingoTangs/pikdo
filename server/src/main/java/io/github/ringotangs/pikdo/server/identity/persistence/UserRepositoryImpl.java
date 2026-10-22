package io.github.ringotangs.pikdo.server.identity.persistence;

import io.github.ringotangs.pikdo.server.identity.domain.User;
import io.github.ringotangs.pikdo.server.identity.domain.UserId;
import io.github.ringotangs.pikdo.server.identity.domain.UserRepository;
import io.github.ringotangs.pikdo.server.identity.domain.UserStatus;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

/** 通过 UserMapper 持久化及还原用户。构造器注入 Mapper，事务由调用方负责。 */
@Repository
@ConditionalOnProperty(name = "pikdo.auth.enabled", havingValue = "true", matchIfMissing = true)
public class UserRepositoryImpl implements UserRepository {
    /** 构造器注入的 Mapper，不在此创建连接或接管事务。 */
    private final UserMapper mapper;

    /**
     * 使用已有 Mapper 创建持久化实现，不接管事务边界。
     *
     * @param mapper 通过构造器提供的 MyBatis Mapper
     * @throws NullPointerException 必要参数为 null
     */
    public UserRepositoryImpl(UserMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** {@inheritDoc} */
    @Override
    public void insert(User user) {
        Objects.requireNonNull(user, "user");
        if (mapper.insert(user.getId().value(), user.getStatus().name()) != 1) {
            throw new IllegalStateException("User insert did not affect exactly one row");
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<User> findById(UserId id) {
        Objects.requireNonNull(id, "id");
        return mapper.findById(id.value()).map(UserRepositoryImpl::fromRow);
    }

    /** {@inheritDoc} */
    @Override
    public void updateStatus(User user) {
        // 修改内存 User 不会自动写库，调用方须显式持久化并负责事务边界。
        Objects.requireNonNull(user, "user");
        if (mapper.updateStatus(user.getId().value(), user.getStatus().name()) != 1) {
            throw new IllegalStateException("User status update did not affect exactly one row");
        }
    }

    /**
     * 通过领域校验还原数据库记录；不信任未知状态、非法字段或不一致的规范化键。
     *
     * @param row 待还原的数据库记录
     * @return 还原的领域对象
     * @throws IllegalStateException 记录无法还原为合法领域对象
     */
    static User fromRow(UserRow row) {
        try {
            var user = User.create(new UserId(row.id()));
            if (UserStatus.valueOf(row.status()) == UserStatus.DISABLED) {
                // Existing lifecycle methods have no event or timestamp side effects.
                user.disable();
            }
            return user;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid persisted user", failure);
        }
    }
}
