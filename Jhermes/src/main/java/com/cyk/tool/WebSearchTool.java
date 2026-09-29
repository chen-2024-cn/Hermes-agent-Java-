package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Web search tool for Hermes agent.
 *
 * <p>Provides a single {@code web_search} tool that searches the web
 * and returns the top results. The backend is chosen automatically:</p>
 * <ol>
 *   <li>If {@code SERPAPI_KEY} env var is set → SerpAPI</li>
 *   <li>If {@code GOOGLE_API_KEY} and {@code GOOGLE_CSE_ID} are set → Google CSE</li>
 *   <li>Otherwise → Baidu HTML scraping (default, no API key needed)</li>
 * </ol>
 */
public class WebSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build();

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; HermesBot/1.0)";

    /**
     * Main search entry point. Called by ToolRegistry.dispatch().
     *
     * @param args must contain "query" (String), optionally "count" (Integer, 1-10, default 5)
     * @return JSON string with search results or error
     */
    public static String search(Map<String, Object> args) {
        // 1. Extract parameters
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return ToolRegistry.toolError("缺少必填参数: query");
        }

        int count = 5;
        if (args.containsKey("count")) {
            count = ((Number) args.get("count")).intValue();
            count = Math.max(1, Math.min(10, count));
        }

        // 2. Select backend
        String serpApiKey = System.getenv("SERPAPI_KEY");
        String googleApiKey = System.getenv("GOOGLE_API_KEY");
        String googleCseId = System.getenv("GOOGLE_CSE_ID");

        try {
            if (serpApiKey != null && !serpApiKey.isBlank()) {
                return searchSerpAPI(query, count, serpApiKey);
            } else if (googleApiKey != null && !googleApiKey.isBlank()
                    && googleCseId != null && !googleCseId.isBlank()) {
                return searchGoogle(query, count, googleApiKey, googleCseId);
            } else {
                return searchBaidu(query, count);
            }
        } catch (Exception e) {
            logger.error("Search failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("搜索失败: " + e.getMessage());
        }
    }

    // =========================================================================
    // DuckDuckGo Backend (default, no API key)
    // =========================================================================

    private static String searchDuckDuckGo(String query, int count) {
        try {
            String url = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);

            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(5000)
                    .get();

            Elements resultElements = doc.select(".result");
            List<Map<String, String>> results = new ArrayList<>();

            for (Element result : resultElements) {
                if (results.size() >= count) break;

                Element titleEl = result.selectFirst(".result__title a");
                Element urlEl = result.selectFirst(".result__url");
                Element snippetEl = result.selectFirst(".result__snippet");

                String title = titleEl != null ? titleEl.text().trim() : "";
                String resultUrl = urlEl != null ? urlEl.text().trim() : "";
                String snippet = snippetEl != null ? snippetEl.text().trim() : "";

                if (title.isEmpty()) continue;

                results.add(Map.of(
                        "title", title,
                        "url", resultUrl,
                        "snippet", snippet
                ));
            }

            if (results.isEmpty()) {
                return ToolRegistry.toolError("未找到相关结果，请尝试更换关键词");
            }

            return ToolRegistry.toolResult(Map.of(
                    "query", query,
                    "backend", "ddg",
                    "results", results,
                    "count", results.size()
            ));

        } catch (Exception e) {
            logger.error("DuckDuckGo search failed: {}", e.getMessage(), e);
            if (e instanceof java.net.SocketTimeoutException
                    || e.getMessage() != null && e.getMessage().contains("timeout")) {
                return ToolRegistry.toolError("搜索请求超时，请稍后重试");
            }
            if (e instanceof java.net.UnknownHostException
                    || e.getMessage() != null && e.getMessage().contains("UnknownHost")) {
                return ToolRegistry.toolError("网络不可达，无法完成搜索");
            }
            return ToolRegistry.toolError("搜索失败: " + e.getMessage());
        }
    }

    // =========================================================================
    // Baidu Backend (default, no API key, domestic network)
    // =========================================================================

    private static String searchBaidu(String query, int count) {
        try {
            String url = "https://www.baidu.com/s?wd="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&rn=" + count;

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", "https://www.baidu.com/")
                    .timeout(10000)
                    .get();

            // Debug: print page title to check if Baidu returned a valid page  调试
            //logger.info("Baidu page title: {}", doc.title());

            // Try multiple selectors (Baidu frequently changes class names)
            Elements resultElements = doc.select(
                    "div.result.c-container, div.c-container, div.c-result, div[tpl=se_com_default]");
            if (resultElements.isEmpty()) {
                resultElements = doc.select("div.result");
            }

            //logger.info("Baidu result elements found: {}", resultElements.size()); 调试

            List<Map<String, String>> results = new ArrayList<>();

            for (Element result : resultElements) {
                if (results.size() >= count) break;

                // Title: h3 > a (standard organic results)
                Element titleEl = result.selectFirst("h3 a, h3[class*=t] a, a[class*=title]");
                // Fallback: any link (weather cards, special results)
                if (titleEl == null) {
                    titleEl = result.selectFirst("a[href]");
                }
                if (titleEl == null) continue;

                String title = titleEl.text().trim();
                if (title.isEmpty()) continue;

                String resultUrl = titleEl.attr("href");

                // Snippet: try multiple known class patterns
                Element snippetEl = result.selectFirst(
                        "span.content-right_8Zs40, div.c-abstract, span.c-abstract, "
                                + "div.c-span-last, div[class*=abstract], span[class*=content]");
                String snippet = snippetEl != null ? snippetEl.text().trim()
                        : result.text().replace(title, "").trim();

                results.add(Map.of(
                        "title", title,
                        "url", resultUrl,
                        "snippet", snippet
                ));
            }

            if (results.isEmpty()) {
                return ToolRegistry.toolError("未找到相关结果，请尝试更换关键词");
            }

            System.out.println("使用 searchBaidu 进行后端搜索");
            return ToolRegistry.toolResult(Map.of(
                    "query", query,
                    "backend", "baidu",
                    "results", results,
                    "count", results.size()
            ));

        } catch (Exception e) {
            logger.error("Baidu search failed: {}", e.getMessage(), e);
            if (e instanceof java.net.SocketTimeoutException
                    || e.getMessage() != null && e.getMessage().contains("timeout")) {
                return ToolRegistry.toolError("搜索请求超时，请稍后重试");
            }
            if (e instanceof java.net.UnknownHostException
                    || e.getMessage() != null && e.getMessage().contains("UnknownHost")) {
                return ToolRegistry.toolError("网络不可达，无法完成搜索");
            }
            return ToolRegistry.toolError("搜索失败: " + e.getMessage());
        }
    }

    // =========================================================================
    // SerpAPI Backend
    // =========================================================================

    private static String searchSerpAPI(String query, int count, String apiKey) {
        try {
            String url = "https://serpapi.com/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&num=" + count
                    + "&engine=google";

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    return ToolRegistry.toolError("搜索 API 返回错误: HTTP " + response.code() + " " + body);
                }

                String body = response.body() != null ? response.body().string() : "";
                JsonNode root = objectMapper.readTree(body);

                // Check for API error
                if (root.has("error")) {
                    return ToolRegistry.toolError("搜索 API 返回错误: " + root.get("error").asText());
                }

                List<Map<String, String>> results = new ArrayList<>();
                JsonNode organicResults = root.get("organic_results");
                if (organicResults != null && organicResults.isArray()) {
                    for (JsonNode item : organicResults) {
                        if (results.size() >= count) break;
                        results.add(Map.of(
                                "title", item.has("title") ? item.get("title").asText("") : "",
                                "url", item.has("link") ? item.get("link").asText("") : "",
                                "snippet", item.has("snippet") ? item.get("snippet").asText("") : ""
                        ));
                    }
                }

                if (results.isEmpty()) {
                    return ToolRegistry.toolError("未找到相关结果，请尝试更换关键词");
                }
                System.out.println("使用 searchSerpAPI 进行后端搜索");
                return ToolRegistry.toolResult(Map.of(
                        "query", query,
                        "backend", "serpapi",
                        "results", results,
                        "count", results.size()
                ));
            }
        } catch (IOException e) {
            logger.error("SerpAPI search failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("搜索 API 请求失败: " + e.getMessage());
        }
    }

    // =========================================================================
    // Google Custom Search Backend
    // =========================================================================

    private static String searchGoogle(String query, int count, String apiKey, String cseId) {
        try {
            String url = "https://www.googleapis.com/customsearch/v1?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&cx=" + URLEncoder.encode(cseId, StandardCharsets.UTF_8)
                    + "&num=" + count;

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429 || response.code() == 403) {
                        return ToolRegistry.toolError("搜索 API 配额已用尽");
                    }
                    return ToolRegistry.toolError("搜索 API 返回错误: HTTP " + response.code());
                }

                String body = response.body() != null ? response.body().string() : "";
                JsonNode root = objectMapper.readTree(body);

                // Check for API error
                if (root.has("error")) {
                    JsonNode error = root.get("error");
                    String msg = error.has("message") ? error.get("message").asText() : error.toString();
                    int code = error.has("code") ? error.get("code").asInt() : 0;
                    if (code == 429 || code == 403) {
                        return ToolRegistry.toolError("搜索 API 配额已用尽");
                    }
                    return ToolRegistry.toolError("搜索 API 返回错误: " + msg);
                }

                List<Map<String, String>> results = new ArrayList<>();
                JsonNode items = root.get("items");
                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        if (results.size() >= count) break;
                        results.add(Map.of(
                                "title", item.has("title") ? item.get("title").asText("") : "",
                                "url", item.has("link") ? item.get("link").asText("") : "",
                                "snippet", item.has("snippet") ? item.get("snippet").asText("") : ""
                        ));
                    }
                }

                if (results.isEmpty()) {
                    return ToolRegistry.toolError("未找到相关结果，请尝试更换关键词");
                }
                System.out.println("使用 searchGoogle 进行后端搜索");
                return ToolRegistry.toolResult(Map.of(
                        "query", query,
                        "backend", "google",
                        "results", results,
                        "count", results.size()
                ));
            }
        } catch (IOException e) {
            logger.error("Google CSE search failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("搜索 API 请求失败: " + e.getMessage());
        }
    }

    // =========================================================================
    // Registration
    // =========================================================================

    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
                .name("web_search")
                .toolset("web")
                .schema(Map.of(
                        "description", "搜索网页获取实时信息。当需要最新新闻、事实核查、" +
                                "实时数据或任何超出模型知识范围的信息时，应优先使用此工具。" +
                                "返回前N条结果的标题、链接和摘要。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of(
                                                "type", "string",
                                                "description", "搜索关键词"
                                        ),
                                        "count", Map.of(
                                                "type", "integer",
                                                "description", "返回结果数量，默认5，最大10",
                                                "default", 5
                                        )
                                ),
                                "required", List.of("query")
                        )
                ))
                .handler(WebSearchTool::search)
                .emoji("🌐")
                .build());
    }

    // =========================================================================
    // Quick test
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== WebSearchTool 搜索功能测试 ===\n");

        // Test 1: Basic search
        System.out.println(">>> 测试 1: 搜索 'Java 21 release date'");
        String result = search(Map.of("query", "Java 21 release date"));
        System.out.println(result);
        System.out.println();

        // Test 2: Search with count limit
        System.out.println(">>> 测试 2: 搜索 '今天天气' (只返回2条)");
        result = search(Map.of("query", "安徽合肥今天天气", "count", 2));
        System.out.println(result);
        System.out.println();

        // Print API key setup guide
        System.out.println("=== 环境变量配置指引 ===");
        System.out.println("默认使用百度 HTML 抓取（无需 API Key，国内直连）。");
        System.out.println();
        System.out.println("如需更稳定的搜索服务，可设置以下环境变量：");
        System.out.println("  SERPAPI_KEY      → 使用 SerpAPI (https://serpapi.com)");
        System.out.println("  GOOGLE_API_KEY   → 使用 Google Custom Search");
        System.out.println("  GOOGLE_CSE_ID    → Google CSE 引擎 ID");
        System.out.println();
        System.out.println("优先级: SERPAPI_KEY > GOOGLE_API_KEY+GOOGLE_CSE_ID > Baidu");
        System.out.println();
        System.out.println("示例 (PowerShell):");
        System.out.println("  $env:SERPAPI_KEY = \"your_key_here\"");
        System.out.println();
        System.out.println("示例 (Bash):");
        System.out.println("  export SERPAPI_KEY=\"your_key_here\"");
    }
}
