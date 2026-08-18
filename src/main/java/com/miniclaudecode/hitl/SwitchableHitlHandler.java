package com.miniclaudecode.hitl;

import java.util.Objects;

/**
 * 将 HITL 请求转发给当前 UI 的处理器
 * UI 模式切换时可替换 delegate，无需重建 ToolRegistry
 */
public final class SwitchableHitlHandler implements HitlHandler {

    private volatile HitlHandler delegate;

    public SwitchableHitlHandler(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public HitlHandler getDelegate() {
        return delegate;
    }

    public void setDelegate(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        return delegate.requestApproval(request);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return delegate.isApprovedAllByTool(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return delegate.isApprovedAllByServer(serverName);
    }

    @Override
    public void clearApprovedAll() {
        delegate.clearApprovedAll();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        delegate.clearApprovedAllForServer(serverName);
    }
}
