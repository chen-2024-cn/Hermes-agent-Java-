package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Web page fetching tool for Hermes agent.
 *
 * <p>Provides a {@code fetch_page} tool that fetches a webpage and
 * extracts its visible text content, limited to 2000 characters.</p>
 *
 * <p>Uses a dual-engine strategy:</p>
 * <ol>
 *   <li>Jsoup — fast, no JS execution. Used for server-rendered pages.</li>
 *   <li>Playwright — headless Chromium. Fallback for SPAs and anti-bot sites.</li>
 * </ol>
 */
public class FetchPageTool {

    private static final Logger logger = LoggerFactory.getLogger(FetchPageTool.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int MAX_TEXT_LENGTH = 2000;

    // Playwright singleton — lazily initialized on first fallback
    private static volatile Playwright playwright;
    private static volatile Browser playwrightBrowser;
    private static volatile boolean playwrightAvailable = true;

    private static synchronized Browser getBrowser() {
        if (!playwrightAvailable) return null;
        if (playwrightBrowser == null) {
            try {
                playwright = Playwright.create();
                playwrightBrowser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true));
                //logger.info("Playwright browser initialized");
            } catch (Exception e) {
                logger.warn("Playwright unavailable, will use Jsoup only: {}", e.getMessage());
                playwrightAvailable = false;
                return null;
            }
        }
        return playwrightBrowser;
    }

    /**
     * Fetch a web page. Tries Jsoup first; falls back to Playwright if the
     * page appears to be an SPA (empty body) or returns a 403/anti-bot response.
     */
    public static String fetch(Map<String, Object> args) {
        String urlStr = (String) args.get("url");
        if (urlStr == null || urlStr.isBlank()) {
            return ToolRegistry.toolError("缺少必填参数: url");
        }

        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return ToolRegistry.toolError("无效的 URL: " + urlStr + " (必须以 http:// 或 https:// 开头)");
        }

        // Engine 1: Jsoup (fast, server-rendered pages)
        String jsoupResult = fetchWithJsoup(urlStr);
        String jsoupText = extractTextField(jsoupResult);

        if (jsoupText != null && jsoupText.length() >= 50) {
            return jsoupResult;
        }

        // Engine 2: Playwright (SPA / anti-bot pages)
        if (jsoupResult.startsWith("{\"error\"")) {
            logger.info("Jsoup returned error, trying Playwright for {}", urlStr);
        } else {
            logger.info("Jsoup returned sparse content ({} chars), trying Playwright for {}",
                    jsoupText != null ? jsoupText.length() : 0, urlStr);
        }

        String pwResult = fetchWithPlaywright(urlStr);
        if (pwResult != null) {
            return pwResult;
        }

        return jsoupResult;
    }

    // =========================================================================
    // Engine 1: Jsoup
    // =========================================================================

    private static String fetchWithJsoup(String urlStr) {
        try {
            Document doc = Jsoup.connect(urlStr)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", "https://www.baidu.com/")
                    .timeout(10000)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .get();

            String contentType = doc.connection().response().contentType();
            if (contentType != null) {
                String ct = contentType.toLowerCase();
                if (ct.contains("image/") || ct.contains("video/")
                        || ct.contains("audio/") || ct.contains("application/pdf")) {
                    return ToolRegistry.toolError("不支持的文件类型: " + contentType);
                }
            }

            String title = doc.title();
            String bodyText = doc.body() != null ? doc.body().text()
                    : doc.wholeText();

            boolean truncated = bodyText.length() > MAX_TEXT_LENGTH;
            if (truncated) {
                bodyText = bodyText.substring(0, MAX_TEXT_LENGTH);
            }

            return ToolRegistry.toolResult(Map.of(
                    "url", urlStr,
                    "title", title != null ? title : "",
                    "text", bodyText,
                    "truncated", truncated,
                    "engine", "jsoup"
            ));

        } catch (java.net.MalformedURLException e) {
            return ToolRegistry.toolError("无效的 URL: " + urlStr);
        } catch (java.net.SocketTimeoutException e) {
            return ToolRegistry.toolError("请求超时: " + urlStr);
        } catch (Exception e) {
            logger.error("Jsoup fetch failed for {}: {}", urlStr, e.getMessage(), e);
            return ToolRegistry.toolError("抓取失败(Jsoup): " + e.getMessage());
        }
    }

    // =========================================================================
    // Engine 2: Playwright
    // =========================================================================

    private static String fetchWithPlaywright(String urlStr) {
        Browser browser = getBrowser();
        if (browser == null) {
            return null;
        }

        try {
            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions()
                            .setUserAgent(USER_AGENT)
                            .setLocale("zh-CN"));

            Page page = context.newPage();
            try {
                page.setDefaultTimeout(20000);
                page.navigate(urlStr, new Page.NavigateOptions().setTimeout(20000));
                page.waitForLoadState(LoadState.NETWORKIDLE);

                String title = page.title();
                String bodyText = page.innerText("body");

                if (bodyText == null || bodyText.isBlank()) {
                    context.close();
                    return null;
                }

                boolean truncated = bodyText.length() > MAX_TEXT_LENGTH;
                if (truncated) {
                    bodyText = bodyText.substring(0, MAX_TEXT_LENGTH);
                }

                context.close();

                return ToolRegistry.toolResult(Map.of(
                        "url", urlStr,
                        "title", title != null ? title : "",
                        "text", bodyText,
                        "truncated", truncated,
                        "engine", "playwright"
                ));

            } finally {
                if (page != null && !page.isClosed()) {
                    context.close();
                }
            }

        } catch (Exception e) {
            logger.error("Playwright fetch failed for {}: {}", urlStr, e.getMessage());
            return null;
        }
    }

    /**
     * Extract the "text" field value from a JSON result string, or null.
     */
    private static String extractTextField(String json) {
        if (json == null || !json.contains("\"text\"")) return null;
        try {
            int start = json.indexOf("\"text\":\"") + 8;
            int end = json.indexOf("\"", start);
            if (start >= 8 && end > start) {
                return json.substring(start, end);
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    // =========================================================================
    // Registration
    // =========================================================================

    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
                .name("fetch_page")
                .toolset("web")
                .schema(Map.of(
                        "description", "抓取指定网页的正文文本内容（前2000字符）。" +
                                "配合 web_search 使用，先搜索获取链接列表，" +
                                "再用此工具抓取感兴趣的页面查看详情。" +
                                "内置 Jsoup 和 Playwright 双引擎，自动适配 SPA 和反爬页面。",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "url", Map.of(
                                                "type", "string",
                                                "description", "目标网页的完整 URL，必须以 http:// 或 https:// 开头"
                                        )
                                ),
                                "required", List.of("url")
                        )
                ))
                .handler(FetchPageTool::fetch)
                .emoji("📄")
                .build());
    }

    // =========================================================================
    // Quick test
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== FetchPageTool 网页抓取功能测试 ===\n");

        // Test 1: Server-rendered page → Jsoup handles it directly
        System.out.println(">>> 测试 1: 抓取百度首页 (Jsoup)");
        String result = fetch(Map.of("url", "https://www.baidu.com"));
        System.out.println(result);
        System.out.println();

        // Test 2: SPA page → Jsoup empty → Playwright fallback
        System.out.println(">>> 测试 2: SPA 页面 (React 官网 → Playwright 接管)");
        result = fetch(Map.of("url", "https://chatgpt.com/"));
        System.out.println(result);
        System.out.println();

        // Test 3: Simple reliable page → Jsoup handles it
        System.out.println(">>> 测试 3: 抓取 example.com (Jsoup)");
        result = fetch(Map.of("url", "https://www.example.com"));
        System.out.println(result);
    }
}
