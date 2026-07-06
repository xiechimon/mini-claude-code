/**
 * Agent Loop 与模型调用接缝
 *
 * <p>对外承诺两样东西：{@link com.xmon.nanoagent.core.AgentLoop} 驱动「请求模型 → 执行工具 → 回填结果」
 * 的控制循环，{@link com.xmon.nanoagent.core.ModelClient} 是提交对话历史取回模型消息的可替换接缝，
 * 测试用假实现注入它。
 *
 * <p>本包不认识任何具体工具，也不含权限策略——它只按工具名分发，并在执行前询问权限闸门。
 */
package com.xmon.nanoagent.core;
