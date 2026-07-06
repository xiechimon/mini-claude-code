package com.xmon.nanoagent.tool;

import com.anthropic.core.JsonValue;

/**
 * 按工具名分发到的工具实现
 *
 * <p>实现必须先解码输入再进入自身的错误边界：输入形状错误要直接暴露，执行失败才转成 {@code Error:} 文本。
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * 执行一次工具调用
     *
     * @param input 模型给出的工具输入
     * @return 回填给模型的 Tool Result 文本，执行失败时以 {@code Error:} 开头
     * @throws InterruptedException 执行被中断
     */
    String execute(JsonValue input) throws InterruptedException;
}
