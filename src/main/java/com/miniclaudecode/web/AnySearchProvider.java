package com.miniclaudecode.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AnySearch provider —— 默认搜索通道
 *
 * <p>Endpoint: {@code POST https://api.anysearch.com/v1/search}，聚合多源并重排
 *
 * <p>认证两档：配置 {@code ANYSEARCH_API_KEY} 时走 Bearer（付费额度、高并发）；
 * 无 Key 走匿名免费档（按 IP 限速、每日额度）。因此本 provider 恒为 ready——
 * 这也是它作为默认 provider 的前提：没有任何 Key 时 web_search 仍可用
 *
 * <p>错误边界：带 Key 请求返回 401/403 说明 Key 失效，网关不会静默降级匿名，
 * 必须把错误暴露给用户而不是吞掉换匿名重试
 */
public class AnySearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(AnySearchProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://api.anysearch.com/v1/search";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_RESULTS_LIMIT = 20;

    private final String apiKey;
    private final OkHttpClient httpClient;
    private final String endpoint;

    public AnySearchProvider(String apiKey) {
        this(apiKey, defaultClient(), ENDPOINT);
    }

    AnySearchProvider(String apiKey, OkHttpClient httpClient, String endpoint) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = httpClient == null ? defaultClient() : httpClient;
        this.endpoint = endpoint == null || endpoint.isBlank() ? ENDPOINT : endpoint;
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String name() {
        return "anysearch";
    }

    @Override
    public boolean isReady() {
        // 匿名免费档始终可用，ready 不取决于 Key
        return true;
    }

    @Override
    public String unavailableHint() {
        return "";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        int count = topK > 0 ? Math.min(topK, MAX_RESULTS_LIMIT) : 10;

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.put("max_results", count);

        Request.Builder builder = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON_MEDIA));
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        boolean anonymous = apiKey.isBlank();
        log.info("AnySearch search: query={}, count={}, auth={}", query, count, anonymous ? "anonymous" : "key");

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.code() == 401 || response.code() == 403) {
                throw new IOException("anysearch 搜索失败: HTTP " + response.code()
                        + "（ANYSEARCH_API_KEY 无效或已禁用，不会回退匿名档）: " + preview(body));
            }
            if (response.code() == 429) {
                throw new IOException("anysearch 搜索失败: HTTP 429 限速"
                        + (anonymous ? "（匿名档按 IP 限速，配额见控制台）" : "（Key 并发或额度耗尽）"));
            }
            if (!response.isSuccessful()) {
                throw new IOException("anysearch 搜索失败: HTTP " + response.code() + ": " + preview(body));
            }
            return parseResults(body);
        }
    }

    private List<SearchResult> parseResults(String body) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IOException("anysearch 响应不是合法 JSON: " + preview(body), e);
        }
        JsonNode results = root.path("data").path("results");
        if (!results.isArray()) {
            throw new IOException("anysearch 响应缺少 data.results 数组: " + preview(body));
        }
        List<SearchResult> parsed = new ArrayList<>();
        int position = 1;
        for (JsonNode item : results) {
            parsed.add(SearchResult.of(position++, item.path("title").asText(""),
                    item.path("url").asText(""), item.path("snippet").asText("")));
        }
        return parsed;
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
