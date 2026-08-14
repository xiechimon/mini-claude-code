package com.miniclaudecode.wechat;

import java.io.IOException;

/**
 * 向当前微信会话发送一段文本消息
 */
@FunctionalInterface
public interface WechatMessageSender {
    void send(String text) throws IOException;
}
