package com.padnote.agentprobe;

import java.net.URI;
import java.util.Set;

final class EndpointPolicy {
    private EndpointPolicy() {
    }

    static URI validate(URI endpoint, Set<String> schemes, boolean hasCredential) {
        String scheme = endpoint.getScheme();
        if (scheme == null || !schemes.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("不支持的 Agent 地址协议：" + endpoint);
        }
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Agent 地址必须是无账号、查询串和片段的绝对地址");
        }
        boolean encrypted = "https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme);
        if (hasCredential && !encrypted && !isLoopback(endpoint.getHost())) {
            throw new IllegalArgumentException("携带凭据时仅允许加密地址或本机回环地址");
        }
        return endpoint;
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase();
        return "localhost".equals(normalized) || "::1".equals(normalized)
                || normalized.startsWith("127.");
    }
}
