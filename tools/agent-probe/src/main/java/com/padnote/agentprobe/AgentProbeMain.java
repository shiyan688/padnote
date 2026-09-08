package com.padnote.agentprobe;

import org.json.JSONObject;

import java.net.URI;
import java.nio.file.Path;

public final class AgentProbeMain {
    private AgentProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("hermes".equals(args[0]) || "openclaw".equals(args[0]))) {
            throw new IllegalArgumentException("用法：run-agent-probe.sh hermes|openclaw");
        }
        String endpoint = requiredEnvironment("PADNOTE_AGENT_URL");
        String token = System.getenv().getOrDefault("PADNOTE_AGENT_TOKEN", "");

        JSONObject result;
        if ("hermes".equals(args[0])) {
            result = new HermesProbe(URI.create(endpoint), token).probe();
        } else {
            String configuredState = System.getenv("PADNOTE_AGENT_STATE_DIR");
            Path stateDirectory = configuredState == null || configuredState.isBlank()
                    ? Path.of("tools", "agent-probe", "state")
                    : Path.of(configuredState);
            result = new OpenClawProbe(URI.create(endpoint), token, stateDirectory).probe();
        }
        System.out.println(result.toString(2));
        if (!result.getBoolean("ready")) {
            System.exit(2);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量 " + name);
        }
        return value.trim();
    }
}
