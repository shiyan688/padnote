package com.padnote.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Multiple optional computer-agent connections. Credentials never enter note files. */
final class AgentConnectionStore {
    enum Kind { HERMES, OPENCLAW, BUILTIN_VIDEO }
    enum Transport { DIRECT, BRIDGE }
    enum ProbeFailure {
        CREDENTIALS("认证未通过", "重新保存连接令牌后测试。"),
        TIMEOUT("连接超时", "检查电脑是否开机及网络是否可达，然后重新测试。"),
        NETWORK("无法连接电脑", "检查网络、地址和电脑连接助手状态，然后重新测试。"),
        TLS_IDENTITY("安全连接或身份检查失败", "检查 HTTPS 证书、配对身份和电脑地址，然后重新测试。"),
        PROTOCOL("电脑未通过能力协议检查", "确认 Agent/连接助手正在运行且配对协议受支持，然后重新测试。"),
        UNKNOWN("上次验证未完成", "重新测试连接以获取当前结果。");
        final String label;
        final String nextStep;
        ProbeFailure(String label, String nextStep) { this.label = label; this.nextStep = nextStep; }
        static ProbeFailure fromStored(String value) {
            if (value == null || value.isEmpty()) return null;
            try { return valueOf(value); } catch (IllegalArgumentException ignored) { return UNKNOWN; }
        }
    }

    static final class Config {
        final String id;
        final String name;
        final Kind kind;
        final String endpoint;
        final String token;
        /** Opaque reference to one independently encrypted credential. */
        final String credentialRef;
        final Transport transport;
        final String bridgeId;
        final String instanceId;
        /** SHA-256 of the computer's certificate for a same-network pairing, or "". */
        final String certSha256;
        final long revision;
        final long verifiedAt;
        final List<String> capabilities;
        /** Null means this record predates full Boolean feature-map persistence. */
        final Map<String, Boolean> featureMap;
        final boolean featureMapAvailable;
        final ProbeFailure probeFailure;
        final boolean hasCredential;
        /** Compatibility flag: means verified configuration, never current online presence. */
        final boolean connected;

        Config(String id, String name, Kind kind, String endpoint, String token,
               String credentialRef, Transport transport, String instanceId,
               long revision, long verifiedAt, List<String> capabilities) {
            this(id, name, kind, endpoint, token, credentialRef, transport, "", instanceId,
                    revision, verifiedAt, capabilities);
        }

        Config(String id, String name, Kind kind, String endpoint, String token,
               String credentialRef, Transport transport, String bridgeId, String instanceId,
               long revision, long verifiedAt, List<String> capabilities) {
            this(id, name, kind, endpoint, token, credentialRef, transport, bridgeId, instanceId,
                    "", revision, verifiedAt, capabilities);
        }

        Config(String id, String name, Kind kind, String endpoint, String token,
               String credentialRef, Transport transport, String bridgeId, String instanceId,
               String certSha256, long revision, long verifiedAt, List<String> capabilities) {
            this(id, name, kind, endpoint, token, credentialRef, transport, bridgeId, instanceId,
                    certSha256, revision, verifiedAt, capabilities, null);
        }

        Config(String id, String name, Kind kind, String endpoint, String token,
               String credentialRef, Transport transport, String bridgeId, String instanceId,
               String certSha256, long revision, long verifiedAt, List<String> capabilities,
               Map<String, Boolean> featureMap) {
            this(id, name, kind, endpoint, token, credentialRef, transport, bridgeId, instanceId,
                    certSha256, revision, verifiedAt, capabilities, featureMap, null);
        }

