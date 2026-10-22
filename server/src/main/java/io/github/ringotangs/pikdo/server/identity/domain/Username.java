package io.github.ringotangs.pikdo.server.identity.domain;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 大小写不敏感的 ASCII 用户名值对象。长度为 3–32，以字母开头，其余仅允许字母、数字和下划线；精确禁止 admin、system、support。 */
public final class Username {
    /** 用户名完整匹配规则：3–32 个 ASCII 字符，字母开头，仅含字母、数字和下划线。 */
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,31}");

    /** 按规范化值精确匹配的保留用户名集合，不限制其他名称的前缀。 */
    private static final Set<String> RESERVED = Set.of("admin", "system", "support");

    /** 通过校验的原始用户名，保留输入大小写。 */
    private final String originalUsername;

    /** ASCII 小写身份键，不依赖默认 Locale。 */
    private final String canonicalUsername;

    /**
     * 校验并保存构造参数，不生成新标识或执行持久化操作。
     *
     * @param originalUsername 不修剪的原始用户名
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 用户名格式不合法或属于保留名称
     */
    private Username(String originalUsername) {
        Objects.requireNonNull(originalUsername, "originalUsername");
        if (!FORMAT.matcher(originalUsername).matches()) {
            throw new IllegalArgumentException("Invalid username format");
        }
        // 仅折叠 ASCII 大小写，不依赖系统 Locale，也不修剪输入。
        char[] characters = originalUsername.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            if (characters[i] >= 'A' && characters[i] <= 'Z') {
                characters[i] = (char) (characters[i] + ('a' - 'A'));
            }
        }
        String canonical = new String(characters);
        if (RESERVED.contains(canonical)) {
            throw new IllegalArgumentException("Reserved username");
        }
        this.originalUsername = originalUsername;
        this.canonicalUsername = canonical;
    }

    /**
     * 校验用户名并保留原文，按 ASCII 小写派生身份键；拒绝保留名称。
     *
     * @param originalUsername 不修剪的原始用户名
     * @return 通过校验的领域对象
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 用户名格式不合法或属于保留名称
     */
    public static Username create(String originalUsername) {
        return new Username(originalUsername);
    }

    /**
     * 获取保留输入大小写的原始用户名。
     *
     * @return 保留输入大小写的原始用户名
     */
    public String getOriginalUsername() {
        return originalUsername;
    }

    /**
     * 获取 ASCII 小写用户名身份键。
     *
     * @return ASCII 小写用户名身份键
     */
    public String getCanonicalUsername() {
        return canonicalUsername;
    }

    /**
     * {@inheritDoc} 按规范化用户名比较，原始拼写不参与身份相等判断。
     *
     * @param other 待比较对象，可为 null
     * @return 是否表示相同的值
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof Username username
                        && canonicalUsername.equals(username.canonicalUsername);
    }

    /**
     * {@inheritDoc} 与本类型的值相等规则保持一致。
     *
     * @return 值对应的哈希码
     */
    @Override
    public int hashCode() {
        return canonicalUsername.hashCode();
    }
}
