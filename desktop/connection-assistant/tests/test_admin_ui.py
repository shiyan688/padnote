from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest.mock import patch

from padnote_assistant.admin_ui import render_admin_page


class Store:
    def __init__(self, instances):
        self.instances = instances

    def list_instances(self):
        return self.instances

    def list_pending_pairs(self):
        return []

    def list_connections(self):
        return []


def instance(iid, name, *, executable=True, health="ready", credential=True):
    return {"instance_id": iid, "name": name, "created_at": 1,
            "kind": "hermes", "base_url": "http://127.0.0.1:8642",
            "health": health, "detail": "Details", "features": {},
            "executable": executable,
            "credential_fingerprint": "fingerprint" if credential else ""}


def service(instances, **overrides):
    values = {
        "store": Store(instances), "lan_identity": SimpleNamespace(sha256="f" * 64),
        "lan_port": 1234, "last_pair_context": None,
        "clock": lambda: 100,
        "discovery_results": [],
    }
    values.update(overrides)
    return SimpleNamespace(**values)


class AdminUiTests(unittest.TestCase):
    def render(self, svc):
        return render_admin_page(svc, "csrf-value", 2345, 3456, "", lambda _: "<svg></svg>")

    def test_pair_target_is_selected_and_matches_pair_card(self):
        first = instance("agent-a", "First Agent")
        second = instance("agent-b", "Second Agent")
        svc = service([first, second],
                      last_pair_context={"payload": {"type": "padnote-pair", "version": 1,
                                                      "url": "https://host", "code": "secret"},
                                        "instance_id": "agent-b", "expires_at": 150})
        with patch("padnote_assistant.lan.lan_addresses", return_value=["192.168.1.10"]):
            page = self.render(svc)
        self.assertIn('<option value="agent-b" selected>Second Agent</option>', page)
        self.assertIn('<h3>Second Agent</h3>', page)
        self.assertIn('data-pair-agent="agent-b"', page)
        self.assertIn('data-pair-instance="agent-b"', page)
        self.assertIn("使用平板上的 PadNote 扫描二维码", page)
        self.assertIn("返回电脑批准申请", page)
        self.assertIn('<details class="pair-technical">', page)
        self.assertNotIn('<details class="pair-technical" open', page)

    def test_active_lan_without_agents_explains_next_step_before_address(self):
        svc = service([])
        with patch("padnote_assistant.lan.lan_addresses", return_value=[]):
            page = self.render(svc)
        self.assertIn("还没有可连接的 Agent", page)
        self.assertIn("先添加 Hermes 并完成连接检测", page)
        self.assertNotIn("找不到电脑的局域网地址", page)

    def test_not_ready_card_has_primary_check_action_and_localized_health(self):
        svc = service([instance("agent-a", "First Agent", executable=False,
                                health="needs_configuration")])
        page = self.render(svc)
        self.assertIn("需要配置凭据或本机引擎", page)
        self.assertIn('action="/admin/instances/agent-a/check"><input', page)
        self.assertIn("检测连接</button>", page)
        self.assertIn("平板", page)
        self.assertNotIn("iPad", page)


if __name__ == "__main__":
    unittest.main()
