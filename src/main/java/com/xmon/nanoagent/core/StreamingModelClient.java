package com.xmon.nanoagent.core;

import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.services.blocking.MessageService;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * 用流式 API 调用模型的 {@link ModelClient}
 *
 * <p>底层是 {@code createStreaming} 返回的原始事件流，喂给 {@link MessageAccumulator} 累积出完整消息。
 * 文本增量在原始事件里即时可见，因此能逐字转发；完整消息只在消息结束时取得。
 */
public final class StreamingModelClient implements ModelClient {

    private final MessageService service;
    private volatile StreamResponse<?> currentResponse;

    /**
     * 创建流式模型客户端
     *
     * @param service SDK 的 messages 服务
     */
    public StreamingModelClient(MessageService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * 发送请求并逐事件转发
     *
     * <p>关闭返回的事件流会连带关闭底层的 {@link StreamResponse}，释放网络连接。
     *
     * @param request 消息请求参数
     * @return 事件流，以 {@link ModelEvent.MessageComplete} 收尾
     */
    @Override
    public Stream<ModelEvent> events(MessageCreateParams request) {
        StreamResponse<RawMessageStreamEvent> response = service.createStreaming(request);
        currentResponse = response;
        MessageAccumulator accumulator = MessageAccumulator.create();
        return response.stream()
                .map(event -> {
                    accumulator.accumulate(event);
                    return toModelEvent(event, accumulator);
                })
                .filter(Objects::nonNull)
                .onClose(() -> {
                    currentResponse = null;
                    response.close();
                });
    }

    /**
     * 取消当前正在进行的请求
     *
     * <p>关闭底层 HTTP 连接，使事件流的下一次 {@code next()} 抛出异常。
     * 信号线程安全：仅设置 volatile 引用 + 调用 OkHttp 的 cancel（线程安全）。
     */
    @Override
    public void cancel() {
        StreamResponse<?> r = currentResponse;
        if (r != null) {
            r.close();
        }
    }

    /**
     * 把一条原始流事件转成对外事件
     *
     * @param event 原始流事件
     * @param accumulator 已喂入本条事件的累积器
     * @return 对外事件，本条原始事件不产生对外事件时返回 {@code null}
     */
    private ModelEvent toModelEvent(RawMessageStreamEvent event, MessageAccumulator accumulator) {
        // contentBlockStart：tool_use 块的开始，携带工具名和 ID
        var blockStart = event.contentBlockStart();
        if (blockStart.isPresent()) {
            var start = blockStart.orElseThrow();
            var toolUse = start.contentBlock().toolUse();
            if (toolUse.isPresent()) {
                var tb = toolUse.orElseThrow();
                return new ModelEvent.ToolUseStart((int) start.index(), tb.name(), tb.id());
            }
            // thinking / text 块的 start 不产生事件：内容从 delta 流式到达
            return null;
        }

        // contentBlockDelta：文本 / input_json / thinking / citations / signature 增量
        var blockDelta = event.contentBlockDelta();
        if (blockDelta.isPresent()) {
            var deltaEvent = blockDelta.orElseThrow();
            var delta = deltaEvent.delta();
            int index = (int) deltaEvent.index();

            var text = delta.text();
            if (text.isPresent()) {
                return new ModelEvent.TextDelta(text.get().text());
            }
            var inputJson = delta.inputJson();
            if (inputJson.isPresent()) {
                return new ModelEvent.ToolUseDelta(index, inputJson.get().partialJson());
            }
            var thinking = delta.thinking();
            if (thinking.isPresent()) {
                return new ModelEvent.ThinkingDelta(index, thinking.get().thinking());
            }
            // citations / signature 不对外
            return null;
        }

        // messageStop 之后累积器给出完整消息；其余事件（start、block stop、message delta 等）不对外。
        if (event.messageStop().isPresent()) {
            return new ModelEvent.MessageComplete(accumulator.message());
        }
        return null;
    }
}
