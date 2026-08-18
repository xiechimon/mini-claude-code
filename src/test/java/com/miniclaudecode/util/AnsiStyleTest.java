package com.miniclaudecode.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AnsiStyleTest {

    private static String stripAnsi(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    @Test
    void userMessageBlockDoesNotForceWrapWhenContentExactlyFits() {
        String line = AnsiStyle.userMessageBlock("abc", 8);

        assertFalse(line.contains("\n"), line);
        assertEquals("> abc", stripAnsi(line), line);
        assertFalse(line.contains("\u001B[48;"), line);
        assertFalse(stripAnsi(line).startsWith(" "), line);
    }

    @Test
    void userMessageBlockKeepsExplicitMultilineInputAsRows() {
        String block = AnsiStyle.userMessageBlock("第一行\n第二行", 40);

        assertEquals(1, block.chars().filter(ch -> ch == '\n').count(), block);
        assertTrue(block.contains("第一行"), block);
        assertTrue(block.contains("第二行"), block);
    }
}
