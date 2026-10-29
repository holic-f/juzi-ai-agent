package com.yupi.juziaiagent.tools;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebScrapingToolTest {

    @Test
    void extractsMainTextFromHttpResponse() throws IOException {
        String html = "<html><head><title>Page title</title><script>secret()</script></head>"
                + "<body><header>Header</header><nav>Menu</nav><main><h1>Article title</h1>"
                + "<p>Useful body text.</p><aside>Advertisement</aside></main><footer>Footer</footer></body></html>";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            // 仅在此离线测试中注入本机地址许可，生产客户端始终禁止内网。
            SafeHttpClient http = new SafeHttpClient(java.net.InetAddress::getAllByName, address -> true, 5_000);
            String result = new WebScrapingTool(http).scrapeWebPage("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("Article title Useful body text.", result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultToolRejectsLocalUrl() {
        assertTrue(new WebScrapingTool().scrapeWebPage("http://127.0.0.1/")
                .startsWith("Error scraping web page:"));
    }

    @Test
    void preservesDeclaredCharsetAndRejectsOversizedPage() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            fixture.server.createContext("/chinese", exchange -> {
                byte[] body = "<html><body><main>中文正文</main></body></html>"
                        .getBytes(java.nio.charset.Charset.forName("GBK"));
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=GBK");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            fixture.server.createContext("/large", exchange -> {
                exchange.sendResponseHeaders(200, 2 * 1024 * 1024 + 1);
                exchange.close();
            });
            WebScrapingTool tool = new WebScrapingTool(fixture.client());
            assertEquals("中文正文", tool.scrapeWebPage(fixture.url("/chinese")));
            assertTrue(tool.scrapeWebPage(fixture.url("/large")).startsWith("Error scraping web page:"));
        }
    }

    @Test
    void fallsBackToBodyAndLimitsOutputWithoutSplittingUnicode() {
        String text = "文".repeat(10_000) + "😀";
        String result = WebScrapingTool.extractMainContent(Jsoup.parse(
                "<body><nav>Menu</nav><div>" + text + "</div></body>"));
        assertEquals("文".repeat(10_000) + "... [truncated]", result);
        assertFalse(result.contains("Menu"));
        assertFalse(result.contains("😀"));
    }

    @Test
    void skipsEmptyMainCandidate() {
        String result = WebScrapingTool.extractMainContent(Jsoup.parse(
                "<body><main></main><article>Article text</article><div>Unrelated text</div></body>"));
        assertEquals("Article text", result);
    }

    @Test
    void returnsEmptyTextWhenPageHasNoBodyText() {
        String result = WebScrapingTool.extractMainContent(Jsoup.parse("<script>secret()</script>"));
        assertTrue(result.isEmpty());
    }
}
