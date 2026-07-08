package com.xmon.nanoagent.core;

import com.anthropic.models.messages.MessageCreateParams;

import java.util.stream.Stream;

/**
 * 调用大模型
 *
 * <p>接缝两侧是同一个接口：流式与非流式只是两个实现，Agent Loop 不感知差异。消费方用 try-with-resources
 * 关闭事件流，关闭会连带释放底层的网络资源。
 */
public interface ModelClient {

    /**
     * 发送消息请求并返回响应事件流
     *
     * @param request 消息请求参数
     * @return 按到达顺序展开的事件流，以 {@link ModelEvent.MessageComplete} 收尾
     */
    Stream<ModelEvent> events(MessageCreateParams request);

    /**
     * 取消当前正在进行的请求
     *
     * <p>默认空实现。流式实现重写为关闭底层 HTTP 连接，使 {@code events()} 返回的流的下一次
     * {@code next()} 抛出异常，从而中断消费循环。
     */
    default void cancel() {
    }
}
