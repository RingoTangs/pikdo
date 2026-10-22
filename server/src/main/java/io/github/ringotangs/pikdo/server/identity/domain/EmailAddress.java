package io.github.ringotangs.pikdo.server.identity.domain;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** 保留原始拼写的 ASCII 邮箱值对象，以规范化键比较身份。相等不证明邮箱控制权，也不授权替换已验证的投递地址。 */
public final class EmailAddress {
    /** 完整邮箱地址最大长度，包含 {@code @} 和域名。 */
    private static final int MAX_ADDRESS_LENGTH = 254;

    /** 分隔符 {@code @} 前本地部分的最大长度。 */
    private static final int MAX_LOCAL_LENGTH = 64;

    /** 域名中每个点分标签的最大长度。 */
    private static final int MAX_DOMAIN_LABEL_LENGTH = 63;

    /** 本地部分允许的特殊字符；点号的位置与连续性单独校验。 */
    private static final String LOCAL_SPECIAL_CHARACTERS = "!#$%&'*+-/=?^_`{|}~";

    /** 通过校验的原始邮箱，保留输入大小写。 */
    private final String originalAddress;

    /** ASCII 小写规范化身份键，用于相等比较和哈希，不证明邮箱控制权。 */
    private final String canonicalAddress;

    /**
     * 校验并保存构造参数，不生成新标识或执行持久化操作。
     *
     * @param originalAddress 保留输入大小写的原始邮箱地址
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 邮箱不符合当前支持的格式或长度规则
     */
    private EmailAddress(String originalAddress) {
        Objects.requireNonNull(originalAddress, "originalAddress");
        validate(originalAddress);
        this.originalAddress = originalAddress;
        this.canonicalAddress = foldAsciiCase(originalAddress);
    }

    /**
     * 校验 ASCII 邮箱并保留原文，按 ASCII 小写派生身份键，不合并点号或标签。
     *
     * @param originalAddress 保留输入大小写的原始邮箱地址
     * @return 通过校验的领域对象
     * @throws NullPointerException 必要参数为 null
     * @throws IllegalArgumentException 邮箱不符合当前支持的格式或长度规则
     */
    public static EmailAddress create(String originalAddress) {
        return new EmailAddress(originalAddress);
    }

    /**
     * 获取保留输入大小写的原始邮箱。
     *
     * @return 保留输入大小写的原始邮箱
     */
    public String getOriginalAddress() {
        return originalAddress;
    }

    /**
     * 获取 ASCII 小写邮箱身份键。
     *
     * @return ASCII 小写邮箱身份键
     */
    public String getCanonicalAddress() {
        return canonicalAddress;
    }

    /**
     * 校验完整邮箱的长度、可打印 ASCII、分隔符、本地部分和域名。
     *
     * @param address 完整邮箱地址
     * @throws IllegalArgumentException 邮箱内容不符合当前校验规则
     */
    private static void validate(String address) {
        if (address.isEmpty() || address.length() > MAX_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("Invalid email address length");
        }
        for (int i = 0; i < address.length(); i++) {
            char character = address.charAt(i);
            if (character < '!' || character > '~') {
                throw new IllegalArgumentException(
                        "Email address requires printable ASCII without spaces");
            }
        }
        int separator = address.indexOf('@');
        if (separator <= 0
                || separator != address.lastIndexOf('@')
                || separator == address.length() - 1) {
            throw new IllegalArgumentException(
                    "Email address requires one separator and nonempty parts");
        }
        validateLocalPart(address.substring(0, separator));
        validateDomain(address.substring(separator + 1));
    }

