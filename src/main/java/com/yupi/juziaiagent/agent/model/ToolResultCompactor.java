package com.yupi.juziaiagent.agent.model;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.regex.Pattern;

/**
 * 在工具结果进入模型上下文前，进行本地正文提取和有损长度限制，不发起模型请求。
 */
public final class ToolResultCompactor {

    private static final String TRUNCATION_NOTICE = "\n\n[工具结果已截断，内容不完整；请勿假定完整。]\n\n";
    private static final String SHORT_NOTICE = "[已截断，不完整]";
    private static final Pattern HTML_TAG = Pattern.compile(
            "(?is)<!doctype\\s+html\\b|</?(?:html|head|body|main|article|section|div|p|h[1-6]|"
                    + "table|tr|td|ul|ol|li|br|script|style|nav|header|footer|aside)\\b[^>]*>");

    private ToolResultCompactor() {
    }

    /**
     * 精简工具结果。只有 scrapeWebPage 的 HTML 会提取正文；普通文本和 JSON 在未超限时原样返回。
     *
     * <p>maxChars 是 Java String.length() 的上限（UTF-16 单元），并非 token 数或 Unicode 码点数。
     * 截断不会拆开有效的 surrogate pair。正常预算下保留头尾，中间插入明确的不完整警示；
     * 若预算连完整警示和头尾都放不下，则只返回缩短的警示。预算为 1 时仅返回“截”，为 0 时返回空串。
     * 截断后的 JSON 不保证可解析；截断内容不可当作完整工具结果。
     *
     * @param toolName 工具名称，可为 null
     * @param result 工具返回值，null 视为空串
     * @param maxChars 非负 UTF-16 字符预算，调用者通常使用 6000 或 8000
     * @return 不超过 maxChars 的文本
     * @throws IllegalArgumentException maxChars 为负数
     */
    public static String compact(String toolName, String result, int maxChars) {
        if (maxChars < 0) {
            throw new IllegalArgumentException("maxChars must not be negative");
        }
        if (result == null || result.isEmpty() || maxChars == 0) {
            return "";
        }
        String text = result;
        String leading = result.stripLeading();
        // JSON 中可以嵌入 HTML；不能因此把结构化结果误当网页解析。
        boolean structured = leading.startsWith("{") || leading.startsWith("[") || leading.startsWith("\"");
        if ("scrapeWebPage".equals(toolName) && !structured && HTML_TAG.matcher(result).find()) {
            text = extractHtmlText(result);
        }
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars < TRUNCATION_NOTICE.length() + 2) {
            return maxChars >= SHORT_NOTICE.length() ? SHORT_NOTICE : "截断".substring(0, Math.min(2, maxChars));
        }
        int remaining = maxChars - TRUNCATION_NOTICE.length();
        int headEnd = safePrefixEnd(text, (remaining + 1) / 2);
        int tailStart = safeSuffixStart(text, remaining / 2);
        return text.substring(0, headEnd) + TRUNCATION_NOTICE + text.substring(tailStart);
    }

    private static String extractHtmlText(String html) {
        Element body = Jsoup.parse(html).body();
        body.select("script, style, noscript, template, nav, header, footer, aside, form, iframe, "
                + "svg, canvas, [role=navigation], [role=banner], [role=contentinfo], [hidden], [aria-hidden=true]")
                .remove();
        Element content = body;
        for (Element candidate : body.select("main, article, [role=main]")) {
            if (!candidate.text().isBlank()) {
                content = candidate;
                break;
            }
        }
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode textNode) {
                    text.append(textNode.getWholeText());
                } else if (node instanceof Element element && (element.isBlock() || element.normalName().equals("br"))) {
                    text.append('\n');
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element && element.isBlock()) {
                    text.append('\n');
                }
            }
        }, content);
        return text.toString().replace('\u00a0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static int safePrefixEnd(String text, int limit) {
        if (limit > 0 && Character.isHighSurrogate(text.charAt(limit - 1))
                && limit < text.length() && Character.isLowSurrogate(text.charAt(limit))) {
            return limit - 1;
        }
        return limit;
    }

    private static int safeSuffixStart(String text, int length) {
        int start = text.length() - length;
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) {
            return start + 1;
        }
        return start;
    }
}
