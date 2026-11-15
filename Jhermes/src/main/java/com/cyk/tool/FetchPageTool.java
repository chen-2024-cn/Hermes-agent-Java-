package com.cyk.tool;

import com.cyk.bean.ToolEntry;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Connection;
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

    /** Only sparse successful pages and HTTP 403 are eligible for browser fallback. */
    private record FetchAttempt(String result, String fallbackReason) { }

    public static String fetch(Map<String, Object> args) {
        Object url = args.get("url");
        if (!(url instanceof String urlStr) || urlStr.isBlank()) {
            return ToolRegistry.toolError("缺少必填参数或类型无效: url");
        }

        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            return ToolRegistry.toolError("无效的 URL: " + urlStr + " (必须以 http:// 或 https:// 开头)");
        }

        // P0-2 R13：回显抓取目标（与 [run #N] 同风格），便于用户及时发现外泄/误访问企图
        com.cyk.ui.Ui.out().println(com.cyk.util.ConsoleUi.dimPrefix() + "[fetch] " + urlStr);

        FetchAttempt jsoup = fetchWithJsoup(urlStr);
        if (jsoup.fallbackReason() == null) {
            return jsoup.result();
        }

        logger.info("尝试浏览器抓取: {} ({})", urlStr, jsoup.fallbackReason());
        String browserResult = fetchWithPlaywright(urlStr);
        return browserResult != null ? browserResult : jsoup.result();
    }

    // =========================================================================
    // Engine 1: Jsoup
    // =========================================================================

    private static FetchAttempt fetchWithJsoup(String urlStr) {
        try {
            Connection.Response response = Jsoup.connect(urlStr)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Referer", "https://www.baidu.com/")
                    .timeout(10000)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .execute();

            String finalUrl = response.url().toString();
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return new FetchAttempt(httpError(urlStr, finalUrl, status, "jsoup"),
                        status == 403 ? "HTTP 403, 尝试浏览器确认访问结果" : null);
            }
            if (unsupportedContentType(response.contentType())) {
                return new FetchAttempt(fetchError("不支持的文件类型: " + response.contentType(),
                        "unsupported_content_type", urlStr, finalUrl, status, "jsoup", false), null);
            }

            Document doc = response.parse();
            String bodyText = doc.body() != null ? doc.body().text() : doc.wholeText();
            String result = pageResult(urlStr, finalUrl, status, doc.title(), bodyText, "jsoup");
            return new FetchAttempt(result, bodyText.length() < 50 ? "正文较少, 检查动态内容" : null);

        } catch (java.net.MalformedURLException | IllegalArgumentException e) {
            return new FetchAttempt(fetchError("无效的 URL", "invalid_url",
                    urlStr, urlStr, 0, "jsoup", false), null);
        } catch (java.net.SocketTimeoutException e) {
            logger.warn("网页请求超时 (Jsoup): {}", urlStr);
            return new FetchAttempt(fetchError("请求超时; 可稍后有限重试, 不自动切换浏览器",
                    "timeout", urlStr, urlStr, 0, "jsoup", true), null);
        } catch (java.io.IOException e) {
            logger.warn("网页连接失败 (Jsoup): {} ({})", urlStr, e.getClass().getSimpleName());
            logger.debug("Jsoup connection details", e);
            return new FetchAttempt(fetchError("网络连接失败; 请检查地址和网络, 不要无限重试",
                    "network_error", urlStr, urlStr, 0, "jsoup", false), null);
        } catch (Exception e) {
            logger.error("Jsoup fetch failed for {}: {}", urlStr, e.getMessage(), e);
            return new FetchAttempt(ToolRegistry.toolError("抓取内部异常(Jsoup): "
                    + ToolRegistry.describeError(e)), null);
        }
    }

    // =========================================================================
    // Engine 2: Playwright
    // =========================================================================

    private static String fetchWithPlaywright(String urlStr) {
        Browser browser = getBrowser();
        if (browser == null) return null;

        try (BrowserContext context = browser.newContext(
                new Browser.NewContextOptions().setUserAgent(USER_AGENT).setLocale("zh-CN"))) {
            Page page = context.newPage();
            page.setDefaultTimeout(10000);
            Response response = page.navigate(urlStr, new Page.NavigateOptions()
                    .setTimeout(20000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            if (response == null) {
                return fetchError("浏览器未收到有效网页响应", "missing_response",
                        urlStr, page.url(), 0, "playwright", false);
            }

            int status = response.status();
            String finalUrl = response.url();
            if (status < 200 || status >= 300) {
                return httpError(urlStr, finalUrl, status, "playwright");
            }
            String contentType = response.headers().get("content-type");
            if (unsupportedContentType(contentType)) {
                return fetchError("不支持的文件类型: " + contentType, "unsupported_content_type",
                        urlStr, finalUrl, status, "playwright", false);
            }

            // Wait for rendered content, not network idle: analytics/SSE may never become idle.
            try {
                page.waitForFunction("() => document.body && document.body.innerText.trim().length >= 50",
                        null, new Page.WaitForFunctionOptions().setTimeout(5000));
            } catch (TimeoutError e) {
                // A genuinely short page is still valid; read its available body below.
            }
            String bodyText = (String) page.evaluate("() => document.body ? document.body.innerText : ''");
            if (bodyText == null || bodyText.isBlank()) return null;
            return pageResult(urlStr, finalUrl, status, page.title(), bodyText, "playwright");

        } catch (TimeoutError e) {
            logger.warn("网页请求超时 (浏览器): {}", urlStr);
            return fetchError("浏览器请求超时; 可稍后有限重试", "timeout",
                    urlStr, urlStr, 0, "playwright", true);
        } catch (PlaywrightException e) {
            logger.warn("浏览器抓取失败: {}", urlStr);
            logger.debug("Playwright connection details", e);
            return fetchError("浏览器抓取失败; 请检查网络或更换有效链接", "browser_error",
                    urlStr, urlStr, 0, "playwright", false);
        } catch (Exception e) {
            logger.error("Playwright fetch failed for {}: {}", urlStr, e.getMessage(), e);
            return ToolRegistry.toolError("抓取内部异常(Playwright): " + ToolRegistry.describeError(e));
        }
    }

    private static boolean unsupportedContentType(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase(java.util.Locale.ROOT);
        return ct.contains("image/") || ct.contains("video/") || ct.contains("audio/")
                || ct.contains("application/pdf");
    }

    private static String pageResult(String url, String finalUrl, int status,
                                     String title, String text, String engine) {
        boolean truncated = text.length() > MAX_TEXT_LENGTH;
        return ToolRegistry.toolResult(Map.of(
                "url", url, "final_url", finalUrl, "status_code", status,
                "title", title != null ? title : "",
                "text", truncated ? text.substring(0, MAX_TEXT_LENGTH) : text,
                "truncated", truncated, "engine", engine));
    }

    private static String httpError(String url, String finalUrl, int status, String engine) {
        String reason = switch (status) {
            case 404 -> "页面不存在; 请从搜索结果或文档首页查找有效链接, 不要重复抓取同一地址";
            case 410 -> "页面已移除; 请查找新的文档地址, 不要重复抓取同一地址";
            case 401 -> "需要登录或授权; 不自动重试";
            case 403 -> "服务器拒绝访问; 浏览器也无法访问时请更换有效来源";
            case 429 -> "请求过于频繁; 请等待后有限重试, 不要立即反复请求";
            default -> status >= 500 ? "服务器暂时异常; 可稍后有限重试"
                    : "网页响应无效; 请检查链接, 不要无限重试";
        };
        logger.warn("网页抓取失败: HTTP {} ({}) [{}]", status, finalUrl, engine);
        return fetchError("HTTP " + status + ": " + reason, "http_error",
                url, finalUrl, status, engine, status == 429 || status >= 500);
    }

    private static String fetchError(String message, String code, String url, String finalUrl,
                                     int status, String engine, boolean retryable) {
        return ToolRegistry.toolResult(Map.of("error", message, "error_code", code,
                "url", url, "final_url", finalUrl, "status_code", status,
                "engine", engine, "retryable", retryable));
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
                                "动态页面可回退浏览器；404/410 表示链接无效，请查找有效链接，不要重复请求。",
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
