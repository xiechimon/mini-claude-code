package com.miniclaudecode.pty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SseStubServerTest {

    @Test
    void servesTextReplySse() throws Exception {
        StubScript script = StubScript.textReply("hello from stub");
        try (SseStubServer server = new SseStubServer(script)) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"glm-5.1\"}"))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
                    "Content-Type 应为 SSE");

            String body = resp.body();
            assertTrue(body.startsWith("data: "), "应以 data: 开头");
            assertTrue(body.contains("hello from stub"), "应包含文本内容");
            assertTrue(body.contains("\"finish_reason\":\"stop\""), "应有 finish_reason stop");
            assertTrue(body.contains("[DONE]"), "应以 [DONE] 结尾");
        }
    }

    @Test
    void servesToolCallSse() throws Exception {
        StubScript script = StubScript.readFile("README.md");
        try (SseStubServer server = new SseStubServer(script)) {
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/chat/completions"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{}"))
                            .timeout(Duration.ofSeconds(5))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(200, resp.statusCode());
            String body = resp.body();
            assertTrue(body.contains("\"name\":\"read_file\""), "应包含工具名 read_file，实际: " + body);
            assertTrue(body.contains("\"finish_reason\":\"tool_calls\""), "应有 finish_reason tool_calls");
        }
    }

    @Test
    void incrementsRequestIndex() throws Exception {
        StubScript script = StubScript.toolThenReply(
                "read_file", "{\"path\":\"x\"}", "got it");
        try (SseStubServer server = new SseStubServer(script)) {
            assertEquals(0, server.requestCount());

            sendRequest(server.baseUrl());
            sendRequest(server.baseUrl());
            sendRequest(server.baseUrl());

            assertEquals(3, server.requestCount(), "三次请求应递增计数");
        }
    }

    private static void sendRequest(String baseUrl) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .timeout(Duration.ofSeconds(5))
                        .build(),
                HttpResponse.BodyHandlers.discarding()
        );
    }
}