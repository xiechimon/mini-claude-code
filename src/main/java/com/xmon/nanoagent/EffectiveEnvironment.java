package com.xmon.nanoagent;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 加载环境变量
 */
final class EffectiveEnvironment {

    private static final String DOTENV_FILE = ".env";

    private final Map<String, String> values;

    /**
     * 保存环境变量快照
     *
     * @param values 环境变量
     */
    private EffectiveEnvironment(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * 合并进程环境变量与最近的 {@code .env} 文件
     *
     * @param workingDirectory 当前工作目录
     * @param inherited 进程环境变量
     * @return 合并后的环境变量
     */
    static EffectiveEnvironment load(Path workingDirectory, Map<String, String> inherited) {
        Map<String, String> merged = new HashMap<>(inherited);
        Path dotenvDirectory = findDotenvDirectory(workingDirectory.toAbsolutePath().normalize());
        if (dotenvDirectory != null) {
            Dotenv dotenv = Dotenv.configure()
                    .directory(dotenvDirectory.toString())
                    .filename(DOTENV_FILE)
                    .load();
            for (DotenvEntry entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        String baseUrl = merged.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            merged.remove("ANTHROPIC_AUTH_TOKEN");
        }
        return new EffectiveEnvironment(merged);
    }

    /**
     * 向上查找最近的 {@code .env} 文件目录
     *
     * @param start 起始目录
     * @return 文件所在目录，未找到时返回 {@code null}
     */
    private static Path findDotenvDirectory(Path start) {
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve(DOTENV_FILE))) {
                return directory;
            }
        }
        return null;
    }

    /**
     * 获取必需的环境变量
     *
     * @param name 变量名
     * @return 变量值
     * @throws IllegalStateException 变量不存在
     */
    String require(String name) {
        if (!values.containsKey(name)) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return values.get(name);
    }

    /**
     * 获取环境变量
     *
     * @param name 变量名
     * @return 变量值，不存在时返回 {@code null}
     */
    String get(String name) {
        return values.get(name);
    }

    /**
     * 获取全部环境变量
     *
     * @return 不可变的环境变量映射
     */
    Map<String, String> values() {
        return values;
    }
}
