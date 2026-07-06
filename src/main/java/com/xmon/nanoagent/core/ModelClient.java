package com.xmon.nanoagent.core;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

/**
 * 调用大模型
 */
@FunctionalInterface
public interface ModelClient {

    /**
     * 发送消息请求
     *
     * @param request 消息请求参数
     * @return 模型响应消息
     */
    Message create(MessageCreateParams request);
}