        Config(String id, String name, Kind kind, String endpoint, String token,
               String credentialRef, Transport transport, String bridgeId, String instanceId,
               String certSha256, long revision, long verifiedAt, List<String> capabilities,
               Map<String, Boolean> featureMap, ProbeFailure probeFailure) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.kind = kind == null ? Kind.HERMES : kind;
            this.endpoint = endpoint == null ? "" : endpoint;
            this.token = token == null ? "" : token;
            this.credentialRef = credentialRef == null ? "" : credentialRef;
            this.transport = transport == null ? Transport.DIRECT : transport;
            this.bridgeId = bridgeId == null ? "" : bridgeId;
            this.instanceId = instanceId == null ? "" : instanceId;
            this.certSha256 = certSha256 == null ? "" : certSha256;
            this.revision = Math.max(0L, revision);
            this.verifiedAt = Math.max(0L, verifiedAt);
            this.featureMap = immutableFeatureMap(featureMap);
            this.featureMapAvailable = featureMap != null;
            this.probeFailure = probeFailure;
            this.capabilities = Collections.unmodifiableList(new ArrayList<>(featureMap != null
                    ? enabledNames(this.featureMap)
                    : (capabilities == null ? Collections.emptyList() : capabilities)));
            this.hasCredential = !this.credentialRef.isEmpty() || !this.token.isEmpty();
            this.connected = this.verifiedAt > 0L;
        }

        /** Legacy constructor retained for client fixtures. */
        Config(Kind kind, String endpoint, String token, boolean connected) {
            this("", "电脑 Agent", kind, endpoint, token, "", Transport.DIRECT, "",
                    0L, connected ? System.currentTimeMillis() : 0L,
                    Collections.emptyList());
        }

        boolean complete() {
            return !endpoint.trim().isEmpty() && hasCredential;
        }

        boolean verified() {
            return verifiedAt > 0L;
        }

        String statusLabel() {
            if (!complete()) return "配置不完整";
            if (probeFailure != null) return probeFailure.label;
            if (!verified()) return "尚未验证";
            return capabilities.isEmpty() ? "已验证" : "已验证 · " + capabilities.size() + " 项能力";
        }

