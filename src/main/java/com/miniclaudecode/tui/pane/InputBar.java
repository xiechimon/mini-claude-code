package com.miniclaudecode.tui.pane;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.LinearLayout.Alignment;
import com.googlecode.lanterna.gui2.LinearLayout.GrowPolicy;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.miniclaudecode.llm.LlmClient;

import java.util.function.Consumer;

/**
 * Lanterna TUI 的底部输入栏，Enter 提交，Esc 清空
 */
public class InputBar extends Panel {

    private final LlmClient llmClient;
    private final Consumer<String> onMessage;
    private final TextBox inputBox;

    public InputBar(com.miniclaudecode.config.MiniClaudeCodeConfig config, LlmClient llmClient, Consumer<String> onMessage) {
        super();
        this.llmClient = llmClient;
        this.onMessage = onMessage;

        setLayoutManager(new LinearLayout(Direction.HORIZONTAL));

        this.inputBox = new TextBox() {
            @Override
            public Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
                if (keyStroke.getKeyType() == KeyType.Enter) {
                    submit();
                    return Interactable.Result.HANDLED;
                } else if (keyStroke.getKeyType() == KeyType.Escape) {
                    clear();
                    return Interactable.Result.HANDLED;
                }
                return super.handleKeyStroke(keyStroke);
            }
        };
        inputBox.setPreferredSize(new TerminalSize(80, 3));

        addComponent(inputBox.setLayoutData(
                LinearLayout.createLayoutData(Alignment.Fill, GrowPolicy.CanGrow)));
    }

    private void submit() {
        String text = inputBox.getText().trim();
        if (!text.isEmpty()) {
            onMessage.accept(text);
            inputBox.setText("");
        }
    }

    public String getText() {
        return inputBox.getText();
    }

    public void clear() {
        inputBox.setText("");
    }
}
