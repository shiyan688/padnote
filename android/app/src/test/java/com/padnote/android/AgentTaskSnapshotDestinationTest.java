package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AgentTaskSnapshotDestinationTest {
    @Test public void bridgeSnapshotShowsOnlySafePersistedIdentityAndHostPort() {
        String label = AgentTaskDestinationLabel.format("历史电脑", "HERMES", "BRIDGE",
                "https://alice:token-secret@history.example.test:8443/private/path?token=query-secret#frag-secret",
                "bridge-original", "inst-original", "connection-original");

        assertTrue(label.contains("历史电脑 · Hermes"));
        assertTrue(label.contains("history.example.test:8443"));
        assertTrue(label.contains("助手 bridge-original"));
        assertTrue(label.contains("实例 inst-original"));
        assertFalse(label.contains("https://"));
        assertFalse(label.contains("alice"));
        assertFalse(label.contains("token-secret"));
        assertFalse(label.contains("private/path"));
        assertFalse(label.contains("query-secret"));
        assertFalse(label.contains("frag-secret"));
    }

    @Test public void directSnapshotDoesNotInventBridgeIdentifiers() {
        String label = AgentTaskDestinationLabel.format("桌面 Hermes", "HERMES", "DIRECT",
                "https://direct.example.test:9443/api?key=do-not-show", "bridge-private",
                "instance-private", "direct-profile");

        assertTrue(label.contains("主机 direct.example.test:9443"));
        assertFalse(label.contains("助手"));
        assertFalse(label.contains("bridge-private"));
        assertFalse(label.contains("instance-private"));
        assertFalse(label.contains("/api"));
        assertFalse(label.contains("do-not-show"));
    }

    @Test public void oldOrMalformedSnapshotIsExplicitlyMarkedIncomplete() {
        String label = AgentTaskDestinationLabel.format("旧连接", "FUTURE_KIND", "UNKNOWN",
                "not a URI with private-query=secret", "", "", "");

        assertTrue(label.contains("未知 Agent"));
        assertTrue(label.contains("未标识主机"));
        assertTrue(label.contains("历史目标信息不完整"));
        assertFalse(label.contains("private-query"));
        assertFalse(label.contains("secret"));
    }

    @Test public void urlLikeProfileNameIsNotEchoedAsDisplayName() {
        String label = AgentTaskDestinationLabel.format(
                "https://name-user:name-secret@example.test/?query-secret", "BUILTIN_VIDEO",
                "DIRECT", "https://video.example.test", "", "", "video-profile");

        assertTrue(label.startsWith("电脑连接 · 内置视频"));
        assertFalse(label.contains("name-user"));
        assertFalse(label.contains("name-secret"));
        assertFalse(label.contains("query-secret"));
    }

    @Test public void historyHostUsesHttpDefaultsAndBracketsIpv6Once() {
        String http = AgentTaskDestinationLabel.format("HTTP", "HERMES", "DIRECT",
                "http://alice:secret@history.example.test/private?key=secret#secret",
                "", "", "profile-http");
        String https = AgentTaskDestinationLabel.format("HTTPS", "HERMES", "DIRECT",
                "https://history.example.test/private", "", "", "profile-https");
        String ipv6 = AgentTaskDestinationLabel.format("IPv6", "HERMES", "DIRECT",
                "https://[2001:db8::1]/private", "", "", "profile-ipv6");

        assertTrue(http.contains("history.example.test:80"));
        assertTrue(https.contains("history.example.test:443"));
        assertTrue(ipv6.contains("[2001:db8::1]:443"));
        assertFalse(ipv6.contains("[["));
        assertFalse(http.contains("alice"));
        assertFalse(http.contains("secret"));
    }

    @Test public void unsupportedSchemeAndInvalidExplicitPortsAreUnknown() {
        String[] endpoints = {
                "ftp://host.example.test/file", "file://host.example.test/file",
                "https://host.example.test:0/path", "http://host.example.test:65536/path",
                "https://host.example.test:/path"
        };
        for (int i = 0; i < endpoints.length; i++) {
            String label = AgentTaskDestinationLabel.format("History", "HERMES", "DIRECT",
                    endpoints[i], "", "", "profile-invalid-" + i);
            assertTrue("endpoint must be explicitly unknown: " + endpoints[i],
                    label.contains("主机 未标识主机"));
            assertTrue(label.contains("历史目标信息不完整"));
            assertFalse(label.contains("host.example.test"));
        }
    }

    @Test public void longIdentifiersKeepOnlySafeSuffixWhileShortIdentifiersStayWhole() {
        String shortLabel = AgentTaskDestinationLabel.format("History", "HERMES", "BRIDGE",
                "https://history.example.test", "bridge-01", "inst-original", "profile-01");
        String longLabel = AgentTaskDestinationLabel.format("History", "HERMES", "BRIDGE",
                "https://history.example.test", "bridge-layout-identity-0123456789",
                "instance-layout-identity-0123456789", "profile-01");

        assertTrue(shortLabel.contains("实例 inst-original"));
        assertTrue(longLabel.contains("助手 23456789"));
        assertTrue(longLabel.contains("实例 23456789"));
        assertFalse(longLabel.contains("instance-layout-identity"));
    }

    @Test public void liveProfileLabelPreservesTheEstablishedNonHistoricalFormat() {
        AgentConnectionStore.Config incomplete = new AgentConnectionStore.Config(
                "live-profile-123456789", "  \n  ", AgentConnectionStore.Kind.HERMES,
                "https://live.example.test", "synthetic-token", "synthetic-ref",
                AgentConnectionStore.Transport.BRIDGE, "", "", 1L, 0L,
                java.util.Collections.emptyList());
        AgentConnectionStore.Config urlNamed = new AgentConnectionStore.Config(
                "live-profile-url", "https://profile-name.example/path\n secondary",
                AgentConnectionStore.Kind.OPENCLAW, "https://host.example.test", "synthetic-token",
                "synthetic-ref", AgentConnectionStore.Transport.DIRECT, "", "", 1L, 0L,
                java.util.Collections.emptyList());

        String incompleteLabel = AgentTaskDialogs.destinationLabel(incomplete);
        String urlNameLabel = AgentTaskDialogs.destinationLabel(urlNamed);
        assertTrue(incompleteLabel.startsWith("未命名 Agent · Hermes · 主机 live.example.test"));
        assertTrue(incompleteLabel.contains("实例 未标识"));
        assertFalse(incompleteLabel.contains("历史"));
        assertTrue(urlNameLabel.startsWith("https://profile-name.example/path  secondary · OpenClaw"));
        assertFalse(urlNameLabel.contains("历史"));
    }
}
