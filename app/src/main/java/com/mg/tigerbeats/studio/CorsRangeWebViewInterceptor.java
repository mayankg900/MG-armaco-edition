package com.mg.tigerbeats.studio;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class CorsRangeWebViewInterceptor {
    @Nullable
    public static WebResourceResponse handleProxyRequest(WebResourceRequest request) {
        try {
            String urlStr = request.getUrl().toString();
            if (urlStr.startsWith("https://appassets.androidplatform.net/proxy/")) {
                String targetUrl = urlStr.substring("https://appassets.androidplatform.net/proxy/".length());
                URL url = new URL(targetUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(request.getMethod());
                for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.connect();

                Map<String, String> responseHeaders = new HashMap<>();
                for (Map.Entry<String, java.util.List<String>> header : conn.getHeaderFields().entrySet()) {
                    if (header.getKey() != null && !header.getValue().isEmpty()) {
                        responseHeaders.put(header.getKey(), header.getValue().get(0));
                    }
                }
                responseHeaders.put("Access-Control-Allow-Origin", "*");
                responseHeaders.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD");
                responseHeaders.put("Access-Control-Allow-Headers", "*");

                int responseCode = conn.getResponseCode();
                String contentType = conn.getContentType();
                String encoding = conn.getContentEncoding();
                java.io.InputStream stream = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();

                return new WebResourceResponse(
                        contentType != null ? contentType : "application/octet-stream",
                        encoding != null ? encoding : "utf-8",
                        responseCode,
                        conn.getResponseMessage() != null ? conn.getResponseMessage() : "OK",
                        responseHeaders,
                        stream
                );
            }
        } catch (Exception ignored) {}
        return null;
    }
}
