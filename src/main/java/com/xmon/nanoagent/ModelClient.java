package com.xmon.nanoagent;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

@FunctionalInterface
interface ModelClient {

    Message create(MessageCreateParams request);
}
