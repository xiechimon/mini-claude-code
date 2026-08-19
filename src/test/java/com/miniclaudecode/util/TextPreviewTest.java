package com.miniclaudecode.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextPreviewTest {

    @Test
    void nullContentBecomesEmptyString() {
        assertEquals("", TextPreview.of(null, 10));
    }

    @Test
    void lineEndingsAreNormalizedToLf() {
        assertEquals("a\nb\nc", TextPreview.of("a\r\nb\rc", 10));
    }

    @Test
    void contentWithinLimitIsReturnedUnchanged() {
        assertEquals("abcde", TextPreview.of("abcde", 5));
    }

    @Test
    void longerContentIsTruncatedWithEllipsis() {
        assertEquals("abcde...", TextPreview.of("abcdefgh", 5));
    }
}
