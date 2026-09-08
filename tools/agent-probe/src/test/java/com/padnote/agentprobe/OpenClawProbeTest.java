package com.padnote.agentprobe;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OpenClawProbeTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void completesChallengeHelloAndHealthFlow() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            CompletableFuture<JSONObject> receivedConnect = new CompletableFuture<>();
            Thread fakeGateway = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    String headers = readHeaders(input);
                    String key = header(headers, "Sec-WebSocket-Key");
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                    .getBytes(StandardCharsets.US_ASCII)));
                    output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                            + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    sendText(output, new JSONObject()
                            .put("type", "event")
                            .put("event", "connect.challenge")
                            .put("payload", new JSONObject().put("nonce", "offline-nonce").put("ts", 1234))
                            .toString());
                    JSONObject connect = new JSONObject(readClientText(input));
                    receivedConnect.complete(connect);
                    sendText(output, new JSONObject()
                            .put("type", "res")
                            .put("id", connect.getString("id"))
                            .put("ok", true)
                            .put("payload", new JSONObject()
                                    .put("type", "hello-ok")
                                    .put("protocol", 4)
                                    .put("server", new JSONObject().put("version", "test").put("connId", "c1"))
                                    .put("features", new JSONObject()
                                            .put("methods", new org.json.JSONArray().put("health"))
                                            .put("events", new org.json.JSONArray()))
                                    .put("snapshot", new JSONObject())
                                    .put("auth", new JSONObject()
                                            .put("role", "operator")
                                            .put("scopes", new org.json.JSONArray().put("operator.read")))
                                    .put("policy", new JSONObject()
                                            .put("maxPayload", 1024)
                                            .put("maxBufferedBytes", 2048)
                                            .put("tickIntervalMs", 15000)))
                            .toString());
                    JSONObject health = new JSONObject(readClientText(input));
                    sendText(output, new JSONObject()
                            .put("type", "event")
                            .put("event", "tick")
                            .put("payload", new JSONObject())
                            .toString());
                    sendText(output, new JSONObject()
                            .put("type", "res")
                            .put("id", health.getString("id"))
                            .put("ok", true)
                            .put("payload", new JSONObject().put("status", "ok"))
                            .toString());
                } catch (Exception error) {
                    receivedConnect.completeExceptionally(error);
                }
            }, "fake-openclaw-gateway");
            fakeGateway.start();

            Path state = temporaryFolder.newFolder("state").toPath();
            JSONObject result = new OpenClawProbe(
                    URI.create("ws://127.0.0.1:" + server.getLocalPort()), "test-token", state).probe();
            JSONObject connect = receivedConnect.get(2, TimeUnit.SECONDS);
            fakeGateway.join(2_000);

            assertTrue(result.getBoolean("ready"));
            assertEquals(4, result.getInt("protocol"));
            assertEquals("connect", connect.getString("method"));
            assertEquals("operator.read",
                    connect.getJSONObject("params").getJSONArray("scopes").getString(0));
            JSONObject device = connect.getJSONObject("params").getJSONObject("device");
            byte[] rawPublicKey = Base64.getUrlDecoder().decode(device.getString("publicKey"));
            byte[] spkiPrefix = hex("302a300506032b6570032100");
            byte[] encodedPublicKey = new byte[spkiPrefix.length + rawPublicKey.length];
            System.arraycopy(spkiPrefix, 0, encodedPublicKey, 0, spkiPrefix.length);
            System.arraycopy(rawPublicKey, 0, encodedPublicKey, spkiPrefix.length, rawPublicKey.length);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encodedPublicKey)));
            verifier.update(OpenClawProbe.buildSignaturePayload(
                    device.getString("id"), 1234, "test-token", "offline-nonce")
                    .getBytes(StandardCharsets.UTF_8));
            assertTrue(verifier.verify(Base64.getUrlDecoder().decode(device.getString("signature"))));
            assertTrue(state.resolve("openclaw-device.json").toFile().isFile());
        }
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) {
                throw new IllegalStateException("WebSocket upgrade 提前结束");
            }
            bytes.write(value);
            byte[] marker = {'\r', '\n', '\r', '\n'};
            matched = value == marker[matched] ? matched + 1 : 0;
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static String header(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return line.substring(name.length() + 1).trim();
            }
        }
        throw new IllegalStateException("缺少 " + name);
    }

    private static void sendText(OutputStream output, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        output.write(0x81);
        if (payload.length < 126) {
            output.write(payload.length);
        } else {
            output.write(126);
            output.write((payload.length >>> 8) & 0xff);
            output.write(payload.length & 0xff);
        }
        output.write(payload);
        output.flush();
    }

    private static String readClientText(InputStream input) throws Exception {
        int first = input.read();
        int second = input.read();
        if (first < 0 || second < 0 || (second & 0x80) == 0) {
            throw new IllegalStateException("客户端 WebSocket 帧无效");
        }
        long length = second & 0x7f;
        if (length == 126) {
            length = (input.read() << 8) | input.read();
        } else if (length == 127) {
            throw new IllegalStateException("测试帧过大");
        }
        byte[] mask = input.readNBytes(4);
        byte[] payload = input.readNBytes((int) length);
        for (int index = 0; index < payload.length; index++) {
            payload[index] ^= mask[index % 4];
        }
        return new String(payload, StandardCharsets.UTF_8);
    }
}
