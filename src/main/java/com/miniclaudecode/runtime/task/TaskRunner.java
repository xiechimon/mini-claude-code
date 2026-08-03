package com.miniclaudecode.runtime.task;

/**
 * Durable task 的执行边界，返回最终文本结果
 */
@FunctionalInterface
public interface TaskRunner {
    String run(String prompt) throws Exception;
}
