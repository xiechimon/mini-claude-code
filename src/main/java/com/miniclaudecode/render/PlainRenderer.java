package com.miniclaudecode.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaudecode.hitl.ApprovalPolicy;
import com.miniclaudecode.hitl.ApprovalRequest;
import com.miniclaudecode.hitl.ApprovalResult;
import com.miniclaudecode.llm.LlmClient;
import com.miniclaudecode.util.AnsiStyle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Plain 渲染器：纯 println 模式，无折叠和状态栏
 *
 * <p>同时充当 inline / lanterna 的回退基线
 */
public final class PlainRenderer implements Renderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PrintStream out;
    private final BufferedReader in;

    public PlainRenderer() {
        this(System.out, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    PlainRenderer(PrintStream out, BufferedReader in) {
        this.out = out;
        this.in = in;
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
    }

    @Override
    public PrintStream stream() {
        return out;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        ToolCallLabels.printExpanded(out, toolCalls);
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        out.println();
        out.println(AnsiStyle.heading("📝 " + (filePath == null ? "(unnamed)" : filePath)));
        if (before == null && after != null) {
            out.println(AnsiStyle.subtle("  (新建文件，" + after.length() + " 字符)"));
            return;
        }
        if (before != null && after == null) {
            out.println(AnsiStyle.subtle("  (删除文件)"));
            return;
        }
        // plain 模式只提供稳定的长度摘要，完整 diff 交给高级渲染器
        int beforeLen = before == null ? 0 : before.length();
        int afterLen = after == null ? 0 : after.length();
        out.println(AnsiStyle.subtle("  " + beforeLen + " → " + afterLen + " 字符"));
    }

    @Override
    public void updateStatus(StatusInfo status) {
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        boolean sensitivePerCall = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        out.println();
        out.println("────────── ⚠️  HITL 审批请求 ──────────");
        if (sensitivePerCall) {
            out.println("⚠️  " + request.sensitiveNotice());
        }
        out.println(request.toDisplayText());

        for (int attempt = 0; attempt < 5; attempt++) {
            out.println();
            if (sensitivePerCall) {
                out.println("请选择操作：[y/Enter] 批准本次  [n] 拒绝  [s] 跳过  [m] 修改参数");
            } else {
                out.println("请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            }
            out.print("> ");
            out.flush();

            String input;
            try {
                input = in.readLine();
            } catch (IOException e) {
                out.println("  [HITL] 读取用户输入失败，保守处理为拒绝");
                return ApprovalResult.reject("读取输入失败: " + e.getMessage());
            }
            if (input == null) {
                out.println("  [HITL] 输入流已关闭，保守处理为拒绝");
                return ApprovalResult.reject("输入流已关闭");
            }

            String normalized = input.trim().toLowerCase();
            if (normalized.isEmpty() || normalized.equals("y")) {
                out.println("  已批准");
                return ApprovalResult.approve();
            }
            switch (normalized) {
                case "a" -> {
                    if (sensitivePerCall) {
                        out.println("  敏感页面操作不支持全部放行，请选择 y/n/s/m");
                        continue;
                    }
                    return promptApproveAllScope(request);
                }
                case "n" -> {
                    out.print("  拒绝原因（可直接回车跳过）：");
                    out.flush();
                    String reason;
                    try {
                        reason = in.readLine();
                    } catch (IOException e) {
                        reason = "";
                    }
                    return ApprovalResult.reject(reason == null ? "" : reason.trim());
                }
                case "s" -> {
                    out.println("  已跳过本次操作");
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    ApprovalResult modified = promptModifiedArguments(request);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> out.println("  ❓ 无法识别的选项：'" + input + "'，请输入 y/a/n/s/m 之一（Enter 等价于 y）");
            }
        }
        out.println("  [HITL] 连续多次无效输入，保守处理为拒绝");
        return ApprovalResult.reject("连续多次无效输入");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return -1;
        }
        out.println();
        out.println(AnsiStyle.heading("📋 " + (title == null ? "请选择" : title)));
        for (int i = 0; i < items.size(); i++) {
            out.printf("  [%d] %s%n", i + 1, items.get(i));
        }
        out.print("> ");
        out.flush();
        try {
            String line = in.readLine();
            if (line == null || line.isBlank()) {
                return -1;
            }
            int idx = Integer.parseInt(line.trim()) - 1;
            return (idx >= 0 && idx < items.size()) ? idx : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    private ApprovalResult promptApproveAllScope(ApprovalRequest request) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        if (mcpServer == null || mcpServer.isBlank()) {
            out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
            return ApprovalResult.approveAll();
        }

        out.println("  全部放行范围：");
        out.println("  [tool / Enter] 仅本工具 " + request.toolName());
        out.println("  [server]       整个 MCP server " + mcpServer + "（连续浏览器操作推荐）");
        out.print("> ");
        out.flush();
        String scope;
        try {
            scope = in.readLine();
        } catch (IOException e) {
            out.println("  读取范围失败，默认按工具维度放行");
            scope = "";
        }
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        if ("server".equals(normalized) || "s".equals(normalized)) {
            out.println("  已批准，后续 MCP server " + mcpServer + " 的工具调用将自动通过");
            return ApprovalResult.approveAllByServer();
        }
        out.println("  已批准，后续 " + request.toolName() + " 操作将自动通过");
        return ApprovalResult.approveAll();
    }

    private ApprovalResult promptModifiedArguments(ApprovalRequest request) {
        out.println("  当前参数：" + request.arguments());
        out.print("  请输入修改后的参数（JSON 格式，空行则使用原始参数）：");
        out.flush();

        String modified;
        try {
            modified = in.readLine();
        } catch (IOException e) {
            out.println("  读取失败，回到主菜单");
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println("  输入为空，改为批准原始参数");
            return ApprovalResult.approve();
        }

        String trimmed = modified.trim();
        try {
            JSON.readTree(trimmed);
        } catch (Exception e) {
            out.println("  ❌ 修改后的参数不是合法 JSON：" + e.getMessage());
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }
}
