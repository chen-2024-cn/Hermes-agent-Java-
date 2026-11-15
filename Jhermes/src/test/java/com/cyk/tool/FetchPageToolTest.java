package com.cyk.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cyk.ui.Ui;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FetchPageToolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger hits = new AtomicInteger();
    private final ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ListAppender<>();
    private HttpServer server;
    private String base;
    private Logger logger;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        logger = (Logger) LoggerFactory.getLogger(FetchPageTool.class);
        logs.start();
        logger.addAppender(logs);
        Ui.setStreams(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), null);
    }

    @AfterEach void stop() {
        server.stop(0);
        logger.detachAppender(logs);
        logs.stop();
        Ui.resetForTest();
    }

    private void endpoint(String path, int status, String type, String body, String location) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", type);
            if (location != null) exchange.getResponseHeaders().set("Location", location);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }

    private JsonNode fetch(String path) throws Exception {
        server.start();
        return mapper.readTree(FetchPageTool.fetch(Map.of("url", base + path)));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 410, 429, 500, 503})
    void httpFailuresDoNotRetryOrUseBrowser(int status) throws Exception {
        endpoint("/error", status, "text/html", "<body>" + "NOT_REAL_CONTENT ".repeat(10) + "</body>", null);
        JsonNode result = fetch("/error");
        assertThat(result.has("error")).isTrue();
        assertThat(result.has("text")).isFalse();
        assertThat(result.path("status_code").asInt()).isEqualTo(status);
        assertThat(result.path("engine").asText()).isEqualTo("jsoup");
        assertThat(result.path("retryable").asBoolean()).isEqualTo(status == 429 || status >= 500);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(logs.list).noneMatch(event -> event.getThrowableProxy() != null
                || event.getFormattedMessage().contains("尝试浏览器"));
    }

    @Test void redirectedMissingPageReportsBothUrls() throws Exception {
        endpoint("/old", 302, "text/html", "redirect", base + "/missing");
        endpoint("/missing", 404, "text/html", "not found", null);
        JsonNode result = fetch("/old");
        assertThat(result.path("url").asText()).isEqualTo(base + "/old");
        assertThat(result.path("final_url").asText()).isEqualTo(base + "/missing");
        assertThat(result.path("error").asText()).contains("HTTP 404", "不要重复抓取");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test void quotedTextDoesNotTriggerFalseSparseFallback() throws Exception {
        String text = "a \"quoted\" value " + "useful page content ".repeat(10);
        endpoint("/quoted", 200, "text/html; charset=UTF-8", "<title>Sample</title><body>" + text + "</body>", null);
        JsonNode result = fetch("/quoted");
        assertThat(result.path("text").asText()).isEqualTo(text.trim());
        assertThat(result.path("title").asText()).isEqualTo("Sample");
        assertThat(result.path("status_code").asInt()).isEqualTo(200);
        assertThat(result.path("engine").asText()).isEqualTo("jsoup");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test void successfulRedirectKeepsMetadataAndTextBudget() throws Exception {
        endpoint("/old", 302, "text/html", "redirect", base + "/long");
        endpoint("/long", 200, "text/html", "<body>" + "generated text ".repeat(200) + "</body>", null);
        JsonNode result = fetch("/old");
        assertThat(result.path("final_url").asText()).isEqualTo(base + "/long");
        assertThat(result.path("text").asText()).hasSize(2000);
        assertThat(result.path("truncated").asBoolean()).isTrue();
    }

    @Test void unsupportedContentDoesNotLaunchBrowser() throws Exception {
        endpoint("/pdf", 200, "application/pdf", "%PDF generated fixture", null);
        JsonNode result = fetch("/pdf");
        assertThat(result.path("error_code").asText()).isEqualTo("unsupported_content_type");
        assertThat(result.has("text")).isFalse();
        assertThat(hits.get()).isEqualTo(1);
        assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("尝试浏览器"));
    }

    @Test void invalidArgumentsAreLocalErrors() throws Exception {
        assertThat(mapper.readTree(FetchPageTool.fetch(Map.of())).has("error")).isTrue();
        assertThat(mapper.readTree(FetchPageTool.fetch(Map.of("url", 42))).has("error")).isTrue();
        assertThat(mapper.readTree(FetchPageTool.fetch(Map.of("url", "file:///generated"))).has("error")).isTrue();
        assertThat(hits.get()).isZero();
    }
}
