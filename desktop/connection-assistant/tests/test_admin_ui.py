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
        self.assertIn("状态未知 · 需要重新检测", page)
        self.assertIn('action="/admin/instances/agent-a/check" data-agent-check><input', page)
        self.assertIn("检测 Agent 接口</button>", page)
        self.assertIn("未知 · 需要重新检测", page)
        self.assertIn("平板", page)
        self.assertNotIn("iPad", page)

    def test_ready_hermes_card_keeps_single_check_form_and_clear_health_label(self):
        ready = instance("agent-ready", "Ready Agent", executable=True, health="ready")
        ready["checked_at"] = 99
        svc = service([ready])
        page = self.render(svc)
        self.assertIn("上次检测通过", page)
        self.assertIn('action="/admin/instances/agent-ready/check" data-agent-check><input', page)
        self.assertEqual(1, page.count('data-agent-check'))
        self.assertEqual(1, page.count('action="/admin/instances/agent-ready/check"'))
        self.assertEqual(1, page.count('data-check-button'))

    def test_check_age_uses_checked_at_and_marks_stale_or_invalid_unknown(self):
        stale = instance("agent-stale", "Stale", executable=True, health="ready")
        stale["checked_at"] = 39
        future = instance("agent-future", "Future", executable=True, health="ready")
        future["checked_at"] = 101
        page = self.render(service([stale, future]))
        self.assertIn("上次检测：1 分钟前 · 结果已过期", page)
        self.assertIn("检测结果已过期", page)
        self.assertIn("未知 · 需要重新检测", page)
        self.assertNotIn("上次检测：-", page)

    def test_onboarding_tutorial_and_missing_hermes_install_link_are_rendered(self):
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status",
                   return_value={"status": "not_installed"}):
            page = self.render(service([]))
        self.assertIn("三步连接平板", page)
        self.assertIn("办公室公共网络可能会阻止设备互连", page)
        self.assertIn("平板扫描二维码后", page)
        self.assertIn("https://hermes-agent.nousresearch.com/docs/getting-started/installation", page)
        self.assertNotIn("<script>", page)

    def test_hermes_status_message_is_html_escaped(self):
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status", return_value={
            "status": "error", "message": '<img src=x onerror="alert(1)">',
        }):
            page = self.render(service([]))
        self.assertIn("&lt;img src=x onerror=&quot;alert(1)&quot;&gt;", page)
        self.assertNotIn('<img src=x', page)


if __name__ == "__main__":
    unittest.main()
