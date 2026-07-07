package com.xmon.nanoagent.core;

import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
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
        MessageAccumulator accumulator = MessageAccumulator.create();
        return response.stream()
                .map(event -> {
                    accumulator.accumulate(event);
                    return toModelEvent(event, accumulator);
                })
                .filter(Objects::nonNull)
                .onClose(response::close);
    }

    /**
     * 把一条原始流事件转成对外事件
     *
     * @param event 原始流事件
     * @param accumulator 已喂入本条事件的累积器
     * @return 对外事件，本条原始事件不产生对外事件时返回 {@code null}
     */
    private ModelEvent toModelEvent(RawMessageStreamEvent event, MessageAccumulator accumulator) {
        var text = event.contentBlockDelta()
                .map(RawContentBlockDeltaEvent::delta)
                .flatMap(RawContentBlockDelta::text);
        if (text.isPresent()) {
            return new ModelEvent.TextDelta(text.get().text());
        }
        // messageStop 之后累积器给出完整消息；其余事件（start、block stop、message delta 等）不对外。
        if (event.messageStop().isPresent()) {
            return new ModelEvent.MessageComplete(accumulator.message());
        }
        return null;
    }
}
