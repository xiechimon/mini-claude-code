package com.xmon.nanoagent.core;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.services.blocking.MessageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 用非流式 API 调用模型的 {@link ModelClient}
 *
 * <p>底层是同步的 {@code create}，拿到整条消息后把文本块展开成 {@link ModelEvent.TextDelta} 再以
 * {@link ModelEvent.MessageComplete} 收尾。它和 {@link StreamingModelClient} 产出同一种事件流，
 * 差别只在于文本是整段到达而非逐字——这正是可插拔的落点：换实现只改显示时机，不改循环语义。
 */
public final class BlockingModelClient implements ModelClient {

    private final MessageService service;

    /**
     * 创建非流式模型客户端
     *
     * @param service SDK 的 messages 服务
     */
    public BlockingModelClient(MessageService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * 发送请求，把完整消息展开成事件流
     *
     * @param request 消息请求参数
     * @return 事件流，文本块在前、{@link ModelEvent.MessageComplete} 在后
     */
    @Override
    public Stream<ModelEvent> events(MessageCreateParams request) {
        Message message = service.create(request);
        List<ModelEvent> events = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.text().map(TextBlock::text).ifPresent(text -> events.add(new ModelEvent.TextDelta(text)));
        }
        events.add(new ModelEvent.MessageComplete(message));
        return events.stream();
    }
}
