package com.miniclaudecode.web;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnySearchProviderTest {

    private okhttp3.mockwebserver.MockWebServer server;
    private AnySearchProvider provider;

    @BeforeEach
    void setup() throws IOException {
        server = new okhttp3.mockwebserver.MockWebServer();
        server.start();
        provider = new AnySearchProvider("test-key",
                new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build(),
                server.url("/v1/search").toString());
    }

    @AfterEach
    void shutdown() throws IOException {
        server.shutdown();
    }

    private static String okBody() {
        return """
                {"code":0,"message":"success","request_id":"r-1","data":{
                  "results":[
                    {"title":"Virtual Threads in 2026","url":"https://example.com/vt",
                     "snippet":"Java 26 ships...","content":"full text"},
                    {"title":"Loom Wiki","url":"https://wiki.example.com/loom",
                     "snippet":"Loom updates","content":"full text"}
                  ],
                  "metadata":{"total_results":2,"search_time_ms":946}}}
                """;
    }

    @Test
    void alwaysReadyWithOrWithoutKey() {
        assertTrue(new AnySearchProvider("key", null, null).isReady());
        assertTrue(new AnySearchProvider("", null, null).isReady());
        assertTrue(new AnySearchProvider(null, null, null).isReady());
    }

    @Test
    void noUnavailableHintWhenAlwaysReady() {
        assertEquals("", new AnySearchProvider(null, null, null).unavailableHint());
    }

    @Test
    void parsesResultsInOrder() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(okBody()));

        List<SearchResult> results = provider.search("Java virtual threads 2026", 5);

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).position());
        assertEquals("Virtual Threads in 2026", results.get(0).title());
        assertEquals("https://example.com/vt", results.get(0).url());
        assertEquals("Java 26 ships...", results.get(0).snippet());
        assertEquals("example.com", results.get(0).source());
        assertEquals(2, results.get(1).position());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v1/search", recorded.getPath());
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"));
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"query\":\"Java virtual threads 2026\""), body);
        assertTrue(body.contains("\"max_results\":5"), body);
    }

    @Test
    void anonymousRequestOmitsAuthorizationHeader() throws IOException, InterruptedException {
        AnySearchProvider anonymous = new AnySearchProvider("",
                new OkHttpClient.Builder().build(),
                server.url("/v1/search").toString());
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(okBody()));

        anonymous.search("q", 3);

        assertNull(server.takeRequest().getHeader("Authorization"));
    }

    @Test
    void topKClampedToApiRange() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(okBody()));
        provider.search("q", 99);
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"max_results\":20"), body);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(okBody()));
        provider.search("q", 0);
        body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"max_results\":10"), body);
    }

    @Test
    void invalidKeySurfacesAuthErrorWithoutAnonymousFallback() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":401,\"message\":\"invalid api key\"}"));

        IOException e = assertThrows(() -> provider.search("q", 5));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertTrue(e.getMessage().contains("ANYSEARCH_API_KEY"), e.getMessage());
    }

    @Test
    void rateLimitSurfaces429Hint() {
        server.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":429,\"message\":\"rate limited\"}"));

        IOException e = assertThrows(() -> provider.search("q", 5));
        assertTrue(e.getMessage().contains("429"), e.getMessage());
    }

    @Test
    void emptyResultsReturnEmptyList() throws IOException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"success\",\"data\":{\"results\":[],\"metadata\":{}}}}"));

        assertEquals(List.of(), provider.search("q", 5));
    }

    @Test
    void malformedJsonSurfacesError() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("not json"));

        IOException e = assertThrows(() -> provider.search("q", 5));
        assertTrue(e.getMessage().contains("anysearch"), e.getMessage());
    }

    private static IOException assertThrows(ThrowingRunnable run) {
        try {
            run.run();
            throw new AssertionError("expected IOException");
        } catch (IOException expected) {
            return expected;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException, InterruptedException;
    }
}
