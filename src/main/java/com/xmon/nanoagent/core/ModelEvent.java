package com.xmon.nanoagent.core;

import com.anthropic.models.messages.Message;

import java.util.Objects;

/**
 * 一次模型响应按到达顺序展开的事件
 *
 * <p>原有两事件架构见 ADR-0006。本次新增三个事件是 ADR-0006「什么会推翻这个决定」节预言的
 * 演进路径：SDK 2.53.0 直接暴露了 content block 粒度的中间结果，生产成本已可忽略。
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
     * 工具调用开始
     *
     * @param index SDK 分配的 content block 序号，用于区分同一轮中多个工具调用块
     * @param name  工具名
     * @param id    工具调用 ID，用于回填 Tool Result
     */
    record ToolUseStart(int index, String name, String id) implements ModelEvent {

        /**
         * 校验工具调用开始事件
         */
        public ToolUseStart {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
        }
    }

    /**
     * 工具调用参数增量
     *
     * <p>{@code partialJson} 是 SDK 的 {@code input_json_delta} 逐段拼接的原始 JSON 碎片，
     * 消费端直接追加显示，不解析 JSON。
     *
     * @param index       SDK 分配的 content block 序号，与 {@link ToolUseStart#index} 对齐
     * @param partialJson 一段 JSON 碎片
     */
    record ToolUseDelta(int index, String partialJson) implements ModelEvent {

        /**
         * 校验工具调用参数增量事件
         */
        public ToolUseDelta {
            Objects.requireNonNull(partialJson, "partialJson");
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
        }
    }

    /**
     * 思考增量
     *
     * <p>来自 SDK 的 {@code thinking_delta}，消费端用暗色样式显示以区别于正文。
     *
     * @param index    SDK 分配的 content block 序号，用于区分同一轮中多个 thinking 块
     * @param thinking 一段思考文本
     */
    record ThinkingDelta(int index, String thinking) implements ModelEvent {

        /**
         * 校验思考增量事件
         */
        public ThinkingDelta {
            Objects.requireNonNull(thinking, "thinking");
            if (index < 0) {
                throw new IllegalArgumentException("index must be non-negative");
            }
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
