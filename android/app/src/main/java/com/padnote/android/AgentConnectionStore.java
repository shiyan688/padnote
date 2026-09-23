package com.padnote.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Optional computer-agent connection. Credentials never enter note files. */
final class AgentConnectionStore {
    enum Kind { HERMES, OPENCLAW }

    static final class Config {
        final Kind kind;
        final String endpoint;
        final String token;
        final boolean connected;

        Config(Kind kind, String endpoint, String token, boolean connected) {
            this.kind = kind;
            this.endpoint = endpoint;
            this.token = token;
            this.connected = connected;
        }

        boolean complete() {
            return endpoint != null && !endpoint.trim().isEmpty()
                    && token != null && !token.trim().isEmpty();
        }
    }

    private static final String STORE = "padnote-agent-connection";
    private static final String KEY_ALIAS = "padnote-agent-token-v1";
    private final SharedPreferences preferences;

    AgentConnectionStore(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    Config load() {
        return new Config(kind(), preferences.getString("endpoint", ""),
                decrypt(preferences.getString("token", ""), preferences.getString("iv", "")),
                preferences.getBoolean("connected", false));
    }

    void save(Kind kind, String endpoint, String token) throws Exception {
        String[] encrypted = encrypt(token == null ? "" : token);
        preferences.edit().putString("kind", kind.name()).putString("endpoint", endpoint.trim())
                .putString("token", encrypted[0]).putString("iv", encrypted[1])
                .putBoolean("connected", false).apply();
    }

    void setConnected(boolean connected) {
        preferences.edit().putBoolean("connected", connected).apply();
    }

    void clear() {
        preferences.edit().clear().apply();
    }

    private Kind kind() {
        try {
            return Kind.valueOf(preferences.getString("kind", Kind.HERMES.name()));
        } catch (IllegalArgumentException ignored) {
            return Kind.HERMES;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    private static String[] encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return new String[]{
                Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP),
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        };
    }

    private static String decrypt(String encoded, String iv) {
        if (encoded == null || encoded.isEmpty() || iv == null || iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(iv, Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(encoded, Base64.DEFAULT)),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }
}
