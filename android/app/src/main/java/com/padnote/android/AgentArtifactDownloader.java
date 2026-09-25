package com.padnote.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;

import javax.net.ssl.HttpsURLConnection;

/** Streams one task-owned Bridge artifact to a verified temporary file. */
final class AgentArtifactDownloader {
    private static final long MAX_BYTES = 100L * 1024L * 1024L;

    interface Source {
        OpenResponse open(URL url, String bearer) throws Exception;
    }

    static final class OpenResponse implements AutoCloseable {
        final int status;
        final URL requestedUrl;
        final URL finalUrl;
        final long contentLength;
        final InputStream input;
        private final Runnable closeAction;

        OpenResponse(int status, URL requestedUrl, URL finalUrl, long contentLength,
                     InputStream input, Runnable closeAction) {
            this.status = status;
            this.requestedUrl = requestedUrl;
            this.finalUrl = finalUrl;
            this.contentLength = contentLength;
            this.input = input;
            this.closeAction = closeAction == null ? () -> { } : closeAction;
        }

        @Override public void close() throws Exception {
            try { if (input != null) input.close(); }
            finally { closeAction.run(); }
        }
    }

    private final Source source;

    AgentArtifactDownloader() { this(productionSource()); }
    AgentArtifactDownloader(Source source) { this.source = source; }

    File download(AgentTaskStore.Task task, AgentConnectionStore.Config connection,
                  AgentTaskStore.Artifact artifact, File cacheDirectory) throws Exception {
        if (task == null || connection == null || artifact == null || !task.matches(connection)) {
            throw new IllegalStateException("任务绑定的连接身份已变更，不能下载产物");
        }
        if (task.transport != AgentConnectionStore.Transport.BRIDGE ||
                !connection.capabilities.contains("artifacts")) {
            throw new IllegalStateException("该连接未验证产物下载能力");
        }
        boolean listed = false;
        for (AgentTaskStore.Artifact candidate : task.artifacts) {
            if (candidate.id.equals(artifact.id) && candidate.sizeBytes == artifact.sizeBytes &&
                    candidate.sha256.equalsIgnoreCase(artifact.sha256)) { listed = true; break; }
        }
        if (!listed || artifact.sizeBytes < 0L || artifact.sizeBytes > MAX_BYTES ||
                !artifact.sha256.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("产物元数据无效或已变化");
        }
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建临时下载目录");
        }
        File temporary = File.createTempFile("agent-artifact-", ".part", cacheDirectory);
        boolean complete = false;
        try {
            URL url = new AgentTaskClient().artifactUrl(task, artifact.id);
            try (OpenResponse response = source.open(url, connection.token)) {
                if (response.status != 200) throw new IllegalStateException(
                        "下载产物失败（HTTP " + response.status + "）");
                if (!response.requestedUrl.toExternalForm().equals(response.finalUrl.toExternalForm())) {
                    throw new IllegalStateException("产物下载拒绝重定向");
                }
                if (response.contentLength >= 0L && response.contentLength != artifact.sizeBytes) {
                    throw new IllegalStateException("产物长度与任务清单不一致");
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0L;
                byte[] buffer = new byte[64 * 1024];
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    int count;
                    while ((count = response.input.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        total += count;
                        if (total > artifact.sizeBytes || total > MAX_BYTES) {
                            throw new IllegalStateException("产物超过声明大小");
                        }
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                    output.flush(); output.getFD().sync();
                }
                if (total != artifact.sizeBytes) throw new IllegalStateException("产物下载不完整");
                String actual = hex(digest.digest());
                if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        artifact.sha256.toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                    throw new IllegalStateException("产物校验失败");
                }
            }
            complete = true;
            return temporary;
        } finally {
            if (!complete) temporary.delete();
        }
    }

    private static Source productionSource() {
        return (url, bearer) -> {
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/octet-stream");
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
                int status = connection.getResponseCode();
                InputStream input = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                return new OpenResponse(status, url, connection.getURL(),
                        connection.getContentLengthLong(), input, connection::disconnect);
            } catch (Exception error) {
                connection.disconnect();
                throw error;
            }
        };
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
