package com.fntv.app.util;

import java.io.UnsupportedEncodingException;
import java.util.Locale;

/**
 * 轻量级汉字转拼音首字母工具类（纯算法，无第三方库依赖，兼容 Android 4.4+）
 */
public class PinyinUtils {

    // GB2312 拼音首字母编码分界表
    private static final int[] SEC_POS_VALUE_LIST = {
            1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787,
            3106, 3212, 3472, 3635, 3722, 3730, 3858, 4027, 4086,
            4390, 4558, 4684, 4925, 5249, 5600
    };

    private static final char[] FIRST_LETTER = {
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'j',
            'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's',
            't', 'w', 'x', 'y', 'z'
    };

    /**
     * 获取单个字符的首字母（小写）
     */
    public static char getInitial(char ch) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
            return Character.toLowerCase(ch);
        }
        if (ch >= '0' && ch <= '9') {
            return ch;
        }
        if (ch < 128) {
            return ch;
        }

        try {
            byte[] bytes = String.valueOf(ch).getBytes("GB2312");
            if (bytes.length < 2) return ch;
            int secPosValue = (bytes[0] + 256) * 100 + (bytes[1] + 256);
            for (int i = 0; i < 23; i++) {
                if (secPosValue >= SEC_POS_VALUE_LIST[i] && secPosValue < SEC_POS_VALUE_LIST[i + 1]) {
                    return FIRST_LETTER[i];
                }
            }
        } catch (UnsupportedEncodingException ignored) {
        }
        return ch;
    }

    /**
     * 获取字符串的拼音首字母串（全小写）
     * 例如："周杰伦" -> "zjl", "头文字D" -> "twsd"
     */
    public static String getInitials(String str) {
        if (str == null || str.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isWhitespace(c)) continue;
            sb.append(getInitial(c));
        }
        return sb.toString().toLowerCase(Locale.US);
    }

    /**
     * 模糊匹配：文本是否包含关键词（支持普通包含匹配 与 拼音首字母连续包含匹配）
     */
    public static boolean match(String target, String query) {
        if (target == null) return false;
        if (query == null || query.trim().isEmpty()) return true;

        String q = query.trim().toLowerCase(Locale.US);
        String t = target.toLowerCase(Locale.US);

        // 1. 直接文本包含
        if (t.contains(q)) return true;

        // 2. 拼音首字母匹配
        String initials = getInitials(target);
        if (initials.contains(q)) return true;

        // 3. 去除非字母数字后的匹配
        String cleanQ = q.replaceAll("[^a-zA-Z0-9\u4e00-\u9fa5]", "");
        if (!cleanQ.isEmpty()) {
            if (t.replaceAll("[^a-zA-Z0-9\u4e00-\u9fa5]", "").contains(cleanQ)) return true;
            if (initials.contains(cleanQ)) return true;
        }

        return false;
    }
}
