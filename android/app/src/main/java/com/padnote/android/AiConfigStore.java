package com.padnote.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stores named AI profiles ("配置档案") encrypted with Android Keystore.
 *
 * <p>A profile is either a single multimodal endpoint ("direct", the original
 * route) or a two-leg pipeline ("split": a transcription leg turns the
 * selection image into Markdown/LaTeX text, then a text-only answer leg runs
 * the tool loop against that transcript). Both routes stay first-class: the
 * user picks per profile and switches between profiles the way cc-switch does
 * for coding tools.
 *
 * <p>API keys never appear in plaintext preferences or logs; each leg stores
 * its own AES-GCM ciphertext under the shared Keystore key. The pre-profile
 * single configuration is migrated once into a profile named 默认配置 so
 * existing users keep working without re-entering their key.
 */
final class AiConfigStore {
    private static final long MAX_REVISION = Long.MAX_VALUE - 1024;
    enum ToolCapability { UNKNOWN, CONFIRMED, EXPLICITLY_REJECTED }
    /** Wire-level credentials for one request leg. */
    static final class Config {
        final String endpoint;
        final String model;
        final String apiKey;

        Config(String endpoint, String model, String apiKey) {
            this.endpoint = endpoint;
            this.model = model;
            this.apiKey = apiKey;
        }

        boolean isComplete() {
            return !endpoint.isEmpty() && !model.isEmpty() && !apiKey.isEmpty();
        }
    }

    /** One switchable configuration slot. */
    static final class Profile {
        String id;
        String name;
        boolean split;
        String directEndpoint = "";
        String directModel = "";
        String transcribeEndpoint = "";
        String transcribeModel = "";
        String answerEndpoint = "";
        String answerModel = "";
        String directKeyCiphertext = "";
        String directKeyIv = "";
        String transcribeKeyCiphertext = "";
        String transcribeKeyIv = "";
        String answerKeyCiphertext = "";
        String answerKeyIv = "";
        /** Changes for every recipient-affecting save, including a credential-only save. */
        long revision;
        ToolCapability toolCapability = ToolCapability.UNKNOWN;

        boolean hasDirectKey() {
            return !directKeyCiphertext.isEmpty() && !directKeyIv.isEmpty();
        }

        boolean hasTranscribeKey() {
            return !transcribeKeyCiphertext.isEmpty() && !transcribeKeyIv.isEmpty();
        }

        boolean hasAnswerKey() {
            return !answerKeyCiphertext.isEmpty() && !answerKeyIv.isEmpty();
        }

        boolean structurallyComplete() {
            if (name.trim().isEmpty()) {
                return false;
            }
            if (!split) {
                return !directEndpoint.trim().isEmpty() && !directModel.trim().isEmpty()
                        && hasDirectKey();
            }
            return !transcribeEndpoint.trim().isEmpty() && !transcribeModel.trim().isEmpty()
                    && hasTranscribeKey()
                    && !answerEndpoint.trim().isEmpty() && !answerModel.trim().isEmpty()
                    && hasAnswerKey();
        }

        String summary() {
            if (!split) {
                return "直连 · " + directModel;
            }
            return "两段式 · 转写 " + transcribeModel + " → 回答 " + answerModel;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", name);
            json.put("split", split);
            json.put("directEndpoint", directEndpoint);
            json.put("directModel", directModel);
            json.put("transcribeEndpoint", transcribeEndpoint);
            json.put("transcribeModel", transcribeModel);
            json.put("answerEndpoint", answerEndpoint);
            json.put("answerModel", answerModel);
            json.put("directKeyCiphertext", directKeyCiphertext);
            json.put("directKeyIv", directKeyIv);
            json.put("transcribeKeyCiphertext", transcribeKeyCiphertext);
            json.put("transcribeKeyIv", transcribeKeyIv);
            json.put("answerKeyCiphertext", answerKeyCiphertext);
            json.put("answerKeyIv", answerKeyIv);
            json.put("revision", revision);
            json.put("toolCapability", toolCapability.name());
            return json;
        }

