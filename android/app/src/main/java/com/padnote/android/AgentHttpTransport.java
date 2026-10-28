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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Small injectable HTTP boundary shared by Agent probes, pairing and tasks. */
interface AgentHttpTransport {
    interface Progress {
        default void onUploadStarted(long totalBytes) { }
        void onBytesWritten(long bytesWritten, long totalBytes);
        default void onWaitingForResponse(long totalBytes) { }
    }
    final class Request {
        final String method;
        final URL url;
        final String bearer;
        final Map<String, String> headers;
        final byte[] body;
        final int maximumResponseBytes;
        /** Pinned certificate SHA-256 for a same-network connection, or "". */
        final String certSha256;
        final Progress progress;
        final int totalTimeoutMillis;

        Request(String method, URL url, String bearer, Map<String, String> headers,
                byte[] body, int maximumResponseBytes) {
            this(method, url, bearer, headers, body, maximumResponseBytes, "");
        }

        Request(String method, URL url, String bearer, Map<String, String> headers,
                byte[] body, int maximumResponseBytes, String certSha256) {
            this(method,url,bearer,headers,body,maximumResponseBytes,certSha256,null,90000);
        }

        Request(String method, URL url, String bearer, Map<String, String> headers,
                byte[] body, int maximumResponseBytes, String certSha256, Progress progress,
                int totalTimeoutMillis) {
            this.method = method;
            this.url = url;
            this.bearer = bearer == null ? "" : bearer;
            this.headers = headers == null ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body == null ? new byte[0] : body.clone();
            this.maximumResponseBytes = maximumResponseBytes;
            this.certSha256 = certSha256 == null ? "" : certSha256;
            this.progress = progress;
            this.totalTimeoutMillis = Math.max(1000, totalTimeoutMillis);
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
            ScheduledFuture<?> deadline = DEADLINES.schedule(connection::disconnect,
                    request.totalTimeoutMillis, TimeUnit.MILLISECONDS);
            try {
                AgentCertificatePin.apply(connection, request.certSha256);
                connection.setConnectTimeout(Math.min(8000, request.totalTimeoutMillis));
                connection.setReadTimeout(Math.min(20000, request.totalTimeoutMillis));
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
                    connection.setFixedLengthStreamingMode((long) request.body.length);
                    if (!request.headers.containsKey("Content-Type")) {
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    }
                    try (OutputStream output = connection.getOutputStream()) {
                        if(request.progress!=null)request.progress.onUploadStarted(request.body.length);
                        int offset=0;
                        while(offset<request.body.length) {
                            int count=Math.min(32*1024,request.body.length-offset);
                            output.write(request.body,offset,count);
                            offset+=count;
                            if(request.progress!=null)request.progress.onBytesWritten(offset,request.body.length);
                        }
                        output.flush();
                    }
                    if(request.progress!=null)request.progress.onWaitingForResponse(request.body.length);
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
                deadline.cancel(false);
                connection.disconnect();
            }
        };
    }

    ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{
        Thread thread=new Thread(r,"padnote-agent-http-deadline");thread.setDaemon(true);return thread;
    });

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
