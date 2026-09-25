package com.padnote.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/** Small injectable HTTP boundary shared by Agent probes, pairing and tasks. */
interface AgentHttpTransport {
    final class Request {
        final String method;
        final URL url;
        final String bearer;
        final Map<String, String> headers;
        final byte[] body;
        final int maximumResponseBytes;

        Request(String method, URL url, String bearer, Map<String, String> headers,
                byte[] body, int maximumResponseBytes) {
            this.method = method;
            this.url = url;
            this.bearer = bearer == null ? "" : bearer;
            this.headers = headers == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body == null ? new byte[0] : body.clone();
            this.maximumResponseBytes = maximumResponseBytes;
        }
    }

    final class Response {
        final int status;
        final URL requestedUrl;
        final URL finalUrl;
        final byte[] body;
        final Map<String, List<String>> headers;

        Response(int status, URL requestedUrl, URL finalUrl, byte[] body,
                 Map<String, List<String>> headers) {
            this.status = status;
            this.requestedUrl = requestedUrl;
            this.finalUrl = finalUrl;
            this.body = body == null ? new byte[0] : body.clone();
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }

        String utf8() { return new String(body, StandardCharsets.UTF_8); }
    }

    Response execute(Request request) throws Exception;

    static AgentHttpTransport production() {
        return request -> {
            HttpsURLConnection connection = (HttpsURLConnection) request.url.openConnection();
            try {
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(request.method);
                connection.setRequestProperty("Accept", "application/json");
                if (!request.bearer.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + request.bearer);
                }
                for (Map.Entry<String, String> header : request.headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                if (request.body.length > 0 || "POST".equals(request.method)) {
                    connection.setDoOutput(true);
                    if (!request.headers.containsKey("Content-Type")) {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    }
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(request.body);
                        output.flush();
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                byte[] body;
                try (InputStream input = stream) {
                    body = readLimited(input, request.maximumResponseBytes);
                }
                return new Response(status, request.url, connection.getURL(), body,
                        connection.getHeaderFields());
            } finally {
                connection.disconnect();
            }
        };
    }

    static byte[] readLimited(InputStream input, int maximum) throws Exception {
        if (input == null) return new byte[0];
        int limit = Math.max(1, maximum);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > limit) throw new IllegalStateException("Agent 响应过大");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
