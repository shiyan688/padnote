package com.padnote.android;

import java.net.URI;

/** Safe display label from either a live profile or the task's persisted identity snapshot. */
final class AgentTaskDestinationLabel {
    private AgentTaskDestinationLabel() { }


    /** Localized display label for live chooser rows; keeps the legacy pure-Java API stable. */
    static String forProfile(android.content.Context context, AgentConnectionStore.Config profile) {
        if (profile == null) return context.getString(R.string.flow_destination_missing_history);
        String safeName = localizedName(context, profile.name, true);
        String kind;
        switch (profile.kind) {
            case HERMES: kind = "Hermes"; break;
            case OPENCLAW: kind = "OpenClaw"; break;
            case BUILTIN_VIDEO: kind = context.getString(R.string.flow_destination_embedded_video); break;
            default: kind = context.getString(R.string.flow_destination_unknown_agent); break;
        }
        String hostValue = profileHostIdentity(profile.endpoint);
        String host = hasHostIdentity(profile.endpoint, false) ? hostValue
                : context.getString(R.string.flow_destination_unidentified_host);
        String identity;
        if (profile.transport == AgentConnectionStore.Transport.BRIDGE) {
            String bridge = hasSafeIdentifier(profile.bridgeId) ? safeIdentifier(profile.bridgeId)
                    : context.getString(R.string.flow_destination_unidentified);
            String instance = hasSafeIdentifier(profile.instanceId) ? safeIdentifier(profile.instanceId)
                    : context.getString(R.string.flow_destination_unidentified);
            identity = context.getString(R.string.flow_destination_bridge_identity, host, bridge, instance);
        } else {
            identity = context.getString(R.string.flow_destination_host, host);
        }
        String id = hasSafeIdentifier(profile.id) ? safeIdentifier(profile.id)
                : context.getString(R.string.flow_destination_unidentified);
        return context.getString(R.string.flow_destination_label, safeName, kind, identity, id);
    }

    /** Localized display label for task-history snapshots, without changing identity checks. */
    static String forTask(android.content.Context context, AgentTaskStore.Task task) {
        if (task == null) return context.getString(R.string.flow_destination_missing_history);
        String safeName = localizedName(context, task.connectionName, false);
        String kind;
        switch (task.kind == null ? "" : task.kind.name()) {
            case "HERMES": kind = "Hermes"; break;
            case "OPENCLAW": kind = "OpenClaw"; break;
            case "BUILTIN_VIDEO": kind = context.getString(R.string.flow_destination_embedded_video); break;
            default: kind = context.getString(R.string.flow_destination_unknown_agent); break;
        }
        boolean bridge = "BRIDGE".equals(task.transport == null ? "" : task.transport.name());
        boolean direct = "DIRECT".equals(task.transport == null ? "" : task.transport.name());
        boolean hostPresent = hasHostIdentity(task.origin, true);
        String host = hostPresent ? safeHistoryHostIdentity(task.origin)
                : context.getString(R.string.flow_destination_unidentified_host);
        boolean bridgePresent = hasSafeIdentifier(task.bridgeId);
        boolean instancePresent = hasSafeIdentifier(task.instanceId);
        boolean connectionPresent = hasSafeIdentifier(task.connectionId);
        String identity;
        if (bridge) {
            String bridgeId = bridgePresent ? safeIdentifier(task.bridgeId)
                    : context.getString(R.string.flow_destination_unidentified);
            String instanceId = instancePresent ? safeIdentifier(task.instanceId)
                    : context.getString(R.string.flow_destination_unidentified);
            identity = context.getString(R.string.flow_destination_bridge_identity,
                    host, bridgeId, instanceId);
        } else {
            identity = context.getString(R.string.flow_destination_host, host);
        }
        String id = connectionPresent ? safeIdentifier(task.connectionId)
                : context.getString(R.string.flow_destination_unidentified);
        String result = context.getString(R.string.flow_destination_label, safeName, kind, identity, id);
        boolean incomplete = task.connectionName == null || task.connectionName.trim().isEmpty()
                || !("HERMES".equals(task.kind == null ? "" : task.kind.name())
                || "OPENCLAW".equals(task.kind == null ? "" : task.kind.name())
                || "BUILTIN_VIDEO".equals(task.kind == null ? "" : task.kind.name()))
                || (!bridge && !direct) || !hostPresent || !connectionPresent
                || (bridge && (!bridgePresent || !instancePresent));
        return incomplete ? context.getString(R.string.flow_destination_incomplete, result) : result;
    }

