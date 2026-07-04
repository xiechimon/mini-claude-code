package com.xmon.nanoagent;

import com.anthropic.core.JsonValue;

import java.io.IOException;
import java.util.Optional;

/**
 * 一条权限规则的判定逻辑
 *
 * <p>判定与原因一起产出：命中时返回原因，未命中时返回空。原因不能做成规则上的静态字段，因为拒绝表
 * 需要在原因里点出命中的是哪一条模式。
 *
 * <p>声明 {@link IOException} 而不用 {@link java.util.function.Predicate}，是因为路径规则要调用
 * {@link Workspace#contains(String)}——把它的 {@code IOException} 在 lambda 里吞掉会掩盖真实的文件系统故障。
 */
@FunctionalInterface
interface RuleCheck {

    /**
     * 判定一次工具调用是否命中本规则
     *
     * @param input 模型给出的工具输入
     * @return 命中时为命中原因，未命中时为空
     * @throws IOException 判定过程访问文件系统失败
     */
    Optional<String> test(JsonValue input) throws IOException;
}
