package com.padnote.android;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Same-network connection: trust exactly the certificate named in the pairing QR.
 *
 * The connection assistant on the computer generates its own certificate; its
 * SHA-256 arrives inside the one-time QR. A pinned connection accepts that one
 * leaf and nothing else -- no CA, no hostname -- which is stronger than normal
 * HTTPS for this purpose and needs no third-party network software. An empty
 * pin leaves the platform's normal certificate checks untouched.
 */
final class AgentCertificatePin {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private AgentCertificatePin() { }

    /** Normalizes a pin, or returns "" for none; anything else is refused. */
    static String normalize(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty()) return "";
        if (!SHA256.matcher(clean).matches()) throw new IllegalArgumentException("证书指纹格式无效");
        return clean;
    }

    /** Applies the pin to one connection; a no-op when the pin is empty. */
    static void apply(HttpsURLConnection connection, String pin) throws Exception {
        String expected = normalize(pin);
        if (expected.isEmpty()) return;
        connection.setSSLSocketFactory(socketFactory(expected));
        // The host is a LAN address that no certificate names; identity is the
        // pinned fingerprint, checked in the trust manager before any byte is sent.
        HostnameVerifier pinned = (host, session) -> true;
        connection.setHostnameVerifier(pinned);
    }

    static SSLSocketFactory socketFactory(String expected) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new PinnedTrustManager(expected)}, null);
        return context.getSocketFactory();
    }

    static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    static final class PinnedTrustManager implements X509TrustManager {
        private final String expected;

        PinnedTrustManager(String expected) { this.expected = normalize(expected); }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("client certificates are not used");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("证书缺失");
            final String actual;
            try { actual = fingerprint(chain[0]); }
            catch (Exception error) { throw new CertificateException("无法读取电脑证书", error); }
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new CertificateException("电脑证书与配对时不一致，已拒绝连接");
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
