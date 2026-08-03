package com.miniclaudecode.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可跨线程观察的协作式取消信号
 */
public class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
}