        Config withoutToken() {
            return new Config(id, name, kind, endpoint, "", credentialRef, transport,
                    bridgeId, instanceId, certSha256, revision, verifiedAt, capabilities,
                    featureMapAvailable ? featureMap : null, probeFailure);
        }
    }

    interface Backend {
        Object transactionLock();
        String string(String key, String fallback);
        boolean bool(String key, boolean fallback);
        boolean contains(String key);
        boolean commit(Map<String, String> strings, Map<String, Boolean> booleans,
                       Set<String> removals);
    }

    private static final class PreferenceBackend implements Backend {
        private static final Object SHARED_LOCK = new Object();
        private final SharedPreferences preferences;
        PreferenceBackend(SharedPreferences preferences) { this.preferences = preferences; }
        @Override public Object transactionLock() { return SHARED_LOCK; }
        @Override public String string(String key, String fallback) {
            return preferences.getString(key, fallback);
        }
        @Override public boolean bool(String key, boolean fallback) {
            return preferences.getBoolean(key, fallback);
        }
        @Override public boolean contains(String key) { return preferences.contains(key); }
        @Override public boolean commit(Map<String, String> strings, Map<String, Boolean> booleans,
                                        Set<String> removals) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : removals) editor.remove(key);
            for (Map.Entry<String, String> entry : strings.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Boolean> entry : booleans.entrySet()) {
                editor.putBoolean(entry.getKey(), entry.getValue());
            }
            return editor.commit();
        }
    }

    interface Clock { long now(); }

    private static final String STORE = "padnote-agent-connection";
    private static final String KEY_ALIAS = "padnote-agent-token-v1";
    private static final String REGISTRY = "connections.v2";
    private static final String TOKEN_PREFIX = "credential.";
    private static final String IV_SUFFIX = ".iv";
    private static final String LEGACY_KIND = "kind";
    private static final String LEGACY_ENDPOINT = "endpoint";
    private static final String LEGACY_TOKEN = "token";
    private static final String LEGACY_IV = "iv";
    private static final String LEGACY_CONNECTED = "connected";

    private static Map<String, Boolean> immutableFeatureMap(Map<String, Boolean> values) {
        if (values == null) return Collections.emptyMap();
        Map<String, Boolean> copy = new TreeMap<>();
        for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isEmpty() &&
                    entry.getValue() != null) copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<String> enabledNames(Map<String, Boolean> values) {
        List<String> result = new ArrayList<>();
        if (values != null) for (Map.Entry<String, Boolean> entry : values.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) result.add(entry.getKey());
        }
        return result;
    }

    private final Backend backend;
    private final Clock clock;
    private final Object transactionLock;

    AgentConnectionStore(Context context) {
        this(new PreferenceBackend(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)),
                System::currentTimeMillis);
    }

    /** Package-private isolated-preferences seam for instrumented store lifecycle tests. */
    AgentConnectionStore(SharedPreferences preferences, Clock clock) {
        this(new PreferenceBackend(preferences), clock);
    }

    AgentConnectionStore(Backend backend, Clock clock) {
        this.backend = backend;
        this.clock = clock;
        this.transactionLock = backend.transactionLock();
        synchronized (transactionLock) { ensureMigrated(); }
    }

    synchronized List<Config> list() {
        synchronized (transactionLock) {
            Registry registry = registry();
            List<Config> result = new ArrayList<>();
            for (StoredConfig stored : registry.connections) result.add(materialize(stored));
            return result;
        }
    }

    synchronized List<Config> listSummaries() {
        List<Config> summaries = new ArrayList<>();
        for (Config config : list()) summaries.add(config.withoutToken());
        return summaries;
    }

    synchronized Config get(String id) {
        synchronized (transactionLock) {
            StoredConfig stored = registry().find(id);
            return stored == null ? null : materialize(stored);
        }
    }

    /** Returns the default connection as the former single-config compatibility view. */
    synchronized Config load() {
        synchronized (transactionLock) {
            Registry registry = registry();
            StoredConfig stored = registry.find(registry.defaultId);
            if (stored == null && !registry.connections.isEmpty()) stored = registry.connections.get(0);
            return stored == null ? emptyConfig() : materialize(stored);
        }
    }

    synchronized String defaultId() {
        synchronized (transactionLock) { return registry().defaultId; }
    }

    synchronized Config add(String name, Kind kind, Transport transport,
                            String endpoint, String token) throws Exception {
        return addInternal(name, kind, transport, endpoint, "", "", "", token);
    }

    synchronized Config addBridge(String name, Kind kind, String endpoint, String bridgeId,
                                  String instanceId, String token) throws Exception {
        return addBridge(name, kind, endpoint, bridgeId, instanceId, "", token);
    }

    synchronized Config addBridge(String name, Kind kind, String endpoint, String bridgeId,
                                  String instanceId, String certSha256, String token) throws Exception {
        if (bridgeId == null || bridgeId.trim().isEmpty() || instanceId == null ||
                instanceId.trim().isEmpty()) {
            throw new IllegalArgumentException("配对结果缺少电脑或 Agent 标识");
        }
        return addInternal(name, kind, Transport.BRIDGE, endpoint, bridgeId.trim(),
                instanceId.trim(), AgentCertificatePin.normalize(certSha256), token);
    }

    private Config addInternal(String name, Kind kind, Transport transport,
                               String endpoint, String bridgeId, String instanceId,
                               String certSha256, String token) throws Exception {
        synchronized (transactionLock) {
        String normalized = AgentConnectionClient.normalizeEndpoint(endpoint);
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("请输入连接令牌");
        }
        Registry registry = registry();
        String id = UUID.randomUUID().toString();
        String credentialRef = UUID.randomUUID().toString();
        StoredConfig stored = new StoredConfig(id, cleanName(name), kind, normalized,
                credentialRef, transport, bridgeId, instanceId, certSha256, 1L, 0L,
                Collections.emptyList());
        registry.connections.add(stored);
        if (registry.defaultId.isEmpty()) registry.defaultId = id;
        commitRegistryAndCredential(registry, credentialRef, token, Collections.emptySet());
        return materialize(stored);
        }
    }

    synchronized Config update(String id, long expectedRevision, String name, Kind kind,
                               Transport transport, String endpoint, String replacementToken)
            throws Exception {
        synchronized (transactionLock) {
        Registry registry = registry();
        StoredConfig current = registry.find(id);
        if (current == null) throw new IllegalArgumentException("找不到 Agent 连接");
        if (current.revision != expectedRevision) {
            throw new IllegalStateException("连接已被其他操作修改，请重新打开");
        }
        String normalized = AgentConnectionClient.normalizeEndpoint(endpoint);
        boolean identityChanged = current.kind != kind || current.transport != transport ||
                !current.endpoint.equals(normalized);
        String token = replacementToken == null ? "" : replacementToken.trim();
        if (identityChanged && token.isEmpty()) {
            throw new IllegalArgumentException("地址或协议已修改，请重新输入该 Agent 的令牌");
        }
        String currentToken = readCredential(current.credentialRef);
        if (!identityChanged && token.isEmpty()) token = currentToken;
        if (token.isEmpty()) throw new IllegalArgumentException("请输入连接令牌");
        boolean authorizationChanged = !token.equals(currentToken);
        boolean identityRevision = identityChanged || authorizationChanged;
        StoredConfig replacement = new StoredConfig(current.id, cleanName(name), kind,
                normalized, current.credentialRef, transport,
                identityChanged ? "" : current.bridgeId,
                identityChanged ? "" : current.instanceId,
                // A pin belongs to the paired address; editing the address drops it.
                identityChanged ? "" : current.certSha256,
                identityRevision ? current.revision + 1L : current.revision,
                identityRevision ? 0L : current.verifiedAt,
                identityRevision ? Collections.emptyList() : current.capabilities,
                identityRevision || !current.featureMapAvailable ? null : current.featureMap,
                identityRevision ? null : ProbeFailure.fromStored(current.probeFailureCode),
                attemptAfterUpdate(current.probeAttemptId, identityRevision));
        registry.replace(replacement);
        commitRegistryAndCredential(registry, current.credentialRef, token,
                Collections.emptySet());
        return materialize(replacement);
        }
    }

    synchronized boolean delete(String id) {
        synchronized (transactionLock) {
        Registry registry = registry();
        StoredConfig removed = registry.remove(id);
        if (removed == null) return false;
        if (id.equals(registry.defaultId)) {
            registry.defaultId = registry.connections.isEmpty()
                    ? "" : registry.connections.get(0).id;
        }
        Set<String> removals = credentialKeys(removed.credentialRef);
        return commit(registry, Collections.emptyMap(), removals);
        }
    }

    synchronized boolean setDefault(String id) {
        synchronized (transactionLock) {
        Registry registry = registry();
        if (registry.find(id) == null) return false;
        registry.defaultId = id;
        return commit(registry, Collections.emptyMap(), Collections.emptySet());
        }
    }

    /** Applies a successful probe only if the exact saved revision is still current. */
    synchronized boolean applyProbeSuccess(String id, long expectedRevision,
                                           AgentConnectionClient.ProbeResult result) {
        return applyProbeSuccess(id, expectedRevision, null, result);
    }

    synchronized boolean applyProbeSuccess(String id, long expectedRevision, String attemptId,
                                           AgentConnectionClient.ProbeResult result) {
        synchronized (transactionLock) {
        Registry registry = registry();
        StoredConfig current = registry.find(id);
        if (current == null || current.revision != expectedRevision ||
                (attemptId != null && !attemptId.equals(current.probeAttemptId)) ||
                (attemptId == null && !current.probeAttemptId.isEmpty())) return false;
        StoredConfig verified = new StoredConfig(current.id, current.name, current.kind,
                current.endpoint, current.credentialRef, current.transport,
                result == null ? current.bridgeId : result.bridgeId,
                result == null ? "" : result.instanceId, current.certSha256, current.revision,
                clock.now(), result == null ? Collections.emptyList() : result.capabilities,
                result == null ? null : result.featureMap, null, "");
        registry.replace(verified);
        return commit(registry, Collections.emptyMap(), Collections.emptySet());
        }
    }

    /** A late failure must not clear a newer edit or a different connection. */
    synchronized boolean applyProbeFailure(String id, long expectedRevision) {
        return applyProbeFailure(id, expectedRevision, null, ProbeFailure.UNKNOWN);
    }

    synchronized boolean applyProbeFailure(String id, long expectedRevision, String attemptId,
                                           ProbeFailure failure) {
        synchronized (transactionLock) {
        Registry registry = registry();
        StoredConfig current = registry.find(id);
        if (current == null || current.revision != expectedRevision ||
                (attemptId != null && !attemptId.equals(current.probeAttemptId)) ||
                (attemptId == null && !current.probeAttemptId.isEmpty())) return false;
        StoredConfig failed = new StoredConfig(current.id, current.name, current.kind,
                current.endpoint, current.credentialRef, current.transport, current.bridgeId,
                current.instanceId, current.certSha256,
                current.revision, 0L, Collections.emptyList(), null,
                failure == null ? ProbeFailure.UNKNOWN : failure, "");
        registry.replace(failed);
        return commit(registry, Collections.emptyMap(), Collections.emptySet());
        }
    }

    /** Creates a per-profile persisted attempt generation so same-revision probes cannot race. */
    synchronized String beginProbe(String id, long expectedRevision) {
        synchronized (transactionLock) {
            Registry registry = registry();
            StoredConfig current = registry.find(id);
            if (current == null || current.revision != expectedRevision) return "";
            String attemptId = UUID.randomUUID().toString();
            registry.replace(current.withProbeAttempt(attemptId));
            return commit(registry, Collections.emptyMap(), Collections.emptySet()) ? attemptId : "";
        }
    }

    static String attemptAfterUpdate(String currentAttemptId, boolean identityRevision) {
        return identityRevision || currentAttemptId == null ? "" : currentAttemptId;
    }

    /** Former API: update or create the default connection. */
    synchronized void save(Kind kind, String endpoint, String token) throws Exception {
        Config current = load();
        if (current.id.isEmpty()) {
            add("我的电脑", kind, Transport.DIRECT, endpoint, token);
        } else {
            update(current.id, current.revision, current.name, kind, current.transport,
                    endpoint, token);
        }
    }

    /** Former API retained for callers; this records verification, not liveness. */
    synchronized void setConnected(boolean connected) {
        Config current = load();
        if (current.id.isEmpty()) return;
        if (connected) {
            applyProbeSuccess(current.id, current.revision,
                    new AgentConnectionClient.ProbeResult("已验证", current.bridgeId,
                            current.instanceId, current.capabilities,
                            current.featureMapAvailable ? current.featureMap : null));
        } else {
            applyProbeFailure(current.id, current.revision);
        }
    }

    synchronized void clear() {
        synchronized (transactionLock) {
        Registry registry = registry();
        Set<String> removals = new LinkedHashSet<>();
        removals.add(LEGACY_KIND);
        removals.add(LEGACY_ENDPOINT);
        removals.add(LEGACY_TOKEN);
        removals.add(LEGACY_IV);
        removals.add(LEGACY_CONNECTED);
        for (StoredConfig config : registry.connections) {
            removals.addAll(credentialKeys(config.credentialRef));
        }
        if (!commit(new Registry(), Collections.emptyMap(), removals)) {
            throw new IllegalStateException("无法清除 Agent 连接");
        }
        }
    }

    private synchronized void ensureMigrated() {
        if (backend.contains(REGISTRY)) return;
        boolean hasLegacy = backend.contains(LEGACY_ENDPOINT) || backend.contains(LEGACY_TOKEN)
                || backend.contains(LEGACY_KIND) || backend.contains(LEGACY_CONNECTED);
        Registry registry = new Registry();
        Map<String, String> additions = new LinkedHashMap<>();
        Set<String> removals = new LinkedHashSet<>();
        if (hasLegacy) {
            String id = UUID.randomUUID().toString();
            String credentialRef = backend.string(LEGACY_TOKEN, "").isEmpty()
                    ? "" : UUID.randomUUID().toString();
            Kind kind = parseKind(backend.string(LEGACY_KIND, Kind.HERMES.name()));
            String endpoint = backend.string(LEGACY_ENDPOINT, "").trim();
            boolean verified = backend.bool(LEGACY_CONNECTED, false);
            StoredConfig migrated = new StoredConfig(id, "我的电脑", kind, endpoint,
                    credentialRef, Transport.DIRECT, "", "", 1L,
                    verified ? clock.now() : 0L, Collections.emptyList());
            registry.connections.add(migrated);
            registry.defaultId = id;
            String legacyToken = backend.string(LEGACY_TOKEN, "");
            String legacyIv = backend.string(LEGACY_IV, "");
            if (!legacyToken.isEmpty()) {
                additions.put(tokenKey(credentialRef), legacyToken);
                additions.put(ivKey(credentialRef), legacyIv);
            }
            Collections.addAll(removals, LEGACY_KIND, LEGACY_ENDPOINT, LEGACY_TOKEN,
                    LEGACY_IV, LEGACY_CONNECTED);
        }
        additions.put(REGISTRY, registry.toJson().toString());
        if (!backend.commit(additions, Collections.emptyMap(), removals)) {
            throw new IllegalStateException("无法迁移旧 Agent 连接");
        }
    }

    private Config materialize(StoredConfig stored) {
        return new Config(stored.id, stored.name, stored.kind, stored.endpoint,
                readCredential(stored.credentialRef), stored.credentialRef, stored.transport,
                stored.bridgeId, stored.instanceId, stored.certSha256, stored.revision,
                stored.verifiedAt, stored.capabilities,
                stored.featureMapAvailable ? stored.featureMap : null,
                ProbeFailure.fromStored(stored.probeFailureCode));
    }

    private Config emptyConfig() {
        return new Config("", "电脑 Agent", Kind.HERMES, "", "", "",
                Transport.DIRECT, "", "", 0L, 0L, Collections.emptyList());
    }

    private Registry registry() {
        String raw = backend.string(REGISTRY, "");
        if (raw.isEmpty()) throw new IllegalStateException("Agent 连接数据缺失");
        try {
            return Registry.fromJson(new JSONObject(raw));
        } catch (Exception error) {
            throw new IllegalStateException("Agent 连接数据损坏，请先恢复应用数据", error);
        }
    }

    private void commitRegistryAndCredential(Registry registry, String credentialRef,
                                             String token, Set<String> removals)
            throws Exception {
        String[] encrypted = encrypt(token);
        Map<String, String> additions = new LinkedHashMap<>();
        additions.put(tokenKey(credentialRef), encrypted[0]);
        additions.put(ivKey(credentialRef), encrypted[1]);
        if (!commit(registry, additions, removals)) {
            throw new IllegalStateException("无法保存 Agent 连接");
        }
    }

    private boolean commit(Registry registry, Map<String, String> additions,
                           Set<String> removals) {
        Map<String, String> values = new LinkedHashMap<>(additions);
        values.put(REGISTRY, registry.toJson().toString());
        return backend.commit(values, Collections.emptyMap(), removals);
    }

    private String readCredential(String ref) {
        if (ref == null || ref.isEmpty()) return "";
        return decrypt(backend.string(tokenKey(ref), ""), backend.string(ivKey(ref), ""));
    }

    private static String cleanName(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return "电脑 Agent";
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    private static Kind parseKind(String value) {
        try { return Kind.valueOf(value); }
        catch (Exception ignored) { return Kind.HERMES; }
    }

    private static Transport parseTransport(String value) {
        try { return Transport.valueOf(value); }
        catch (Exception ignored) { return Transport.DIRECT; }
    }

    private static String tokenKey(String ref) { return TOKEN_PREFIX + ref; }
    private static String ivKey(String ref) { return TOKEN_PREFIX + ref + IV_SUFFIX; }
    private static Set<String> credentialKeys(String ref) {
        Set<String> result = new LinkedHashSet<>();
        result.add(tokenKey(ref));
        result.add(ivKey(ref));
        return result;
    }

    private static final class StoredConfig {
        final String id, name, endpoint, credentialRef, bridgeId, instanceId, certSha256;
        final Kind kind;
        final Transport transport;
        final long revision, verifiedAt;
        final List<String> capabilities;
        final Map<String, Boolean> featureMap;
        final boolean featureMapAvailable;
        final String probeFailureCode;
        final String probeAttemptId;

        StoredConfig(String id, String name, Kind kind, String endpoint, String credentialRef,
                     Transport transport, String bridgeId, String instanceId, long revision, long verifiedAt,
                     List<String> capabilities) {
            this(id, name, kind, endpoint, credentialRef, transport, bridgeId, instanceId, "",
                    revision, verifiedAt, capabilities);
        }

        StoredConfig(String id, String name, Kind kind, String endpoint, String credentialRef,
                     Transport transport, String bridgeId, String instanceId, String certSha256,
                     long revision, long verifiedAt, List<String> capabilities) {
            this(id, name, kind, endpoint, credentialRef, transport, bridgeId, instanceId,
                    certSha256, revision, verifiedAt, capabilities, null);
        }

        StoredConfig(String id, String name, Kind kind, String endpoint, String credentialRef,
                     Transport transport, String bridgeId, String instanceId, String certSha256,
                     long revision, long verifiedAt, List<String> capabilities,
                     Map<String, Boolean> featureMap) {
            this(id, name, kind, endpoint, credentialRef, transport, bridgeId, instanceId,
                    certSha256, revision, verifiedAt, capabilities, featureMap, null, "");
        }

        StoredConfig(String id, String name, Kind kind, String endpoint, String credentialRef,
                     Transport transport, String bridgeId, String instanceId, String certSha256,
                     long revision, long verifiedAt, List<String> capabilities,
                     Map<String, Boolean> featureMap, ProbeFailure probeFailure) {
            this(id, name, kind, endpoint, credentialRef, transport, bridgeId, instanceId,
                    certSha256, revision, verifiedAt, capabilities, featureMap, probeFailure, "");
        }

        StoredConfig(String id, String name, Kind kind, String endpoint, String credentialRef,
                     Transport transport, String bridgeId, String instanceId, String certSha256,
                     long revision, long verifiedAt, List<String> capabilities,
                     Map<String, Boolean> featureMap, ProbeFailure probeFailure, String probeAttemptId) {
            this.id = id; this.name = name; this.kind = kind; this.endpoint = endpoint;
            this.credentialRef = credentialRef; this.transport = transport;
            this.bridgeId = bridgeId; this.instanceId = instanceId;
            this.certSha256 = certSha256 == null ? "" : certSha256;
            this.revision = revision; this.verifiedAt = verifiedAt;
            this.featureMap = featureMap == null ? null : immutableFeatureMap(featureMap);
            this.featureMapAvailable = featureMap != null;
            this.probeFailureCode = probeFailure == null ? "" : probeFailure.name();
            this.probeAttemptId = probeAttemptId == null ? "" : probeAttemptId;
            this.capabilities = featureMap != null ? AgentConnectionStore.enabledNames(this.featureMap) :
                    new ArrayList<>(capabilities == null ? Collections.emptyList() : capabilities);
        }

        StoredConfig withProbeAttempt(String attemptId) {
            return new StoredConfig(id, name, kind, endpoint, credentialRef, transport, bridgeId,
                    instanceId, certSha256, revision, verifiedAt, capabilities,
                    featureMapAvailable ? featureMap : null,
                    ProbeFailure.fromStored(probeFailureCode), attemptId);
        }

        JSONObject toJson() {
            try {
                JSONObject json = new JSONObject().put("id", id).put("name", name)
                    .put("kind", kind.name()).put("endpoint", endpoint)
                    .put("credentialRef", credentialRef).put("transport", transport.name())
                    .put("bridgeId", bridgeId).put("instanceId", instanceId)
                    .put("certSha256", certSha256).put("revision", revision)
                    .put("verifiedAt", verifiedAt).put("capabilities", new JSONArray(capabilities));
                if (featureMapAvailable) {
                    JSONObject map = new JSONObject();
                    for (Map.Entry<String, Boolean> entry : featureMap.entrySet()) {
                        map.put(entry.getKey(), entry.getValue());
                    }
                    json.put("featureMap", map);
                }
                if (!probeFailureCode.isEmpty()) json.put("probeFailureCode", probeFailureCode);
                if (!probeAttemptId.isEmpty()) json.put("probeAttemptId", probeAttemptId);
                return json;
            }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }

        static StoredConfig fromJson(JSONObject json) {
            List<String> capabilities = new ArrayList<>();
            JSONArray values = json.optJSONArray("capabilities");
            if (values != null) {
                for (int index = 0; index < values.length(); index++) {
                    String value = values.optString(index, "");
                    if (!value.isEmpty()) capabilities.add(value);
                }
            }
            Map<String, Boolean> featureMap = null;
            Object rawFeatureMap = json.opt("featureMap");
            if (rawFeatureMap instanceof JSONObject) {
                featureMap = new TreeMap<>();
                JSONObject valuesByName = (JSONObject) rawFeatureMap;
                for (java.util.Iterator<String> keys = valuesByName.keys(); keys.hasNext();) {
                    String key = keys.next();
                    Object value = valuesByName.opt(key);
                    if (value instanceof Boolean) featureMap.put(key, (Boolean) value);
                }
            }
            return new StoredConfig(json.optString("id", ""), json.optString("name", "电脑 Agent"),
                    parseKind(json.optString("kind", Kind.HERMES.name())),
                    json.optString("endpoint", ""), json.optString("credentialRef", ""),
                    parseTransport(json.optString("transport", Transport.DIRECT.name())),
                    json.optString("bridgeId", ""), json.optString("instanceId", ""),
                    json.optString("certSha256", ""),
                    Math.max(1L, json.optLong("revision", 1L)),
                    Math.max(0L, json.optLong("verifiedAt", 0L)), capabilities, featureMap,
                    ProbeFailure.fromStored(json.optString("probeFailureCode", "")),
                    json.optString("probeAttemptId", ""));
        }

    }

    private static final class Registry {
        String defaultId = "";
        final List<StoredConfig> connections = new ArrayList<>();

        StoredConfig find(String id) {
            if (id == null) return null;
            for (StoredConfig config : connections) if (id.equals(config.id)) return config;
            return null;
        }
        void replace(StoredConfig replacement) {
            for (int index = 0; index < connections.size(); index++) {
                if (connections.get(index).id.equals(replacement.id)) {
                    connections.set(index, replacement); return;
                }
            }
        }
        StoredConfig remove(String id) {
            for (int index = 0; index < connections.size(); index++) {
                if (connections.get(index).id.equals(id)) return connections.remove(index);
            }
            return null;
        }
        JSONObject toJson() {
            JSONArray array = new JSONArray();
            for (StoredConfig connection : connections) array.put(connection.toJson());
            try { return new JSONObject().put("schemaVersion", 2).put("defaultId", defaultId)
                    .put("connections", array); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
        static Registry fromJson(JSONObject json) {
            Registry registry = new Registry();
            registry.defaultId = json.optString("defaultId", "");
            JSONArray array = json.optJSONArray("connections");
            Set<String> ids = new LinkedHashSet<>();
            if (array != null) {
                for (int index = 0; index < array.length(); index++) {
                    JSONObject item = array.optJSONObject(index);
                    if (item == null) continue;
                    StoredConfig config = StoredConfig.fromJson(item);
                    if (!config.id.isEmpty() && ids.add(config.id)) registry.connections.add(config);
                }
            }
            if (registry.find(registry.defaultId) == null) {
                registry.defaultId = registry.connections.isEmpty() ? "" : registry.connections.get(0).id;
            }
            return registry;
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
                Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),
                        Base64.NO_WRAP),
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
