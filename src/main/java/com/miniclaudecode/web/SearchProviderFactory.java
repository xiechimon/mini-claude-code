package com.miniclaudecode.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * 按环境变量 / .env / 系统属性选择 SearchProvider 实现
 * <p>
 * 默认 provider 是 anysearch：配置 {@code ANYSEARCH_API_KEY} 走付费额度，
 * 无 Key 走匿名免费档，因此默认链路在任何配置下都可用
 * <p>
 * 显式 {@code SEARCH_PROVIDER}（anysearch / zhipu / serpapi / searxng）会跳过默认，
 * GLM Key 用户想继续用智谱搜索需显式声明 zhipu
 * <p>
 * 这里不做单例缓存，由调用方按需缓存（如 ToolRegistry 的 webSearchProvider 字段）
 */
public final class SearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(SearchProviderFactory.class);

    private SearchProviderFactory() {
    }

    public static SearchProvider create() {
        String provider = readEnv("SEARCH_PROVIDER");
        String anyKey = readEnv("ANYSEARCH_API_KEY");
        String glmKey = readEnv("GLM_API_KEY");
        String zhipuEngine = readEnv("ZHIPU_SEARCH_ENGINE");
        String serpKey = readEnv("SERPAPI_KEY");
        String searxngUrl = readEnv("SEARXNG_URL");

        String chosen = pickProvider(provider);
        log.info("SearchProvider chosen: {}", chosen);

        return switch (chosen) {
            case "zhipu" -> new ZhipuSearchProvider(glmKey, zhipuEngine);
            case "serpapi" -> new SerpApiSearchProvider(serpKey);
            case "searxng" -> new SearxngSearchProvider(searxngUrl);
            default -> new AnySearchProvider(anyKey);
        };
    }

    static String pickProvider(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        return "anysearch"; // 匿名档可用，无需占位 provider
    }

    private static String readEnv(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        return readFromDotEnv(key);
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = {new File(".env"), new File(System.getProperty("user.home"), ".env")};
        for (File envFile : envFiles) {
            if (!envFile.exists()) continue;
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
