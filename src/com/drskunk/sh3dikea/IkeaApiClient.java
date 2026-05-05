package com.drskunk.sh3dikea;

import com.drskunk.sh3dikea.json.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the same public IKEA endpoints as the Blender ikea-browser add-on.
 *
 * Endpoints:
 *   search:  https://sik.search.blue.cdtapps.com/{country}/{language}/search-result-page
 *   exists:  https://web-api.ikea.com/{country}/{language}/rotera/data/exists/{itemNo}/
 *   model:   https://web-api.ikea.com/{country}/{language}/rotera/data/model/{itemNo}/
 *   pip:     https://www.ikea.com/{country}/{language}/products/{itemNo[5:]}/{itemNo}.json
 *
 * The web-api.ikea.com endpoints require an X-Client-Id header. The value
 * below was reverse-engineered from ikea.com's JS by the Blender add-on
 * author and works for unauthenticated reads.
 */
public final class IkeaApiClient {

    private static final String CLIENT_ID = "4863e7d2-1428-4324-890b-ae5dede24fc6";
    private static final String USER_AGENT =
            "Sweet Home 3D IKEA Browser ( https://github.com/drskunk/sh3d-ikea-models/ )";
    private static final int TIMEOUT_MS = 15000;

    private final String country;
    private final String language;

    public IkeaApiClient(String country, String language) {
        this.country = country;
        this.language = language;
    }

    public List<IkeaProduct> search(String query) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("types", "PRODUCT");
        params.put("q", query);
        params.put("c", "sr");
        params.put("v", "20210322");
        if (IkeaProduct.isItemNo(query)) {
            params.put("size", "1");
        } else {
            params.put("size", "24");
            params.put("autocorrect", "true");
            params.put("subcategories-style", "tree-navigation");
        }

        String url = "https://sik.search.blue.cdtapps.com/" + country + "/" + language + "/search-result-page";
        byte[] data = httpGet(url, params, false);
        Map<String, Object> root = Json.asObject(Json.parse(new String(data, StandardCharsets.UTF_8)));

        List<IkeaProduct> out = new ArrayList<>();
        Map<String, Object> page = Json.asObject(root.get("searchResultPage"));
        if (page == null) return out;
        Map<String, Object> products = Json.asObject(page.get("products"));
        if (products == null) return out;
        Map<String, Object> main = Json.asObject(products.get("main"));
        if (main == null) return out;
        List<Object> items = Json.asArray(main.get("items"));
        if (items == null) return out;

        for (Object item : items) {
            Map<String, Object> i = Json.asObject(item);
            if (i == null) continue;
            Map<String, Object> p = Json.asObject(i.get("product"));
            if (p == null) continue;

            String itemNo = Json.asString(p.get("itemNo"));
            String mainImageUrl = Json.asString(p.get("mainImageUrl"));
            String pipUrl = Json.asString(p.get("pipUrl"));
            if (itemNo == null || mainImageUrl == null || pipUrl == null) continue;

            out.add(new IkeaProduct(
                    itemNo,
                    Json.asString(p.get("name")),
                    Json.asString(p.get("typeName")),
                    mainImageUrl,
                    Json.asString(p.get("mainImageAlt")),
                    pipUrl));
        }
        return out;
    }

    /** Returns true if a 3D model is available for the given item. */
    public boolean modelExists(String itemNo) throws IOException {
        String url = "https://web-api.ikea.com/" + country + "/" + language
                + "/rotera/data/exists/" + itemNo + "/";
        byte[] data = httpGet(url, null, true);
        Map<String, Object> root = Json.asObject(Json.parse(new String(data, StandardCharsets.UTF_8)));
        return Json.asBool(root.get("exists"), false);
    }

    /** Model URL plus measurements served by the rotera /data/model/ endpoint. */
    public static final class ModelInfo {
        public final String modelUrl;
        public final String productName;
        /** Width / depth / height in millimetres if reported by IKEA, else null. */
        public final Float widthMm;
        public final Float depthMm;
        public final Float heightMm;

        public ModelInfo(String modelUrl, String productName,
                         Float widthMm, Float depthMm, Float heightMm) {
            this.modelUrl = modelUrl;
            this.productName = productName;
            this.widthMm = widthMm;
            this.depthMm = depthMm;
            this.heightMm = heightMm;
        }
    }

    /** Fetches the GLB URL plus the official IKEA measurements (in mm). */
    public ModelInfo getModelInfo(String itemNo) throws IOException {
        String url = "https://web-api.ikea.com/" + country + "/" + language
                + "/rotera/data/model/" + itemNo + "/";
        byte[] data = httpGet(url, null, true);
        if (data.length == 0) {
            throw new IOException("Empty response from IKEA model endpoint for " + itemNo);
        }
        Map<String, Object> root = Json.asObject(Json.parse(new String(data, StandardCharsets.UTF_8)));
        String modelUrl = Json.asString(root.get("modelUrl"));
        if (modelUrl == null) {
            throw new IOException("No modelUrl in IKEA response for " + itemNo);
        }
        String productName = Json.asString(root.get("productName"));
        Float widthMm = null, depthMm = null, heightMm = null;
        java.util.List<Object> measurements = Json.asArray(root.get("measurements"));
        if (measurements != null) {
            for (Object o : measurements) {
                Map<String, Object> m = Json.asObject(o);
                if (m == null) continue;
                String type = Json.asString(m.get("measurementType"));
                if (type == null) continue;
                float value = (float) Json.asDouble(m.get("value"), 0);
                if (value <= 0) continue;
                // Some products report multiple variants (e.g. "seat width"
                // and "width"). Only the unqualified terms describe the
                // overall outer envelope used for floorplanning.
                switch (type) {
                    case "width":  widthMm = value; break;
                    case "depth":  depthMm = value; break;
                    case "height": heightMm = value; break;
                    default: /* ignore the rest */
                }
            }
        }
        return new ModelInfo(modelUrl, productName, widthMm, depthMm, heightMm);
    }

    public byte[] download(String url) throws IOException {
        return httpGet(url, null, false);
    }

    private byte[] httpGet(String url, Map<String, String> params, boolean withClientId) throws IOException {
        if (params != null && !params.isEmpty()) {
            StringBuilder qs = new StringBuilder(url.contains("?") ? "&" : "?");
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) qs.append('&');
                first = false;
                qs.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                  .append('=')
                  .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            url += qs;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        if (withClientId) {
            conn.setRequestProperty("X-Client-Id", CLIENT_ID);
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " " + conn.getResponseMessage() + " for " + url);
        }

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
}