    /**
     * 校验本地部分长度、允许字符及点号位置。
     *
     * @param localPart 邮箱本地部分
     * @throws IllegalArgumentException 邮箱内容不符合当前校验规则
     */
    private static void validateLocalPart(String localPart) {
        if (localPart.length() > MAX_LOCAL_LENGTH) {
            throw new IllegalArgumentException("Invalid email local part length");
        }
        boolean previousDot = true;
        for (int i = 0; i < localPart.length(); i++) {
            char character = localPart.charAt(i);
            if (character == '.') {
                if (previousDot) {
                    throw new IllegalArgumentException("Invalid email local part dots");
                }
                previousDot = true;
            } else {
                if (isNotAsciiLetterOrDigit(character)
                        && LOCAL_SPECIAL_CHARACTERS.indexOf(character) < 0) {
                    throw new IllegalArgumentException("Invalid email local part character");
                }
                previousDot = false;
            }
        }
        if (previousDot) {
            throw new IllegalArgumentException("Invalid email local part dots");
        }
    }

    /**
     * 校验域名标签格式，拒绝 IPv4 地址，不发起 DNS 或网络查询。
     *
     * @param domain 邮箱域名
     * @throws IllegalArgumentException 邮箱内容不符合当前校验规则
     */
    private static void validateDomain(String domain) {
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            throw new IllegalArgumentException("Email domain requires at least two labels");
        }
        for (String label : labels) {
            if (label.isEmpty()
                    || label.length() > MAX_DOMAIN_LABEL_LENGTH
                    || isNotAsciiLetterOrDigit(label.charAt(0))
                    || isNotAsciiLetterOrDigit(label.charAt(label.length() - 1))) {
                throw new IllegalArgumentException("Invalid email domain label");
            }
            for (int i = 0; i < label.length(); i++) {
                char character = label.charAt(i);
                if (isNotAsciiLetterOrDigit(character) && character != '-') {
                    throw new IllegalArgumentException("Invalid email domain character");
                }
            }
        }
        if (isIpv4Address(labels)) {
            throw new IllegalArgumentException("IP addresses are not supported as email domains");
        }
    }

    /**
     * 按十进制分段判断 IPv4 地址，包括前导零；不解析主机名。
     *
     * @param labels 按点号拆分的域名标签
     * @return 是否由四个 0–255 的十进制标签组成
     */
    private static boolean isIpv4Address(String[] labels) {
        if (labels.length != 4) {
            return false;
        }
        // Check decimal segments locally, including leading zeros; never resolve a host name.
        for (String label : labels) {
            int value = 0;
            for (int i = 0; i < label.length(); i++) {
                char character = label.charAt(i);
                if (character < '0' || character > '9') {
                    return false;
                }
                value = value * 10 + character - '0';
                if (value > 255) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 判断字符是否不属于 ASCII 英文字母或数字。
     *
     * @param character 待判断的字符
     * @return 非 ASCII 英文字母或数字时为 true
     */
    private static boolean isNotAsciiLetterOrDigit(char character) {
        return !(character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9');
    }

    /**
     * 仅将 ASCII 大写字母转换为小写，保持点号和标签，不依赖默认 Locale。
     *
     * @param address 完整邮箱地址
     * @return ASCII 小写身份键
     */
    private static String foldAsciiCase(String address) {
        // 只折叠 ASCII 大小写，避免默认 Locale 改变身份键；点号和加号标签原样保留。
        char[] characters = address.toCharArray();
        for (int i = 0; i < characters.length; i++) {
            if (characters[i] >= 'A' && characters[i] <= 'Z') {
                characters[i] = (char) (characters[i] + ('a' - 'A'));
            }
        }
        return new String(characters);
    }

    /**
     * {@inheritDoc} 按规范化邮箱身份键比较，原始拼写不参与身份相等判断。
     *
     * @param other 待比较对象，可为 null
     * @return 是否表示相同的值
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof EmailAddress email
                        && canonicalAddress.equals(email.canonicalAddress);
    }

    /**
     * {@inheritDoc} 与本类型的值相等规则保持一致。
     *
     * @return 值对应的哈希码
     */
    @Override
    public int hashCode() {
        return canonicalAddress.hashCode();
    }
}
