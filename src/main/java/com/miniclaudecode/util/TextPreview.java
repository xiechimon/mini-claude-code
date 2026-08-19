package com.miniclaudecode.util;

/**
 * 日志预览文本：统一换行后按上限截断
 *
 * <p>只服务于日志和诊断输出，不用于发送给模型的正文；截断后的字符串不保证是合法 JSON 或完整语句
 */
public final class TextPreview {

    private TextPreview() {
    }

    /**
     * @param maxLength 截断上限，超出部分替换为 {@code ...}
     * @return content 为 null 时返回空串，避免调用方在日志参数里再判空
     */
    public static String of(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
