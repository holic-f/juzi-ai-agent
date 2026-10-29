package com.yupi.juziaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.apache.http.entity.ContentType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;

public class WebScrapingTool {

    private static final int MAX_CONTENT_LENGTH = 10_000;
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private final SafeHttpClient http;

    public WebScrapingTool() {
        this(new SafeHttpClient());
    }

    WebScrapingTool(SafeHttpClient http) {
        this.http = http;
    }

    @Tool(description = "Scrape the main text of a public HTTP/HTTPS web page (up to 2 MiB). Local/private addresses are forbidden.")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            SafeHttpClient.Response response = http.get(url, MAX_PAGE_BYTES);
            ContentType contentType = response.contentType() == null ? null : ContentType.parse(response.contentType());
            Charset charset = contentType == null ? null : contentType.getCharset();
            Document doc = Jsoup.parse(new ByteArrayInputStream(response.body()),
                    charset == null ? null : charset.name(), response.uri().toString());
            return extractMainContent(doc);
        } catch (IOException | RuntimeException e) {
            return "Error scraping web page: 抓取失败；仅支持公网 HTTP/HTTPS、最大 2 MiB，请检查地址或稍后重试。";
        }
    }

    static String extractMainContent(Document doc) {
        doc.select("script, style, noscript, template, nav, header, footer, aside, form, iframe, "
                + "[role=navigation], [role=banner], [role=contentinfo], [aria-hidden=true]").remove();
        Element content = null;
        for (Element candidate : doc.select("main, article, [role=main]")) {
            if (!candidate.text().isBlank()) {
                content = candidate;
                break;
            }
        }
        if (content == null) {
            content = doc.body();
        }
        String text = content == null ? "" : content.text();
        if (text.codePointCount(0, text.length()) <= MAX_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_CONTENT_LENGTH)) + "... [truncated]";
    }
}
