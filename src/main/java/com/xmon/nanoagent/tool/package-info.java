/**
 * 模型可调用的工具
 *
 * <p>对外承诺两样东西：{@link com.xmon.nanoagent.tool.ToolHandler} 是所有工具实现的统一形状，
 * {@link com.xmon.nanoagent.tool.ToolRegistry} 同时持有发给模型的声明表与运行时的实现表。
 * 两张表互不校验，理由见 ADR-0003。
 *
 * <p>工具不做权限判定：危险与越界一律由 {@code permission} 包在执行之前裁决。工具自身只负责
 * 「做这件事」以及把失败转成 {@code Error:} 开头的文本结果。
 */
package com.xmon.nanoagent.tool;
