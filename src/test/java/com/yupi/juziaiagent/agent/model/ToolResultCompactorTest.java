package com.yupi.juziaiagent.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultCompactorTest {

    @Test
    void extractsHtmlMainTextAndRemovesPageChromeBeforeApplyingBudget() {
        String html = "<!doctype html><html><head><style>.secret{display:none}</style>"
                + "<script>trackingSecret()</script></head><body><header>HEADER_NOISE</header>"
                + "<nav>NAV_NOISE</nav><aside>SIDE_NOISE</aside><main><h1>正文标题</h1>"
                + "<p>正文开头 &amp; 简介</p><p>" + "资料内容".repeat(2000)
                + "</p><p>正文结尾：结论保留</p></main><footer>FOOTER_NOISE</footer></body></html>";

        String compact = ToolResultCompactor.compact("scrapeWebPage", html, 6000);

        assertTrue(compact.length() <= 6000);
        assertTrue(compact.startsWith("正文标题"));
        assertTrue(compact.contains("正文开头 & 简介"));
        assertTrue(compact.endsWith("正文结尾：结论保留"));
        assertTrue(compact.contains("截断"));
        assertTrue(compact.contains("不完整"));
        assertFalse(compact.contains("trackingSecret"));
        assertFalse(compact.contains("display:none"));
        assertFalse(compact.contains("_NOISE"));
        assertFalse(compact.contains("<p>"));
    }

    @Test
    void preservesParagraphBoundariesWhenExtractingHtml() {
        String html = "<article><h1>标题</h1><p>第一段</p><p>第二段<br>第三行</p></article>";
        String compact = ToolResultCompactor.compact("scrapeWebPage", html, 8000);
        assertTrue(compact.contains("标题"));
        assertTrue(compact.contains("第一段"));
        assertTrue(compact.contains("第二段"));
        assertTrue(compact.contains("第三行"));
        assertTrue(compact.contains("\n"));
        assertFalse(compact.contains("截断"));
    }

    @Test
    void keepsPlainTextLineBreaksAndPathsByteForByteWithinBudget() {
        String result = "生成成功\r\n路径：E:\\work space\\报告.pdf\n下载：/files/报告.pdf\n  保留缩进\n";
        assertEquals(result, ToolResultCompactor.compact("generatePDF", result, 6000));
        assertEquals(result, ToolResultCompactor.compact("scrapeWebPage", result, 6000));
    }

    @Test
    void preservesShortErrorsAndAngleBracketTextFromOtherTools() {
        String error = "Error scraping web page: timeout after 10000ms";
        assertEquals(error, ToolResultCompactor.compact("scrapeWebPage", error, 6000));
        assertEquals("<p>not a web result</p>",
                ToolResultCompactor.compact("readFile", "<p>not a web result</p>", 6000));
        assertEquals("value < limit\nC:\\a\\b.json",
                ToolResultCompactor.compact(null, "value < limit\nC:\\a\\b.json", 6000));
    }

    @Test
    void preservesCompleteJsonIncludingWhitespaceAndEmbeddedHtml() {
        String json = "{\n  \"html\": \"<main>内容</main>\",\n  \"file\": \"C:\\\\temp\\\\out.json\"\n}";
        assertEquals(json, ToolResultCompactor.compact("search", json, 8000));
        assertEquals(json, ToolResultCompactor.compact("scrapeWebPage", json, 8000));
    }

    @Test
    void keepsJsonArrayAndJsonStringContainingHtml() {
        String array = "[\"<p>one</p>\",\"<p>two</p>\"]";
        String string = "\"<main>JSON string value</main>\"";
        assertEquals(array, ToolResultCompactor.compact("scrapeWebPage", array, 6000));
        assertEquals(string, ToolResultCompactor.compact("scrapeWebPage", string, 6000));
    }

    @Test
    void htmlWithoutMainFallsBackToBodyAndCanBecomeEmptyAfterRemovingScripts() {
        assertEquals("正文", ToolResultCompactor.compact("scrapeWebPage",
                "<body><nav>导航</nav><div>正文</div></body>", 6000));
        assertEquals("", ToolResultCompactor.compact("scrapeWebPage",
                "<script>secret()</script><style>body{color:red}</style>", 6000));
    }

    @Test
    void truncatedJsonRetainsHeadAndTailButExplicitlyWarnsItIsIncomplete() {
        String json = "{\"items\":[\"FIRST\",\"" + "entry".repeat(1000) + "\",\"LAST\"],\"ok\":true}";
        String compact = ToolResultCompactor.compact("search", json, 160);
        assertTrue(compact.length() <= 160);
        assertTrue(compact.startsWith("{\"items\":[\"FIRST\""));
        assertTrue(compact.endsWith("\"LAST\"],\"ok\":true}"));
        assertTrue(compact.contains("截断"));
        assertTrue(compact.contains("不完整"));
        assertTrue(compact.contains("请勿假定完整"));
    }

    @Test
    void truncatesLongPlainTextWithoutReformattingRetainedHeadAndTail() {
        String result = "START\r\n  路径：C:\\资料\\report.pdf\n" + "middle\n".repeat(1000)
                + "\r\nEND\n下载地址：/download/report.pdf";
        String compact = ToolResultCompactor.compact("readFile", result, 200);
        assertTrue(compact.length() <= 200);
        assertTrue(compact.startsWith("START\r\n  路径：C:\\资料\\report.pdf\n"));
        assertTrue(compact.endsWith("\r\nEND\n下载地址：/download/report.pdf"));
        assertTrue(compact.contains("截断"));
    }

    @Test
    void handlesNullBlankEmptyAndZeroBudget() {
        assertEquals("", ToolResultCompactor.compact("search", null, 6000));
        assertEquals("", ToolResultCompactor.compact("search", "", 6000));
        assertEquals(" \r\n\t", ToolResultCompactor.compact("search", " \r\n\t", 6000));
        assertEquals("", ToolResultCompactor.compact("search", "anything", 0));
        assertThrows(IllegalArgumentException.class,
                () -> ToolResultCompactor.compact("search", "anything", -1));
    }

    @Test
    void exactLengthAndShortEmojiTextAreUnchanged() {
        String result = "开头😀结尾🌏";
        assertEquals(result, ToolResultCompactor.compact("search", result, result.length()));
        assertEquals("😀", ToolResultCompactor.compact("search", "😀", 2));
        assertEquals("A", ToolResultCompactor.compact("search", "A", 1));
    }

    @Test
    void everySmallBudgetIsBoundedAndNeverSplitsSurrogatePairs() {
        String result = "HEAD😀🌏" + "a😀中🌏".repeat(100) + "🌏😀TAIL";
        for (int maxChars = 1; maxChars <= 200; maxChars++) {
            String compact = ToolResultCompactor.compact("search", result, maxChars);
            assertTrue(compact.length() <= maxChars, "budget=" + maxChars);
            assertWellFormedSurrogates(compact);
            assertTrue(compact.contains("截"), "Even tiny budgets must mark truncation: " + maxChars);
        }
        String compact = ToolResultCompactor.compact("search", result, 100);
        assertTrue(compact.startsWith("HEAD😀🌏"));
        assertTrue(compact.endsWith("🌏😀TAIL"));
    }

    private static void assertWellFormedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)),
                        "Unpaired high surrogate at " + i);
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(current), "Unpaired low surrogate at " + i);
            }
        }
    }
}
