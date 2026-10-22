package io.github.ringotangs.pikdo.server.identity.domain;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

/** 仅接受规范 E.164 格式的手机号值对象。不清理分隔符、不推断国家区号，也不判断号码分配情况或控制权。 */
public final class PhoneNumber {
    /** 完整匹配加号及 2–15 位 ASCII 数字，首位数字非零。 */
    private static final Pattern FORMAT = Pattern.compile("\\+[1-9][0-9]{1,14}");

    /** 已规范化的国际号码，原样保存，不修剪或推断国家区号。 */
    private final String value;

    /**
     * 校验并保存构造参数，不生成新标识或执行持久化操作。
     *
     * @param value 已带国家区号的规范国际号码
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 号码不符合规范 E.164 格式
     */
    private PhoneNumber(String value) {
        Objects.requireNonNull(value, "value");
        // 完整匹配规范格式，不修剪空白或猜测国家区号；格式合法不表示号码已实际分配。
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Phone number requires canonical E.164 syntax");
        }
        this.value = value;
    }

    /**
     * 校验规范国际号码；不清理输入、不推断区号，不验证号码分配或控制权。
     *
     * @param value 已带国家区号的规范国际号码
     * @return 通过校验的领域对象
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 号码不符合规范 E.164 格式
     */
    public static PhoneNumber create(String value) {
        return new PhoneNumber(value);
    }

    /**
     * 获取规范国际号码原文。
     *
     * @return 规范国际号码原文
     */
    public String getValue() {
        return value;
    }

    /**
     * {@inheritDoc} 按完整规范手机号比较。
     *
     * @param other 待比较对象，可为 null
     * @return 是否表示相同的值
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || other instanceof PhoneNumber phone && value.equals(phone.value);
    }

    /**
     * {@inheritDoc} 与本类型的值相等规则保持一致。
     *
     * @return 值对应的哈希码
     */
    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
