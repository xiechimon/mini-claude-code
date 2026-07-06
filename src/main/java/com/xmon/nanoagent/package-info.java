/**
 * 进程入口与命令行交互
 *
 * <p>本包组装其余各包并驱动一次会话，不含任何可复用逻辑。依赖方向是单向的：本包依赖
 * {@code core}、{@code tool}、{@code permission}，它们再依赖 {@code host}，反向依赖一律不允许。
 *
 * <p>import 必须反映合法的依赖方向，Javadoc 也不例外：跨包引用某个类型只是为了在文档里提它时，
 * 若该方向不合法，用 {@code {@code X}} 而不是 {@code {@link X}}——文档里的一句话不值得换来一条
 * 反向的编译期依赖边。
 */
package com.xmon.nanoagent;
