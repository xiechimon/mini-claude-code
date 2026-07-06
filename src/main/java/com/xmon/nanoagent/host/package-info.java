/**
 * 进程与外部世界的接触面
 *
 * <p>本包收纳被多个能力包共同依赖、且自身不依赖任何能力包的那一层：
 * {@link com.xmon.nanoagent.host.Workspace} 是以启动目录为根的路径边界，
 * {@link com.xmon.nanoagent.host.EffectiveEnvironment} 是进程变量与 {@code .env} 合并后的配置视图。
 *
 * <p>「被所有能力包依赖、不依赖任何能力包」是本包唯一的收纳标准——新类型能否进来按这条判断，
 * 而不是按「看起来像基础设施」。本包不得 import 同级的其余任何包。
 */
package com.xmon.nanoagent.host;
