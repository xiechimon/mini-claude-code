package com.miniclaudecode.wechat;

import com.miniclaudecode.policy.AuditLog;
import com.miniclaudecode.tool.ToolOutput;
import com.miniclaudecode.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;

/**
 * 在微信非交互通道中先执行 default-deny 策略，再委托 ToolRegistry
 * 策略拒绝会写入审计日志，且不能被远程用户批准绕过
 */
public class WechatToolRegistry extends ToolRegistry {
    private final WechatPolicyDecider decider;

    public WechatToolRegistry(WechatPolicyDecider decider) {
        this.decider = decider;
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        long start = System.nanoTime();
        WechatPolicyDecision decision = decider == null
                ? WechatPolicyDecision.allow()
                : decider.decide(name, argumentsJson);
        if (!decision.allowed()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(
                    name,
                    argumentsJson,
                    decision.reason(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            return ToolOutput.text("微信通道策略拒绝: " + decision.reason());
        }
        return super.doExecuteTool(name, argumentsJson);
    }
}
