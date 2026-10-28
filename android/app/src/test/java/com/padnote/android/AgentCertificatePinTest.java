package com.padnote.android;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

public final class AgentCertificatePinTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void pinFormatIsStrict() {
        assertEquals("", AgentCertificatePin.normalize(null));
        assertEquals("", AgentCertificatePin.normalize("  "));
        String pin = repeat("ab", 32);
        assertEquals(pin, AgentCertificatePin.normalize(" " + pin.toUpperCase() + " "));
        for (String bad : new String[]{"abc", repeat("zz", 32), repeat("ab", 31), repeat("ab", 33)}) {
            try { AgentCertificatePin.normalize(bad); fail(bad); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void pairingQrCarriesThePinIntoEveryRequest() throws Exception {
        String pin = repeat("cd", 32);
        AgentPairingClient.PairingPayload payload = AgentPairingClient.parse(
                "{\"type\":\"padnote-pair\",\"version\":1,\"url\":\"https://192.168.1.20:8767\"," +
                        "\"bridge_id\":\"bridge-a\",\"code\":\"0123456789abcdef\"," +
                        "\"cert_sha256\":\"" + pin + "\"}");
        assertEquals(pin, payload.certSha256);
        AtomicReference<AgentHttpTransport.Request> seen = new AtomicReference<>();
        AgentPairingClient client = new AgentPairingClient(request -> {
            seen.set(request);
            return new AgentHttpTransport.Response(202, request.url, request.url,
                    ("{\"request_id\":\"r\",\"poll_token\":\"p\",\"expires_at\":1999999999," +
                            "\"status\":\"pending\"}").getBytes(StandardCharsets.UTF_8),
                    Collections.emptyMap());
        });
        client.request(payload, "device-a", "Tablet");
        assertEquals(pin, seen.get().certSha256);
    }

    @Test public void aMalformedPinInTheQrIsRefused() {
        try {
            AgentPairingClient.parse("{\"type\":\"padnote-pair\",\"version\":1," +
                    "\"url\":\"https://192.168.1.20:8767\",\"bridge_id\":\"b\"," +
                    "\"code\":\"0123456789abcdef\",\"cert_sha256\":\"not-a-pin\"}");
            fail("a malformed pin must not silently fall back to normal HTTPS");
        } catch (Exception expected) { }
    }

    @Test public void trustManagerAcceptsOnlyThePinnedLeaf() throws Exception {
        X509Certificate certificate = serverIdentity().certificate;
        String actual = AgentCertificatePin.fingerprint(certificate);
        new AgentCertificatePin.PinnedTrustManager(actual)
                .checkServerTrusted(new X509Certificate[]{certificate}, "RSA");
        try {
            new AgentCertificatePin.PinnedTrustManager(repeat("00", 32))
                    .checkServerTrusted(new X509Certificate[]{certificate}, "RSA");
            fail("a different certificate must be rejected");
        } catch (CertificateException expected) { }
    }

    @Test public void aPinnedConnectionReachesASelfSignedServerAndAWrongPinDoesNot() throws Exception {
        Identity identity = serverIdentity();
        try (TinyTlsServer server = new TinyTlsServer(identity)) {
            URL url = new URL("https://127.0.0.1:" + server.port() + "/padnote/v1/ping");
            String pin = AgentCertificatePin.fingerprint(identity.certificate);
            AgentHttpTransport.Response ok = AgentHttpTransport.production().execute(
                    new AgentHttpTransport.Request("GET", url, "", Collections.emptyMap(), null,
                            1024, pin));
            assertEquals(200, ok.status);
            assertEquals("{\"ok\":true}", ok.utf8());

            try {
                AgentHttpTransport.production().execute(new AgentHttpTransport.Request(
                        "GET", url, "", Collections.emptyMap(), null, 1024, repeat("00", 32)));
                fail("a wrong pin must fail the handshake");
            } catch (SSLHandshakeException expected) { }

            try {
                AgentHttpTransport.production().execute(new AgentHttpTransport.Request(
                        "GET", url, "", Collections.emptyMap(), null, 1024));
                fail("without a pin the platform must still reject a self-signed certificate");
            } catch (SSLHandshakeException expected) { }
        }
    }

    @Test public void postStreamsFixedLengthChunksAndReportsWrittenBytes() throws Exception {
        Identity identity=serverIdentity();
        try(TinyTlsServer server=new TinyTlsServer(identity)) {
            byte[] payload=new byte[70*1024];java.util.Arrays.fill(payload,(byte)'x');
            java.util.List<Long> progress=new java.util.ArrayList<>();
            URL url=new URL("https://127.0.0.1:"+server.port()+"/runs");
            AgentHttpTransport.production().execute(new AgentHttpTransport.Request("POST",url,"",
                    Collections.emptyMap(),payload,1024,AgentCertificatePin.fingerprint(identity.certificate),
                    (sent,total)->progress.add(sent),90000));
            assertTrue(server.requestHeaders.contains("Content-Length: "+payload.length));
            assertFalse(server.requestHeaders.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked"));
            assertEquals(payload.length,server.requestBytes);
            assertTrue(progress.size()>=3);
            assertEquals(Long.valueOf(payload.length),progress.get(progress.size()-1));
        }
    }

    @Test public void postDeadlineUnblocksAndDoesNotRepeatRequest() throws Exception {
        Identity identity=serverIdentity();
        try(TinyTlsServer server=new TinyTlsServer(identity,3000)) {
            URL url=new URL("https://127.0.0.1:"+server.port()+"/slow");byte[] body=new byte[64*1024];
            long started=System.nanoTime();
            try {
                AgentHttpTransport.production().execute(new AgentHttpTransport.Request("POST",url,"",
                        Collections.emptyMap(),body,1024,AgentCertificatePin.fingerprint(identity.certificate),null,1000));
                fail("deadline must abort a response that never arrives in time");
            } catch(java.io.IOException expected) { }
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertTrue("request should be bounded by the deadline",elapsed<2500);
            assertEquals(1,server.requests);
            assertEquals(body.length,server.requestBytes);
        }
    }

    // --- fixtures -------------------------------------------------------

    private static final class Identity {
        final KeyStore keyStore; final X509Certificate certificate;
        Identity(KeyStore keyStore, X509Certificate certificate) {
            this.keyStore = keyStore; this.certificate = certificate;
        }
    }

    /** A self-signed RSA identity, generated with the JDK keytool, as the assistant's would be. */
    private Identity serverIdentity() throws Exception {
        File store = new File(temporary.getRoot(), "server.p12");
        if (!store.exists()) {
            String keytool = new File(System.getProperty("java.home"), "bin/keytool").getPath();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                    "-dname", "CN=PadNote Connection Assistant", "-storetype", "PKCS12",
                    "-keystore", store.getPath(), "-storepass", "changeit", "-keypass", "changeit")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream input = new FileInputStream(store)) {
            keyStore.load(input, "changeit".toCharArray());
        }
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(
                        keyStore.getCertificate("server").getEncoded()));
        return new Identity(keyStore, certificate);
    }

    /** One-request-at-a-time HTTPS server answering 200 {"ok":true}. */
    private static final class TinyTlsServer implements AutoCloseable {
        private final SSLServerSocket socket;
        private final Thread thread;
        private final long responseDelayMillis;
        volatile String requestHeaders="";
        volatile int requestBytes;
        volatile int requests;

        TinyTlsServer(Identity identity) throws Exception {this(identity,0);}
        TinyTlsServer(Identity identity,long responseDelayMillis) throws Exception {
            this.responseDelayMillis=responseDelayMillis;
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(identity.keyStore, "changeit".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            socket = (SSLServerSocket) context.getServerSocketFactory()
                    .createServerSocket(0, 8, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (SSLSocket client = (SSLSocket) socket.accept()) {
                        client.setSoTimeout(5000);
                        InputStream in = client.getInputStream();
                        ByteArrayOutputStream headers=new ByteArrayOutputStream();
                        int current,matched=0;while(matched<4&&(current=in.read())>=0){headers.write(current);matched=(current=='\r'||current=='\n')?matched+1:0;}
                        requestHeaders=new String(headers.toByteArray(),StandardCharsets.US_ASCII);
                        requests++;
                        int length=0;for(String line:requestHeaders.split("\\r?\\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
                        byte[] buffer=new byte[8192];int count;while(requestBytes<length&&(count=in.read(buffer,0,Math.min(buffer.length,length-requestBytes)))>0)requestBytes+=count;
                        if(responseDelayMillis>0)Thread.sleep(responseDelayMillis);
                        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(body);
                        out.flush();
                    } catch (Exception ignored) {
                        // A refused handshake from the wrong-pin case lands here.
                    }
                }
            }, "tiny-tls");
            thread.setDaemon(true);
            thread.start();
        }

        int port() { return socket.getLocalPort(); }

        @Override public void close() throws Exception {
            socket.close();
            thread.join(2000);
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) builder.append(value);
        return builder.toString();
    }
}