        static Profile fromJson(JSONObject json) {
            Profile profile = new Profile();
            profile.id = json.optString("id");
            profile.name = json.optString("name");
            profile.split = json.optBoolean("split", false);
            profile.directEndpoint = json.optString("directEndpoint");
            profile.directModel = json.optString("directModel");
            profile.transcribeEndpoint = json.optString("transcribeEndpoint");
            profile.transcribeModel = json.optString("transcribeModel");
            profile.answerEndpoint = json.optString("answerEndpoint");
            profile.answerModel = json.optString("answerModel");
            profile.directKeyCiphertext = json.optString("directKeyCiphertext");
            profile.directKeyIv = json.optString("directKeyIv");
            profile.transcribeKeyCiphertext = json.optString("transcribeKeyCiphertext");
            profile.transcribeKeyIv = json.optString("transcribeKeyIv");
            profile.answerKeyCiphertext = json.optString("answerKeyCiphertext");
            profile.answerKeyIv = json.optString("answerKeyIv");
            profile.revision = json.optLong("revision", 0);
            if (profile.revision < 0 || profile.revision > MAX_REVISION) {
                throw new IllegalArgumentException("AI 配置修订无效");
            }
            try {
                profile.toolCapability = ToolCapability.valueOf(
                        json.optString("toolCapability", ToolCapability.UNKNOWN.name()));
            } catch (IllegalArgumentException ignored) {
                profile.toolCapability = ToolCapability.UNKNOWN;
            }
            return profile;
        }
    }

    /** Fill-in helper shown in the profile editor; never carries a key. */
    static final class VendorPreset {
        final String label;
        final String baseUrl;
        final String directModel;
        final String transcribeModel;
        final String answerModel;
        /** Where to get the key and what to watch out for; shown under the spinner. */
        final String hint;

        VendorPreset(String label, String baseUrl, String directModel,
                     String transcribeModel, String answerModel, String hint) {
            this.label = label;
            this.baseUrl = baseUrl;
            this.directModel = directModel;
            this.transcribeModel = transcribeModel;
            this.answerModel = answerModel;
            this.hint = hint;
        }
    }

    private static final String STORE_NAME = "padnote-ai-config";
    private static final String KEY_ALIAS = "padnote-ai-api-key-v1";
    private static final String PREF_PROFILE_IDS = "profileIds";
    private static final String PREF_ACTIVE_ID = "activeProfileId";
    private static final String PREF_PROFILE_PREFIX = "profile.";
    // Legacy single-configuration keys, read once for migration then removed.
    private static final String PREF_ENDPOINT = "endpoint";
    private static final String PREF_MODEL = "model";
    private static final String PREF_KEY_CIPHERTEXT = "apiKeyCiphertext";
    private static final String PREF_KEY_IV = "apiKeyIv";

    private final SharedPreferences preferences;
    private static final Object STORE_LOCK = new Object();

