package net.micode.notes.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 标签名的规范化与展示拼接（纯 Java，无 Android 依赖）。
 *
 * <p>用户输入是自由文本，落库前必须统一：去掉首尾空白、把连续空白压成一个空格、
 * 空串一律视为无效、超长截断。多个标签允许用中英文逗号/分号/换行分隔，
 * 解析时去重且保持输入顺序。</p>
 */
public final class TagNames {

    /** 单个标签名的最大长度，超出的部分直接截断。 */
    public static final int MAX_LENGTH = 20;

    /** 展示多个标签时的分隔符。 */
    public static final String SEPARATOR = " · ";

    /** 分隔符：中文逗号、英文逗号、中文分号、英文分号、换行。 */
    private static final String SPLIT_REGEX = "[,，;；\\r\\n]+";

    private TagNames() {
    }

    /**
     * 规范化单个标签名。
     *
     * @param raw 用户原始输入，可为 null
     * @return 规范化后的名字；输入为空或全是空白时返回 null，调用方据此拒绝写入
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        return trimmed;
    }

    /**
     * 解析一次输入里的多个标签：按分隔符切开，逐个规范化，去重并保持顺序。
     *
     * @param raw 例如 "工作，读书, 读书"
     * @return 规范化后的标签名列表；没有有效项时返回空列表
     */
    public static List<String> parse(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String piece : raw.split(SPLIT_REGEX)) {
            String name = normalize(piece);
            if (name != null) {
                seen.add(name);
            }
        }
        result.addAll(seen);
        return result;
    }

    /**
     * 把多个标签名拼成一行展示文案。
     *
     * @param names 标签名列表，可为 null
     * @return 空列表返回空串，调用方据此隐藏标签行
     */
    public static String join(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            if (builder.length() > 0) {
                builder.append(SEPARATOR);
            }
            builder.append(name);
        }
        return builder.toString();
    }
}
