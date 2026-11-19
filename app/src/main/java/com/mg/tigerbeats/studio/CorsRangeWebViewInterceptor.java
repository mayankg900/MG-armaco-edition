package com.mg.tigerbeats.studio;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Narrow CORS compatibility adapter for public, read-only endpoints used by the studio.
 * This is intentionally not an arbitrary URL proxy: only HTTPS GET/HEAD requests to
 * explicitly listed public API hosts are accepted. It does not forward cookies, auth,
 * arbitrary headers, or follow redirects.
 */
public final class CorsRangeWebViewInterceptor {
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String PROXY_PREFIX = APP_ORIGIN + "/proxy/";
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "suggestqueries.google.com",
            "pipedapi.kavin.rocks"
    ));
    private static final Set<String> FORWARDED_REQUEST_HEADERS = new HashSet<>(Arrays.asList(
            "accept", "accept-language", "range", "if-none-match", "if-modified-since"
    ));
    private static final Set<String> EXPOSED_RESPONSE_HEADERS = new HashSet<>(Arrays.asList(
            "accept-ranges", "cache-control", "content-disposition", "content-length",
            "content-range", "content-type", "etag", "last-modified"
    ));

    private CorsRangeWebViewInterceptor() { }

    @Nullable
    public static WebResourceResponse handleProxyRequest(WebResourceRequest request) {
        final Uri proxyUri = request.getUrl();
        final String proxyUrl = proxyUri.toString();
        if (!proxyUrl.startsWith(PROXY_PREFIX)) return null;

        HttpURLConnection connection = null;
        try {
            String method = request.getMethod() == null ? "GET" : request.getMethod().toUpperCase(Locale.ROOT);
            if ("OPTIONS".equals(method)) return preflightResponse();
            if (!"GET".equals(method) && !"HEAD".equals(method)) return errorResponse(405, "Only GET and HEAD are supported");

            String target = proxyUrl.substring(PROXY_PREFIX.length());
            URL targetUrl = new URL(target);
            String host = targetUrl.getHost() == null ? "" : targetUrl.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(targetUrl.getProtocol()) || targetUrl.getPort() != -1 && targetUrl.getPort() != 443
                    || targetUrl.getUserInfo() != null || !ALLOWED_HOSTS.contains(host)) {
                return errorResponse(403, "Proxy target is not an allowed public API origin");
            }

            connection = (HttpURLConnection) targetUrl.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", "TigerBeatsStudio/1.0 (Android WebView)");
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                String key = header.getKey().toLowerCase(Locale.ROOT);
                if (FORWARDED_REQUEST_HEADERS.contains(key)) connection.setRequestProperty(header.getKey(), header.getValue());
            }

            int code = connection.getResponseCode();
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", APP_ORIGIN);
            headers.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Accept, Accept-Language, Range, If-None-Match, If-Modified-Since");
            headers.put("Access-Control-Expose-Headers", "Accept-Ranges, Content-Length, Content-Range, Content-Type, ETag, Last-Modified");
            headers.put("Vary", "Origin");
            for (Map.Entry<String, java.util.List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() == null || header.getValue() == null || header.getValue().isEmpty()) continue;
                if (EXPOSED_RESPONSE_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                    headers.put(header.getKey(), header.getValue().get(0));
                }
            }

            InputStream body = null;
            if (!"HEAD".equals(method) && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                body = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            }
            final HttpURLConnection activeConnection = connection;
            InputStream responseBody = body == null ? new ByteArrayInputStream(new byte[0]) : new FilterInputStream(body) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { activeConnection.disconnect(); }
                }
            };
            String contentType = connection.getContentType();
            String mime = contentType == null ? "application/octet-stream" : contentType.split(";", 2)[0].trim();
            String charset = connection.getContentEncoding();
            if (charset == null || charset.isEmpty()) charset = "utf-8";
            String reason = code >= 200 && code < 300 ? "OK" : (connection.getResponseMessage() == null ? "HTTP response" : connection.getResponseMessage());
            return new WebResourceResponse(mime, charset, code, reason, headers, responseBody);
        } catch (Exception e) {
            if (connection != null) connection.disconnect();
            return errorResponse(502, "Allowed API request failed");
        }
    }

    private static WebResourceResponse preflightResponse() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", APP_ORIGIN);
        headers.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Accept, Accept-Language, Range, If-None-Match, If-Modified-Since");
        headers.put("Access-Control-Max-Age", "600");
        headers.put("Vary", "Origin");
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", headers, new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse errorResponse(int status, String message) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", APP_ORIGIN);
        headers.put("Vary", "Origin");
        return new WebResourceResponse("text/plain", "utf-8", status, message,
                headers, new ByteArrayInputStream(message.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
