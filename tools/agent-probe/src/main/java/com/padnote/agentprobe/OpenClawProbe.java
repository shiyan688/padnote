package com.padnote.agentprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class OpenClawProbe {
    static final int PROTOCOL_VERSION = 4;
    private static final String CLIENT_ID = "openclaw-probe";
    private static final String CLIENT_MODE = "probe";
    private static final String ROLE = "operator";
    private static final String PLATFORM = "linux";
    private static final JSONArray SCOPES = new JSONArray().put("operator.read");
    private static final long TIMEOUT_SECONDS = 10;

    private final URI endpoint;
    private final String token;
    private final Path stateDirectory;

    OpenClawProbe(URI endpoint, String token, Path stateDirectory) {
        this.endpoint = EndpointPolicy.validate(endpoint, Set.of("ws", "wss"), !token.isBlank());
        this.token = token;
        this.stateDirectory = stateDirectory;
    }

    JSONObject probe() throws Exception {
        Identity identity = Identity.loadOrCreate(stateDirectory.resolve("openclaw-device.json"));
        QueueListener listener = new QueueListener();
        WebSocket socket = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .buildAsync(endpoint, listener)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        JSONObject challenge = listener.nextJson();
        if (!"event".equals(challenge.getString("type"))
                || !"connect.challenge".equals(challenge.getString("event"))) {
            throw new IllegalStateException("OpenClaw 首帧不是 connect.challenge");
        }
        JSONObject challengePayload = challenge.getJSONObject("payload");
        String nonce = challengePayload.getString("nonce");
        long signedAt = challengePayload.getLong("ts");
        if (nonce.isBlank() || signedAt < 0) {
            throw new IllegalStateException("OpenClaw challenge 缺少有效 nonce/ts");
        }

        String connectId = UUID.randomUUID().toString();
        socket.sendText(buildConnectRequest(connectId, nonce, signedAt, identity, token).toString(), true)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        JSONObject hello = listener.nextResponse(connectId);
        if (!hello.getBoolean("ok")) {
            socket.abort();
            return connectFailure(hello.getJSONObject("error"), identity.deviceId);
        }

        JSONObject helloPayload = hello.getJSONObject("payload");
        if (!"hello-ok".equals(helloPayload.getString("type"))
                || helloPayload.getInt("protocol") != PROTOCOL_VERSION) {
            throw new IllegalStateException("OpenClaw hello-ok 协议版本不兼容");
        }

        String healthId = UUID.randomUUID().toString();
        JSONObject healthRequest = new JSONObject()
                .put("type", "req")
                .put("id", healthId)
                .put("method", "health")
                .put("params", new JSONObject());
        socket.sendText(healthRequest.toString(), true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        JSONObject health = listener.nextResponse(healthId);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete");
        if (!health.optBoolean("ok", false)) {
            throw new IllegalStateException("OpenClaw health RPC 未通过 operator.read 验证");
        }

        JSONObject server = helloPayload.getJSONObject("server");
        JSONObject features = helloPayload.getJSONObject("features");
        return new JSONObject()
                .put("backend", "openclaw")
                .put("ready", true)
                .put("protocol", PROTOCOL_VERSION)
                .put("serverVersion", server.optString("version", ""))
                .put("deviceId", identity.deviceId)
                .put("methods", features.getJSONArray("methods"));
    }

    private static JSONObject connectFailure(JSONObject error, String deviceId) {
        JSONObject details = error.optJSONObject("details");
        String detailCode = details == null ? "" : details.optString("code", "");
        String status = "PAIRING_REQUIRED".equals(error.optString("code"))
                || "PAIRING_REQUIRED".equals(detailCode) ? "pairing_required" : "rejected";
        JSONObject result = new JSONObject()
                .put("backend", "openclaw")
                .put("ready", false)
                .put("status", status)
                .put("deviceId", deviceId)
                .put("code", error.optString("code", ""))
                .put("detailCode", detailCode)
                .put("message", error.optString("message", ""));
        if (details != null && details.has("requestId")) {
            result.put("requestId", details.get("requestId"));
        }
        return result;
    }

    static JSONObject buildConnectRequest(String requestId, String nonce, long signedAt,
                                          Identity identity, String token) throws Exception {
        String signaturePayload = buildSignaturePayload(identity.deviceId, signedAt, token, nonce);
        JSONObject params = new JSONObject()
                .put("minProtocol", PROTOCOL_VERSION)
                .put("maxProtocol", PROTOCOL_VERSION)
                .put("client", new JSONObject()
                        .put("id", CLIENT_ID)
                        .put("displayName", "PadNote Agent Probe")
                        .put("version", "0.1.0")
                        .put("platform", PLATFORM)
                        .put("mode", CLIENT_MODE))
                .put("role", ROLE)
                .put("scopes", SCOPES)
                .put("caps", new JSONArray())
                .put("commands", new JSONArray())
                .put("permissions", new JSONObject())
                .put("locale", "zh-CN")
                .put("userAgent", "padnote-agent-probe/0.1")
                .put("device", new JSONObject()
                        .put("id", identity.deviceId)
                        .put("publicKey", identity.publicKey)
                        .put("signature", identity.sign(signaturePayload))
                        .put("signedAt", signedAt)
                        .put("nonce", nonce));
        if (!token.isBlank()) {
            params.put("auth", new JSONObject().put("token", token));
        }
        return new JSONObject()
                .put("type", "req")
                .put("id", requestId)
                .put("method", "connect")
                .put("params", params);
    }

    static String buildSignaturePayload(String deviceId, long signedAt, String token, String nonce) {
        return String.join("|", "v3", deviceId, CLIENT_ID, CLIENT_MODE, ROLE,
                "operator.read", Long.toString(signedAt), token, nonce, PLATFORM, "");
    }

    static final class Identity {
        final String deviceId;
        final String publicKey;
        private final PrivateKey privateKey;

        private Identity(String deviceId, String publicKey, PrivateKey privateKey) {
            this.deviceId = deviceId;
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }

        static Identity loadOrCreate(Path path) throws Exception {
            if (Files.exists(path)) {
                JSONObject stored = new JSONObject(Files.readString(path));
                String publicKey = stored.getString("publicKey");
                byte[] privateBytes = decode(stored.getString("privateKeyPkcs8"));
                PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                        .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                String deviceId = deviceId(decode(publicKey));
                if (!deviceId.equals(stored.getString("deviceId"))) {
                    throw new IllegalStateException("OpenClaw 设备身份文件不一致");
                }
                return new Identity(deviceId, publicKey, privateKey);
            }

            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] encodedPublicKey = keyPair.getPublic().getEncoded();
            byte[] rawPublicKey = Arrays.copyOfRange(encodedPublicKey,
                    encodedPublicKey.length - 32, encodedPublicKey.length);
            String publicKey = encode(rawPublicKey);
            String deviceId = deviceId(rawPublicKey);
            Files.createDirectories(path.getParent());
            JSONObject stored = new JSONObject()
                    .put("deviceId", deviceId)
                    .put("publicKey", publicKey)
                    .put("privateKeyPkcs8", encode(keyPair.getPrivate().getEncoded()));
            Files.writeString(path, stored.toString(2), StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(path, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            return new Identity(deviceId, publicKey, keyPair.getPrivate());
        }

        String sign(String payload) throws Exception {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payload.getBytes(StandardCharsets.UTF_8));
            return encode(signer.sign());
        }

        private static String deviceId(byte[] rawPublicKey) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawPublicKey);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        }

        private static String encode(byte[] value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }

        private static byte[] decode(String value) {
            return Base64.getUrlDecoder().decode(value);
        }
    }

    private static final class QueueListener implements WebSocket.Listener {
        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
                                                               CharSequence data,
                                                               boolean last) {
            partial.append(data);
            if (last) {
                events.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onPing(WebSocket webSocket,
                                                               ByteBuffer message) {
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            events.add(error);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket,
                                                                int statusCode,
                                                                String reason) {
            events.add(new IllegalStateException(
                    "OpenClaw WebSocket 已关闭：" + statusCode + " " + reason));
            return null;
        }

        JSONObject nextResponse(String requestId) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                JSONObject frame = nextJson(remaining);
                if ("event".equals(frame.optString("type"))) {
                    continue;
                }
                if (!"res".equals(frame.optString("type"))
                        || !requestId.equals(frame.optString("id"))) {
                    throw new IllegalStateException("OpenClaw 响应帧与当前请求不匹配");
                }
                return frame;
            }
            throw new IllegalStateException("等待 OpenClaw 请求响应超时");
        }

        JSONObject nextJson() throws Exception {
            return nextJson(TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS));
        }

        private JSONObject nextJson(long timeoutNanos) throws Exception {
            Object event = events.poll(timeoutNanos, TimeUnit.NANOSECONDS);
            if (event == null) {
                throw new IllegalStateException("等待 OpenClaw 响应超时");
            }
            if (event instanceof Exception) {
                throw (Exception) event;
            }
            if (event instanceof Throwable) {
                throw new IllegalStateException("OpenClaw WebSocket 失败", (Throwable) event);
            }
            return new JSONObject((String) event);
        }
    }
}
