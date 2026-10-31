from __future__ import annotations

import unittest
from html.parser import HTMLParser
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
        self.assertIn('<a class="pairing-shortcut" href="#pairing">查看当前配对码', page)

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
        self.assertIn("完成凭据或本机服务配置后，再运行接口检测。", page)
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
        self.assertIn("接口已通过检测。可以选择此 Agent 并为平板生成配对码。", page)
        self.assertIn('<details class="agent-details">', page)
        self.assertNotIn('class="pairing-shortcut"', page)

    def test_ready_onboarding_uses_four_actionable_anchor_links(self):
        ready = instance("agent-ready", "Ready Agent", executable=True, health="ready")
        ready["checked_at"] = 99
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status", return_value={"status": "ready"}):
            page = self.render(service([ready]))
        self.assertIn('class="onboarding-grid onboarding-ready"', page)
        for href in ('href="#agents"', 'href="#pairing"', 'href="#requests"', 'href="#first-task"'):
            self.assertIn(href, page)
        self.assertIn('class="onboarding-progress"', page)
        self.assertNotIn('aria-label="连接进度"', page)

    def test_anchor_targets_are_unique_and_first_task_is_manual_safe(self):
        class Structure(HTMLParser):
            def __init__(self):
                super().__init__()
                self.ids, self.hrefs = [], []
                self.in_first = False
                self.in_progress = False
                self.first_form_count = 0
                self.first_submit_count = 0
            def handle_starttag(self, tag, attrs):
                attrs = dict(attrs)
                if "id" in attrs:
                    self.ids.append(attrs["id"])
                if tag == "nav" and ("onboarding-progress" in attrs.get("class", "") or "onboarding-steps" in attrs.get("class", "")):
                    self.in_progress = True
                elif tag == "a" and self.in_progress and attrs.get("href", "").startswith("#"):
                    self.hrefs.append(attrs["href"][1:])
                if tag == "section" and attrs.get("id") == "first-task":
                    self.in_first = True
                elif self.in_first and tag == "form":
                    self.first_form_count += 1
                elif self.in_first and tag == "button" and attrs.get("type", "submit") == "submit":
                    self.first_submit_count += 1
            def handle_endtag(self, tag):
                if tag == "section" and self.in_first:
                    self.in_first = False
                if tag == "nav" and self.in_progress:
                    self.in_progress = False
        page = self.render(service([]))
        parsed = Structure()
        parsed.feed(page)
        for target in ("agents", "pairing", "requests", "first-task"):
            self.assertEqual(1, parsed.hrefs.count(target), target)
            self.assertEqual(1, parsed.ids.count(target), target)
        first = page.split('id="first-task"', 1)[1].split("</section>", 1)[0]
        self.assertEqual(0, parsed.first_form_count)
        self.assertEqual(0, parsed.first_submit_count)
        self.assertIn("只回复连接成功，不要操作文件", first)
        self.assertIn("这里只提供操作指引，不会自动发送任务。", first)

    def test_stale_agent_navigation_does_not_claim_connected(self):
        stale = instance("agent-stale", "Stale", executable=True, health="ready")
        stale["checked_at"] = 39
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status", return_value={"status": "ready"}):
            page = self.render(service([stale]))
        nav_start = page.index("<nav class=\"onboarding-", page.index('id="getting-started"'))
        nav = page[nav_start:page.index("</nav>", nav_start)]
        self.assertIn('href="#agents"', nav)
        self.assertNotIn('class="complete"', nav)
        self.assertIn("检测结果已过期", page)

    def test_check_age_uses_checked_at_and_marks_stale_or_invalid_unknown(self):
        stale = instance("agent-stale", "Stale", executable=True, health="ready")
        stale["checked_at"] = 39
        future = instance("agent-future", "Future", executable=True, health="ready")
        future["checked_at"] = 101
        page = self.render(service([stale, future]))
        self.assertIn("上次检测：1 分钟前 · 结果已过期", page)
        self.assertIn("检测结果已过期", page)
        self.assertIn("上次检测结果已过期。重新检测接口后，再继续配对。", page)
        stale_card = page.split('data-check-age="61"', 1)[1].split('</article>', 1)[0]
        self.assertLess(stale_card.index("重新检测接口"), stale_card.index('class="button button-quiet" href="#pairing"'))
        self.assertIn("未知 · 需要重新检测", page)
        self.assertNotIn("上次检测：-", page)

    def test_onboarding_tutorial_and_not_found_guidance_offers_manual_api_path(self):
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status",
                   return_value={"status": "not_installed"}):
            page = self.render(service([]))
        self.assertIn("从 Agent 到平板，按顺序完成", page)
        self.assertIn("检查 Agent", page)
        self.assertIn("再为平板生成配对码", page)
        self.assertIn("核对设备名称与 Agent，再批准", page)
        self.assertIn("常见位置未找到 Hermes", page)
        self.assertIn("只检查了已知安装位置和 PATH", page)
        self.assertIn('href="#add-agent" data-open-manual-hermes', page)
        self.assertIn("https://hermes-agent.nousresearch.com/docs/getting-started/installation", page)
        self.assertIn('id="manual-api-endpoint" name="base_url"', page)
        self.assertNotIn("<script>", page)

    def test_ambiguous_status_links_to_discovered_profile_selection(self):
        svc = service([], discovery_results=[
            {"kind": "hermes", "path": "/home/demo/.hermes/.env", "guidance": "Known config"},
            {"kind": "hermes", "path": "/home/demo/.config/hermes/.env", "guidance": "Known config"},
        ])
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status",
                   return_value={"status": "ambiguous"}):
            page = self.render(svc)
        self.assertIn("发现多个 Hermes 配置", page)
        self.assertIn('href="#discovered-configs" data-open-discovery', page)
        self.assertIn('<details id="discovered-configs" class="advanced discovery">', page)
        self.assertIn("使用此配置</button>", page)
        self.assertIn('href="#add-agent" data-open-manual-hermes', page)
        self.assertNotIn("请在 Hermes 配置向导中选择", page)

    def test_hermes_status_message_is_html_escaped(self):
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status", return_value={
            "status": "error", "message": '<img src=x onerror="alert(1)">',
        }):
            page = self.render(service([]))
        self.assertIn("&lt;img src=x onerror=&quot;alert(1)&quot;&gt;", page)
        self.assertNotIn('<img src=x', page)
        self.assertIn("请确认 Hermes 已安装并完成配置，然后重试。", page)
        self.assertIn('<details class="onboarding-detail">', page)

    def test_hermes_primary_help_uses_status_copy_and_folds_raw_backend_detail(self):
        with patch("padnote_assistant.admin_ui.hermes_onboarding_status", return_value={
            "status": "not_installed", "message": "Hermes was not found on this computer.",
        }):
            page = self.render(service([]))
        self.assertIn("常见位置未找到 Hermes", page)
        self.assertIn("若 Hermes 位于 WSL 或自定义目录", page)
        details = page.split('<details class="onboarding-detail">', 1)[1].split('</details>', 1)[0]
        self.assertIn("Hermes was not found on this computer.", details)
        self.assertNotIn("Hermes was not found on this computer.</p><a", page)


if __name__ == "__main__":
    unittest.main()