    AiConfigStore(Context context) {
        preferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE);
    }

    static VendorPreset[] vendorPresets() {
        return new VendorPreset[]{
                new VendorPreset("自定义", "", "", "", "", ""),
                new VendorPreset("智谱 BigModel（有免费视觉模型）",
                        "https://open.bigmodel.cn/api/paas/v4",
                        "glm-4.6v-flash", "glm-4.6v-flash", "glm-4.7-flash",
                        "API Key 在 open.bigmodel.cn 控制台获取。glm-4.6v-flash 免费且支持图像与工具调用，适合零成本起步。"),
                new VendorPreset("DeepSeek",
                        "https://api.deepseek.com",
                        "deepseek-v4-flash-vision-exp",
                        "deepseek-v4-flash-vision-exp", "deepseek-v4-flash",
                        "API Key 在 platform.deepseek.com 获取，按量计费。vision-exp 为实验版：图片会被压缩到约 800×800，密集小字手写需真机确认识别率。"),
                new VendorPreset("阿里云百炼",
                        "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-flash", "qwen3.5-ocr", "qwen3.7-flash",
                        "粘贴 sk- 开头的按量 API Key（百炼控制台获取），新用户各模型有免费额度。"),
                new VendorPreset("阿里云百炼 Token Plan（订阅）",
                        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                        "qwen3.7-plus", "qwen3.7-plus", "qwen3.7-plus",
                        "粘贴 sk-sp- 开头的订阅 Key（Token Plan 控制台“我的订阅”生成，与按量 Key 不互通）。官方允许协议兼容工具接入；Lite 约 39 元/月、Credits 按 7 天窗口抵扣。请到官方模型列表确认所用模型（含视觉）在套餐内，并注意个人版数据授权条款与团队版不同。"),
                new VendorPreset("MiniMax",
                        "https://api.minimaxi.com/v1",
                        "MiniMax-M3", "MiniMax-M3", "MiniMax-M3",
                        "按量 API Key 在开放平台获取。M 系列把思维链内联在正文里，PadNote 已自动剥离显示并回传保真；M2.7 是纯文本（适合两段式回答腿），看手写图请用 M3 或其他视觉模型。"),
                new VendorPreset("MiniMax Token Plan（订阅）",
                        "https://api.minimaxi.com/v1",
                        "MiniMax-M3", "MiniMax-M3", "MiniMax-M3",
                        "粘贴订阅 Key（平台「订阅管理 > Token Plan」查看，与按量 API Key 不互通），端点与按量相同；同一订阅 Key 也可走 OpenAI 兼容 /v1 端点。思维链处理同上；M2.7 为纯文本模型。"),
                new VendorPreset("Kimi 开放平台",
                        "https://api.moonshot.cn/v1",
                        "kimi-k2.6", "kimi-k2.6", "kimi-k2.6",
                        "API Key 在 platform.kimi.com 获取，纯按量计费（无订阅制）。")
        };
    }

    List<Profile> listProfiles() {
        synchronized (STORE_LOCK) {
            migrateLegacyIfNeeded();
            List<Profile> profiles = new ArrayList<>();
            for (String id : profileIds()) {
                String raw = preferences.getString(PREF_PROFILE_PREFIX + id, null);
                if (raw == null) continue;
                try {
                    Profile profile = Profile.fromJson(new JSONObject(raw));
                    if (profile.revision <= 0) {
                        profile.revision = 1;
                        if (!preferences.edit().putString(PREF_PROFILE_PREFIX + id,
                                profile.toJson().toString()).commit()) {
                            continue;
                        }
                    }
                    profiles.add(profile);
                } catch (Exception corrupted) {
                    // Skip a damaged entry instead of failing the whole list.
                }
            }
            return profiles;
        }
    }

    Profile activeProfile() {
        migrateLegacyIfNeeded();
        String activeId = preferences.getString(PREF_ACTIVE_ID, "");
        if (!activeId.isEmpty()) {
            for (Profile profile : listProfiles()) {
                if (activeId.equals(profile.id)) return profile;
            }
        }
        List<Profile> profiles = listProfiles();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    String activeProfileId() {
        return preferences.getString(PREF_ACTIVE_ID, "");
    }

    void setActiveProfileId(String id) {
        preferences.edit().putString(PREF_ACTIVE_ID, id == null ? "" : id).apply();
    }

    /** Inserts or updates a profile; a null key keeps the stored one. */
    void saveProfile(Profile profile, String directKey, String transcribeKey,
                     String answerKey) throws Exception {
        synchronized (STORE_LOCK) {
        if (profile.id == null || profile.id.isEmpty()) {
            profile.id = java.util.UUID.randomUUID().toString();
        }
        if (directKey != null && !directKey.isEmpty()) {
            byte[][] encrypted = encrypt(directKey);
            profile.directKeyCiphertext = Base64.encodeToString(encrypted[0], Base64.NO_WRAP);
            profile.directKeyIv = Base64.encodeToString(encrypted[1], Base64.NO_WRAP);
        }
        if (transcribeKey != null && !transcribeKey.isEmpty()) {
            byte[][] encrypted = encrypt(transcribeKey);
            profile.transcribeKeyCiphertext = Base64.encodeToString(encrypted[0], Base64.NO_WRAP);
            profile.transcribeKeyIv = Base64.encodeToString(encrypted[1], Base64.NO_WRAP);
        }
        if (answerKey != null && !answerKey.isEmpty()) {
            byte[][] encrypted = encrypt(answerKey);
            profile.answerKeyCiphertext = Base64.encodeToString(encrypted[0], Base64.NO_WRAP);
            profile.answerKeyIv = Base64.encodeToString(encrypted[1], Base64.NO_WRAP);
        }

        long oldRevision = 0;
        String stored = preferences.getString(PREF_PROFILE_PREFIX + profile.id, null);
        if (stored != null) {
            try { oldRevision = Profile.fromJson(new JSONObject(stored)).revision; }
            catch (Exception ignored) { }
        }
        if (oldRevision >= MAX_REVISION) {
            throw new java.io.IOException("AI 配置修订已达上限");
        }
        profile.revision = Math.max(1, oldRevision + 1);
        profile.toolCapability = ToolCapability.UNKNOWN;
        List<String> ids = profileIds();
        if (!ids.contains(profile.id)) {
            ids.add(profile.id);
        }
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(PREF_PROFILE_PREFIX + profile.id, profile.toJson().toString());
        editor.putString(PREF_PROFILE_IDS, android.text.TextUtils.join(",", ids));
        if (preferences.getString(PREF_ACTIVE_ID, "").isEmpty()) {
            editor.putString(PREF_ACTIVE_ID, profile.id);
        }
        if (!editor.commit()) throw new java.io.IOException("无法保存 AI 配置修订");
        }
    }

    boolean recordToolCapability(String profileId, long expectedRevision,
                                 ToolCapability capability) {
        synchronized (STORE_LOCK) {
            String raw = preferences.getString(PREF_PROFILE_PREFIX + profileId, null);
            if (raw == null) return false;
            try {
                Profile profile = Profile.fromJson(new JSONObject(raw));
                if (profile.revision != expectedRevision) return false;
                profile.toolCapability = capability == null ? ToolCapability.UNKNOWN : capability;
                return preferences.edit().putString(PREF_PROFILE_PREFIX + profileId,
                        profile.toJson().toString()).commit();
            } catch (Exception invalid) {
                return false;
            }
        }
    }

    void deleteProfile(String id) {
        List<String> ids = profileIds();
        ids.remove(id);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(PREF_PROFILE_PREFIX + id);
        editor.putString(PREF_PROFILE_IDS, android.text.TextUtils.join(",", ids));
        if (id.equals(preferences.getString(PREF_ACTIVE_ID, ""))) {
            editor.putString(PREF_ACTIVE_ID, ids.isEmpty() ? "" : ids.get(0));
        }
        editor.apply();
    }

    Config directConfig(Profile profile) throws Exception {
        return new Config(profile.directEndpoint, profile.directModel,
                profile.hasDirectKey() ? decrypt(profile.directKeyCiphertext, profile.directKeyIv) : "");
    }

    Config transcribeConfig(Profile profile) throws Exception {
        return new Config(profile.transcribeEndpoint, profile.transcribeModel,
                profile.hasTranscribeKey()
                        ? decrypt(profile.transcribeKeyCiphertext, profile.transcribeKeyIv) : "");
    }

    Config answerConfig(Profile profile) throws Exception {
        return new Config(profile.answerEndpoint, profile.answerModel,
                profile.hasAnswerKey()
                        ? decrypt(profile.answerKeyCiphertext, profile.answerKeyIv) : "");
    }

    /**
     * One-time migration of the pre-profile single configuration. The legacy
     * ciphertext was produced by the same Keystore key, so it is copied into
     * the migrated profile verbatim instead of being decrypted and re-encrypted.
     */
    private void migrateLegacyIfNeeded() {
        if (!profileIds().isEmpty()) {
            return;
        }
        String endpoint = preferences.getString(PREF_ENDPOINT, "");
        String model = preferences.getString(PREF_MODEL, "");
        String ciphertext = preferences.getString(PREF_KEY_CIPHERTEXT, "");
        String iv = preferences.getString(PREF_KEY_IV, "");
        if (endpoint.isEmpty() && model.isEmpty() && ciphertext.isEmpty()) {
            return;
        }
        try {
            Profile legacy = new Profile();
            legacy.name = "默认配置";
            legacy.split = false;
            legacy.directEndpoint = endpoint;
            legacy.directModel = model;
            legacy.directKeyCiphertext = ciphertext;
            legacy.directKeyIv = iv;
            saveProfile(legacy, null, null, null);
            setActiveProfileId(legacy.id);
            preferences.edit()
                    .remove(PREF_ENDPOINT)
                    .remove(PREF_MODEL)
                    .remove(PREF_KEY_CIPHERTEXT)
                    .remove(PREF_KEY_IV)
                    .apply();
        } catch (Exception failure) {
            // Keep the legacy keys so the next launch can retry migration.
        }
    }

    private List<String> profileIds() {
        String raw = preferences.getString(PREF_PROFILE_IDS, "");
        List<String> ids = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        for (String id : raw.split(",")) {
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private byte[][] encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return new byte[][]{encrypted, cipher.getIV()};
    }

    private String decrypt(String ciphertext, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        byte[] decrypted = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
