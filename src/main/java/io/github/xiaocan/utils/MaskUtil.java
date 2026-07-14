package io.github.xiaocan.utils;

/**
 * 敏感信息脱敏工具：用于日志输出，避免明文泄露 token、spt、JWT、密钥等。
 * 策略：保留首尾少量字符，中间用 * 替代；过短的值整体打码。
 */
public final class MaskUtil {

    private static final String MASK = "***";

    private MaskUtil() {}

    /**
     * 脱敏：保留首 keepHead 与尾 keepTail 个字符，中间以 *** 代替。
     * null/空返回 ""；长度不足以同时保留首尾时整体返回 ***。
     */
    public static String mask(String value, int keepHead, int keepTail) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= keepHead + keepTail) {
            return MASK;
        }
        return value.substring(0, keepHead) + MASK + value.substring(value.length() - keepTail);
    }

    /** 常用脱敏：保留前 6 后 4。 */
    public static String mask(String value) {
        return mask(value, 6, 4);
    }
}