    private static String localizedName(android.content.Context context, String value, boolean profile) {
        if (value == null || value.trim().isEmpty()) return context.getString(profile
                ? R.string.flow_destination_unnamed_agent : R.string.flow_destination_unnamed_connection);
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            safe.append(Character.isISOControl(c) ? ' ' : c);
        }
        String normalized = safe.toString().trim();
        if (normalized.isEmpty()) return context.getString(profile
                ? R.string.flow_destination_unnamed_agent : R.string.flow_destination_unnamed_connection);
        return normalized.contains("://") ? context.getString(R.string.flow_destination_computer)
                : normalized;
    }

    private static boolean hasSafeIdentifier(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9') || c == '-' || c == '_') return true;
        }
        return false;
    }

    private static boolean hasHostIdentity(String endpoint, boolean strict) {
        try {
            URI uri = URI.create(endpoint == null ? "" : endpoint);
            String scheme = uri.getScheme();
            if (strict && (scheme == null || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)))) return false;
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return false;
            if (strict) {
                String authority = uri.getRawAuthority();
                if (authority == null || authority.endsWith(":")) return false;
                int port = uri.getPort();
                if (port == 0 || port > 65535 || port < -1) return false;
            }
            return true;
        } catch (RuntimeException ignored) { return false; }
    }

    static String forProfile(AgentConnectionStore.Config profile) {
        if (profile == null) return "未知目标（历史记录缺少连接信息）";
        String name = profile.name == null ? "" : profile.name.trim()
                .replace('\n', ' ').replace('\r', ' ');
        if (name.isEmpty()) name = "未命名 Agent";
        String kind;
        switch (profile.kind) {
            case HERMES: kind = "Hermes"; break;
            case OPENCLAW: kind = "OpenClaw"; break;
            case BUILTIN_VIDEO: kind = "内置视频"; break;
            default: kind = "未知 Agent";
        }
        String host = profileHostIdentity(profile.endpoint);
        String identity = profile.transport == AgentConnectionStore.Transport.BRIDGE
                ? "主机 " + host + " / 助手 " + safeIdentifier(profile.bridgeId) +
                        " / 实例 " + safeIdentifier(profile.instanceId)
                : "主机 " + host;
        return name + " · " + kind + " · " + identity + " · #" + safeIdentifier(profile.id);
    }

    static String forTask(AgentTaskStore.Task task) {
        if (task == null) return "未知目标（历史记录缺少连接信息）";
        return formatInternal(task.connectionName, task.kind == null ? "" : task.kind.name(),
                task.transport == null ? "" : task.transport.name(), task.origin,
                task.bridgeId, task.instanceId, task.connectionId);
    }

    static String format(String name, String kind, String transport, String endpoint,
                         String bridgeId, String instanceId, String connectionId) {
        return formatInternal(name, kind, transport, endpoint, bridgeId, instanceId,
                connectionId);
    }

    private static String formatInternal(String name, String kind, String transport,
                                         String endpoint, String bridgeId, String instanceId,
                                         String connectionId) {
        String safeName = displayName(name);
        String safeKind = kindLabel(kind);
        String host = safeHistoryHostIdentity(endpoint);
        boolean bridge = "BRIDGE".equals(transport);
        boolean direct = "DIRECT".equals(transport);
        boolean incomplete = name == null || name.trim().isEmpty() ||
                safeKind.equals("未知 Agent") || (!bridge && !direct) ||
                host.equals("未标识主机") || safeIdentifier(connectionId).equals("未标识");
        String identity;
        if (bridge) {
            String safeBridge = safeIdentifier(bridgeId);
            String safeInstance = safeIdentifier(instanceId);
            incomplete |= safeBridge.equals("未标识") || safeInstance.equals("未标识");
            identity = "主机 " + host + " / 助手 " + safeBridge + " / 实例 " + safeInstance;
        } else {
            identity = "主机 " + host;
        }
        String result = safeName + " · " + safeKind + " · " + identity + " · #" +
                safeIdentifier(connectionId);
        return incomplete ? result + " · 历史目标信息不完整" : result;
    }

    private static String displayName(String value) {
        if (value == null || value.trim().isEmpty()) return "未命名连接";
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) safe.append(' ');
            else safe.append(c);
        }
        String normalized = safe.toString().trim();
        // Profile names are user-controlled too; never echo a URL-looking string as a label.
        if (normalized.contains("://")) return "电脑连接";
        return normalized.isEmpty() ? "未命名连接" : normalized;
    }

    private static String kindLabel(String value) {
        if ("HERMES".equals(value)) return "Hermes";
        if ("OPENCLAW".equals(value)) return "OpenClaw";
        if ("BUILTIN_VIDEO".equals(value)) return "内置视频";
        return "未知 Agent";
    }

    private static String safeHistoryHostIdentity(String endpoint) {
        try {
            URI uri = URI.create(endpoint == null ? "" : endpoint);
            String scheme = uri.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme) ||
                    "https".equalsIgnoreCase(scheme))) return "未标识主机";
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "未标识主机";
            int port = uri.getPort();
            String authority = uri.getRawAuthority();
            if (authority == null) return "未标识主机";
            int userInfoEnd = authority.lastIndexOf('@');
            String hostAndPort = userInfoEnd >= 0 ? authority.substring(userInfoEnd + 1) : authority;
            if (hostAndPort.endsWith(":")) return "未标识主机";
            if (port == 0 || port > 65535 || port < -1) return "未标识主机";
            if (port == -1) port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
                host = "[" + host + "]";
            }
            return host + ":" + port;
        } catch (RuntimeException ignored) {
            return "未标识主机";
        }
    }

    /** Keeps existing live-profile chooser labels stable; strict normalization is history-only. */
    private static String profileHostIdentity(String endpoint) {
        try {
            URI uri = URI.create(endpoint == null ? "" : endpoint);
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "未标识主机";
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            int port = uri.getPort();
            return port > 0 && port <= 65535 ? host + ":" + port : host;
        } catch (RuntimeException ignored) {
            return "未标识主机";
        }
    }

    private static String safeIdentifier(String value) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; value != null && i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9') || c == '-' || c == '_') safe.append(c);
        }
        if (safe.length() == 0) return "未标识";
        return safe.length() <= 16 ? safe.toString() : safe.substring(safe.length() - 8);
    }
}
