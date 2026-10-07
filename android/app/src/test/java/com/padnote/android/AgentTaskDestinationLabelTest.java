package com.padnote.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

public final class AgentTaskDestinationLabelTest {
    @Test public void sameDisplayNameHasDistinctSafeBridgeDestinations() {
        AgentConnectionStore.Config first = bridge("11111111-aaaa", "bridge-alpha", "instance-01", "sensitive-token-one");
        AgentConnectionStore.Config second = bridge("22222222-bbbb", "bridge-beta", "instance-02", "sensitive-token-two");

        String firstLabel = AgentTaskDialogs.destinationLabel(first);
        String secondLabel = AgentTaskDialogs.destinationLabel(second);
        assertNotEquals(firstLabel, secondLabel);
        assertTrue(firstLabel.contains("Hermes"));
        assertTrue(firstLabel.contains("bridge-alpha"));
        assertTrue(firstLabel.contains("instance-01"));
        assertFalse(firstLabel.contains(first.token));
        assertFalse(secondLabel.contains(second.token));
        assertFalse(firstLabel.contains("https://"));
    }

    @Test public void directDestinationShowsOnlyParsedHostNotEndpointSecrets() {
        AgentConnectionStore.Config direct = new AgentConnectionStore.Config(
                "profile-12345678", "同名电脑", AgentConnectionStore.Kind.OPENCLAW,
                "https://user:password@host.example.test/path?token=query-secret#fragment-secret",
                "credential-secret", "ref-secret", AgentConnectionStore.Transport.DIRECT,
                "", "", 1, 1, Collections.emptyList());

        String label = AgentTaskDialogs.destinationLabel(direct);
        assertTrue(label.contains("OpenClaw"));
        assertTrue(label.contains("host.example.test"));
        assertFalse(label.contains("password"));
        assertFalse(label.contains("query-secret"));
        assertFalse(label.contains("fragment-secret"));
        assertFalse(label.contains("credential-secret"));
        assertFalse(label.contains("ref-secret"));
    }

    @Test public void builtinVideoKindAndDirectHostPortRemainDistinct() {
        AgentConnectionStore.Config video = new AgentConnectionStore.Config(
                "video-profile-12345678", "同名电脑", AgentConnectionStore.Kind.BUILTIN_VIDEO,
                "https://user:password@host.example.test:8443/path?token=query-secret#fragment-secret",
                "credential-secret", "ref-secret", AgentConnectionStore.Transport.BRIDGE,
                "bridge-video", "instance-video-01", 1, 1,
                Collections.singletonList("video_task_submission"));
        AgentConnectionStore.Config firstPort = direct("same-name-a", "https://same.example.test:8443/path?secret=x");
        AgentConnectionStore.Config secondPort = direct("same-name-b", "https://same.example.test:9443/path?secret=y");

        String videoLabel = AgentTaskDialogs.destinationLabel(video);
        assertTrue(videoLabel.contains("内置视频"));
        assertFalse(videoLabel.contains("OpenClaw"));
        assertTrue(videoLabel.contains("host.example.test:8443"));
        assertTrue(videoLabel.contains("bridge-video"));
        assertTrue(videoLabel.contains("video-01"));
        assertFalse(videoLabel.contains("password"));
        assertFalse(videoLabel.contains("query-secret"));
        assertFalse(videoLabel.contains("fragment-secret"));
        assertFalse(videoLabel.contains("credential-secret"));

        String firstLabel = AgentTaskDialogs.destinationLabel(firstPort);
        String secondLabel = AgentTaskDialogs.destinationLabel(secondPort);
        assertNotEquals(firstLabel, secondLabel);
        assertTrue(firstLabel.contains("same.example.test:8443"));
        assertTrue(secondLabel.contains("same.example.test:9443"));
    }

    private static AgentConnectionStore.Config bridge(String id, String bridge, String instance, String token) {
        return new AgentConnectionStore.Config(id, "同名电脑", AgentConnectionStore.Kind.HERMES,
                "https://unused.example.test", token, "ref-" + token,
                AgentConnectionStore.Transport.BRIDGE, bridge, instance, 1, 1,
                Collections.singletonList("task_bundle"));
    }

    private static AgentConnectionStore.Config direct(String id, String endpoint) {
        return new AgentConnectionStore.Config(id, "同名电脑", AgentConnectionStore.Kind.HERMES,
                endpoint, "private-token", "private-ref", AgentConnectionStore.Transport.DIRECT,
                "", "", 1, 1, Collections.emptyList());
    }
}
