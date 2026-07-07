package com.xmon.nanoagent.core;

import com.anthropic.models.messages.MessageCreateParams;

import java.util.stream.Stream;

/**
 * 调用大模型
 *
 * <p>接缝两侧是同一个接口：流式与非流式只是两个实现，Agent Loop 不感知差异。消费方用 try-with-resources
 * 关闭事件流，关闭会连带释放底层的网络资源。
 */
@FunctionalInterface
public interface ModelClient {

    /**
     * 发送消息请求并返回响应事件流
     *
     * @param request 消息请求参数
     * @return 按到达顺序展开的事件流，以 {@link ModelEvent.MessageComplete} 收尾
     */
    Stream<ModelEvent> events(MessageCreateParams request);
}
