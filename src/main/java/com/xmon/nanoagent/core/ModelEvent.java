package com.xmon.nanoagent.core;

import com.anthropic.models.messages.Message;

import java.util.Objects;

/**
 * 一次模型响应按到达顺序展开的事件
 *
 * <p>只暴露两个时机，理由见 ADR-0006：文本增量 {@link TextDelta} 来自 SDK 的 {@code contentBlockDelta}，
 * 消息完整 {@link MessageComplete} 来自 {@link com.anthropic.helpers.MessageAccumulator}。两者正好落在
 * 「SDK 真实暴露、且不重复造轮子」的边界上。
 *
 * <p>{@link MessageComplete} 是终态：它是事件流的最后一个事件，出现之后不再有任何事件。
 */
public sealed interface ModelEvent {

    /**
     * 文本增量
     *
     * @param text 一段文本，非流式实现下是整段文本
     */
    record TextDelta(String text) implements ModelEvent {

        /**
         * 校验文本增量
         *
         * @param text 文本内容
         */
        public TextDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * 消息完整
     *
     * @param message 完整模型消息，{@code stopReason} 与 content block 都从这里取
     */
    record MessageComplete(Message message) implements ModelEvent {

        /**
         * 校验消息完整事件
         *
         * @param message 完整模型消息
         */
        public MessageComplete {
            Objects.requireNonNull(message, "message");
        }
    }
}
