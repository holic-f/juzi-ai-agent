package com.yupi.juziimagesearchmcp.tools;


import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ImageSearchTool {

    private static final String PIXABAY_API_URL = "https://pixabay.com/api/";
    private static final String SEARCHAPI_URL = "https://www.searchapi.io/api/v1/search";
    private static final String OPENVERSE_API_URL = "https://api.openverse.org/v1/images/";
    // baidu_images 的结果在国内可直接访问；若该引擎解析不到结果，可改为 google_images
    private static final String SEARCHAPI_IMAGE_ENGINE = "baidu_images";
    // 工具在 Agent 步骤中同步执行，超时不能过长
    private static final int HTTP_TIMEOUT_MS = 10000;

    @Value("${pixabay.api-key:}")
    private String pixabayApiKey;

    @Value("${searchapi.api-key:}")
    private String searchApiKey;

    @Tool(description = "search web images (auto-selects Pixabay / SearchAPI / Openverse by configured keys)")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        try {
            List<String> urls = selectImages(query);
            return urls.isEmpty() ? "No images found for: " + query : String.join(",", urls);
        } catch (Exception e) {
            return "Error search image: " + e.getMessage();
        }
    }

    /**
     * 按已配置的 Key 自动选择数据源：Pixabay（免费图库）优先，其次消耗 SearchAPI 额度，
     * 都未配置时走免 Key 的 Openverse（匿名限额约 200 次/天）。
     */
    private List<String> selectImages(String query) {
        if (StrUtil.isNotBlank(pixabayApiKey)) {
            return searchPixabayImages(query);
        }
        if (StrUtil.isNotBlank(searchApiKey)) {
            return searchSearchapiImages(query);
        }
        return searchOpenverseImages(query);
    }

    /**
     * Pixabay 图片搜索，返回 webformatURL（最长边 640px；原图 URL 需向 Pixabay 单独申请授权）
     */
    public List<String> searchPixabayImages(String query) {
        // Pixabay 的 key 走查询参数而非请求头；中文关键词由 form 自动 URL 编码
        Map<String, Object> params = new HashMap<>();
        params.put("key", pixabayApiKey);
        params.put("q", query);

        String response = HttpUtil.createGet(PIXABAY_API_URL)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();

        // Key 无效等异常时 Pixabay 返回纯文本（如 "[ERROR 400] Invalid or missing API key"），不是 JSON
        JSONObject result = parseJson(response, "Pixabay");
        if (result.getJSONArray("hits") == null) {
            throw new IllegalStateException("Pixabay 响应缺少 hits 字段: " + StrUtil.brief(response, 200));
        }
        return result.getJSONArray("hits")
                .stream()
                .map(hitObj -> (JSONObject) hitObj)
                .map(hit -> hit.getStr("webformatURL"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    /**
     * SearchAPI 图片搜索（engine 见 SEARCHAPI_IMAGE_ENGINE），每次调用消耗 SearchAPI 额度
     */
    public List<String> searchSearchapiImages(String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("engine", SEARCHAPI_IMAGE_ENGINE);
        params.put("q", query);
        params.put("api_key", searchApiKey);

        String response = HttpUtil.createGet(SEARCHAPI_URL)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();

        JSONObject result = parseJson(response, "SearchAPI");
        if (result.containsKey("error")) {
            throw new IllegalStateException("SearchAPI 错误: " + result.getStr("error"));
        }
        // 各图片引擎的数组名不统一（images_results / images），original 可能是对象或字符串
        JSONArray images = result.containsKey("images_results")
                ? result.getJSONArray("images_results")
                : result.getJSONArray("images");
        if (images == null) {
            throw new IllegalStateException("SearchAPI 响应缺少图片结果字段: " + StrUtil.brief(response, 200));
        }
        return images
                .stream()
                .map(itemObj -> (JSONObject) itemObj)
                .map(ImageSearchTool::extractImageUrl)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    /**
     * Openverse 图片搜索（开放版权素材），无需 Key；匿名请求有限流
     */
    public List<String> searchOpenverseImages(String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("q", query);

        String response = HttpUtil.createGet(OPENVERSE_API_URL)
                .form(params)
                .timeout(HTTP_TIMEOUT_MS)
                .execute()
                .body();

        JSONObject result = parseJson(response, "Openverse");
        if (result.getJSONArray("results") == null) {
            throw new IllegalStateException("Openverse 响应缺少 results 字段: " + StrUtil.brief(response, 200));
        }
        return result.getJSONArray("results")
                .stream()
                .map(itemObj -> (JSONObject) itemObj)
                .map(item -> item.getStr("url"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }

    private static JSONObject parseJson(String response, String provider) {
        try {
            return JSONUtil.parseObj(response);
        } catch (Exception e) {
            throw new IllegalStateException(provider + " 返回非 JSON 响应: " + StrUtil.brief(response, 200));
        }
    }

    private static String extractImageUrl(JSONObject item) {
        JSONObject original = item.getJSONObject("original");
        String url = original != null ? original.getStr("link") : item.getStr("original");
        return StrUtil.isNotBlank(url) ? url : item.getStr("thumbnail");
    }
}
