package com.xmon.nanoagent.hook;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 一组 hook 及其触发条件
 *
 * <p>对应 Claude Code 契约的 {@code HookCallbackMatcher}：一个可选的 {@code matcher} 模式、
 * 一组 handler、一个可选的组级超时。
 *
 * <p>{@code matcher} 的求值分三档，按模式串**含有哪些字符**判别，见 {@link Tier}。这个「按字符集选档」
 * 的规则是契约明文，不是本实现的发明——它让 {@code Bash} 走精确匹配、{@code mcp__memory__.*}
 * 走正则，用户不需要额外声明用哪种。
 *
 * <p>正则在构造时就编译：模式串来自配置文件，写错了应该在启动时炸，而不是等到某个工具调用触发它时
 * 才在回合中间抛出来。
 */
public final class HookMatcher {

    /**
     * 匹配所有取值的模式串，与省略 {@code matcher} 同效。
     *
     * <p>名字带 {@code _PATTERN} 后缀是必需的：叫 {@code MATCH_ALL} 时，嵌套枚举 {@link Tier} 里的
     * 同名引用会解析到枚举常量 {@code Tier.MATCH_ALL} 而不是这个字符串，于是 {@code "*".equals(枚举)}
     * 恒为 false，{@code *} 会一路掉进正则档并因 "Dangling meta character" 编译失败。
     */
    private static final String MATCH_ALL_PATTERN = "*";

    /** 精确匹配档允许的字符集，取自契约。含其他字符即落入正则档。 */
    private static final Pattern EXACT_MATCH_CHARSET = Pattern.compile("[A-Za-z0-9_\\-, |]*");

    /** 精确匹配档的分隔符：{@code |} 或 {@code ,}，两侧允许空白。 */
    private static final Pattern EXACT_MATCH_SEPARATOR = Pattern.compile("\\s*[|,]\\s*");

    private final Tier tier;
    private final List<String> exactValues;
    private final Pattern regex;
    private final List<HookHandler> handlers;
    private final Optional<Duration> timeout;

    /**
     * 创建一组 hook
     *
     * @param matcher 模式串，{@code null}、空串或 {@code *} 表示匹配所有
     * @param handlers 命中时执行的 handler，至少一个
     * @param timeout 组级超时，为空时由 handler 自身的默认值决定
     * @throws IllegalArgumentException handler 列表为空，或正则档的模式串语法错误
     */
    public HookMatcher(String matcher, List<HookHandler> handlers, Optional<Duration> timeout) {
        this.handlers = List.copyOf(Objects.requireNonNull(handlers, "handlers"));
        if (this.handlers.isEmpty()) {
            throw new IllegalArgumentException("handlers must not be empty");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.tier = Tier.of(matcher);
        this.exactValues = tier == Tier.EXACT ? splitExactValues(matcher) : List.of();
        try {
            this.regex = tier == Tier.REGEX ? Pattern.compile(matcher) : null;
        } catch (java.util.regex.PatternSyntaxException invalid) {
            throw new IllegalArgumentException("invalid matcher regex: " + matcher, invalid);
        }
    }

    /**
     * 判断本组是否应在该事件的该取值上触发
     *
     * <p>事件不支持 matcher 时恒为 {@code true}：契约规定此时 {@code matcher} 字段被静默忽略。
     * 「静默」是契约行为，不是本实现的偷懒——已在 {@link HookEvent#matchable()} 记录。
     *
     * @param event 触发的事件
     * @param value 被匹配的取值。工具类事件传工具名，事件不支持 matcher 时该参数不被读取
     * @return 应触发时为 {@code true}
     */
    public boolean matches(HookEvent event, String value) {
        if (!Objects.requireNonNull(event, "event").matchable()) {
            return true;
        }
        Objects.requireNonNull(value, "value");
        return switch (tier) {
            case MATCH_ALL -> true;
            case EXACT -> exactValues.contains(value);
            case REGEX -> regex.matcher(value).find();
        };
    }

    /**
     * 返回本组的 handler
     *
     * @return handler 列表
     */
    public List<HookHandler> handlers() {
        return handlers;
    }

    /**
     * 返回组级超时
     *
     * @return 超时，未配置时为空
     */
    public Optional<Duration> timeout() {
        return timeout;
    }

    /**
     * 返回本组模式串落入的求值档
     *
     * @return 求值档
     */
    Tier tier() {
        return tier;
    }

    /**
     * 拆分精确匹配档的模式串
     *
     * @param matcher 模式串
     * @return 逐个精确取值，已去掉两侧空白
     */
    private static List<String> splitExactValues(String matcher) {
        return Arrays.stream(EXACT_MATCH_SEPARATOR.split(matcher.strip(), -1))
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * {@code matcher} 模式串的求值档
     *
     * <p>三档取自契约，判别依据是模式串含有哪些字符，而不是用户声明。
     */
    enum Tier {

        /** {@code null}、空串或 {@code *}：匹配所有取值。 */
        MATCH_ALL,

        /** 仅含字母、数字、{@code _}、{@code -}、空格、{@code ,}、{@code |}：精确串或精确串列表。 */
        EXACT,

        /** 含其他任何字符：非锚定正则。 */
        REGEX;

        /**
         * 判别模式串落入哪一档
         *
         * <p>本项目未实现 {@code FileChanged} 与 {@code StopFailure}，因此不含契约给这两个事件的
         * 特例（它们的精确匹配档只允许字母、数字、{@code _}、{@code |}，连字符与逗号会把模式串推入正则档）。
         *
         * @param matcher 模式串，可为 {@code null}
         * @return 求值档
         */
        static Tier of(String matcher) {
            if (matcher == null || matcher.isEmpty() || matcher.equals(MATCH_ALL_PATTERN)) {
                return MATCH_ALL;
            }
            return EXACT_MATCH_CHARSET.matcher(matcher).matches() ? EXACT : REGEX;
        }
    }
}
