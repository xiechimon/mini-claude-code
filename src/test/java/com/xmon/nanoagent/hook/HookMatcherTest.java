package com.xmon.nanoagent.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 matcher 的三档求值
 *
 * <p>「按模式串含有哪些字符选档」是契约里最容易写错的一条：同一个模式串落进精确档还是正则档，
 * 决定了它到底匹配什么。契约文档专门列了两处反直觉的后果，本类各钉一条。
 */
final class HookMatcherTest {

    @Test
    void omittedEmptyAndStarAllMatchEverything() {
        assertEquals(HookMatcher.Tier.MATCH_ALL, matcher(null).tier());
        assertEquals(HookMatcher.Tier.MATCH_ALL, matcher("").tier());
        assertEquals(HookMatcher.Tier.MATCH_ALL, matcher("*").tier());
        assertTrue(matcher(null).matches(HookEvent.PRE_TOOL_USE, "bash"));
        assertTrue(matcher("*").matches(HookEvent.PRE_TOOL_USE, "anything"));
    }

    @Test
    void plainNameIsAnExactStringNotASubstring() {
        HookMatcher bash = matcher("bash");
        assertEquals(HookMatcher.Tier.EXACT, bash.tier());
        assertTrue(bash.matches(HookEvent.PRE_TOOL_USE, "bash"));
        assertFalse(bash.matches(HookEvent.PRE_TOOL_USE, "bash_extra"));
        assertFalse(bash.matches(HookEvent.PRE_TOOL_USE, "run_bash"));
    }

    @Test
    void pipeAndCommaBothSeparateExactValuesAndSurroundingWhitespaceIsTolerated() {
        for (String pattern : List.of("edit_file|write_file", "edit_file, write_file", "edit_file , write_file")) {
            HookMatcher both = matcher(pattern);
            assertEquals(HookMatcher.Tier.EXACT, both.tier(), pattern);
            assertTrue(both.matches(HookEvent.PRE_TOOL_USE, "edit_file"), pattern);
            assertTrue(both.matches(HookEvent.PRE_TOOL_USE, "write_file"), pattern);
            assertFalse(both.matches(HookEvent.PRE_TOOL_USE, "read_file"), pattern);
        }
    }

    @Test
    void hyphensAndSpacesStayInTheExactTier() {
        // 连字符在契约的精确档字符集里：code-reviewer 不该退化成非锚定正则，
        // 否则它会连 senior-code-reviewer 一起匹配上。
        HookMatcher exact = matcher("code-reviewer");
        assertEquals(HookMatcher.Tier.EXACT, exact.tier());
        assertTrue(exact.matches(HookEvent.SUBAGENT_START, "code-reviewer"));
        assertFalse(exact.matches(HookEvent.SUBAGENT_START, "senior-code-reviewer"));
    }

    @Test
    void anyOtherCharacterPushesThePatternIntoTheRegexTier() {
        assertEquals(HookMatcher.Tier.REGEX, matcher("^Notebook").tier());
        assertEquals(HookMatcher.Tier.REGEX, matcher("mcp__memory__.*").tier());
        assertEquals(HookMatcher.Tier.REGEX, matcher("edit.*").tier());
    }

    @Test
    void regexIsUnanchoredSoAPrefixPatternAlsoMatchesLongerNames() {
        // 契约明文点出的坑：Edit.* 会同时匹配 Edit 和 NotebookEdit。
        HookMatcher unanchored = matcher("edit.*");
        assertTrue(unanchored.matches(HookEvent.PRE_TOOL_USE, "edit_file"));
        assertTrue(unanchored.matches(HookEvent.PRE_TOOL_USE, "notebook_edit_file"));

        HookMatcher anchored = matcher("^edit.*$");
        assertTrue(anchored.matches(HookEvent.PRE_TOOL_USE, "edit_file"));
        assertFalse(anchored.matches(HookEvent.PRE_TOOL_USE, "notebook_edit_file"));
    }

    @Test
    void anMcpServerPrefixWithoutTheDotStarMatchesNothing() {
        // 契约明文点出的第二个坑：mcp__memory 只含精确档字符，因此被当成精确串比较，
        // 而没有任何工具就叫这个名字——它匹配不到任何东西。补上 .* 才落入正则档。
        HookMatcher bare = matcher("mcp__memory");
        assertEquals(HookMatcher.Tier.EXACT, bare.tier());
        assertFalse(bare.matches(HookEvent.PRE_TOOL_USE, "mcp__memory__create_entities"));

        HookMatcher wildcard = matcher("mcp__memory__.*");
        assertEquals(HookMatcher.Tier.REGEX, wildcard.tier());
        assertTrue(wildcard.matches(HookEvent.PRE_TOOL_USE, "mcp__memory__create_entities"));
    }

    @Test
    void eventsWithoutMatcherSupportAlwaysFire() {
        // 契约规定此时 matcher 字段被静默忽略，不是「匹配不上所以不触发」。
        HookMatcher narrow = matcher("bash");
        assertFalse(HookEvent.USER_PROMPT_SUBMIT.matchable());
        assertTrue(narrow.matches(HookEvent.USER_PROMPT_SUBMIT, ""));
        assertFalse(HookEvent.STOP.matchable());
        assertTrue(narrow.matches(HookEvent.STOP, ""));
    }

    @Test
    void anInvalidRegexFailsAtConstructionNotAtDispatch() {
        // 模式串来自配置文件：写错了要在启动时炸，而不是等到某个工具调用触发它时才在回合中间抛。
        assertThrows(IllegalArgumentException.class, () -> matcher("(unclosed"));
    }

    @Test
    void anEmptyHandlerListIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new HookMatcher("*", List.of(), Optional.empty()));
    }

    private static HookMatcher matcher(String pattern) {
        return new HookMatcher(
                pattern,
                List.of(new HookHandler.Callback(input -> HookOutput.Sync.none())),
                Optional.empty());
    }
}
