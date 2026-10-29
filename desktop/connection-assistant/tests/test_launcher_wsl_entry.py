"""Focused policy and UI wiring tests for the native WSL page entry."""
from __future__ import annotations

import ast
from pathlib import Path
import unittest


LAUNCHER = Path(__file__).resolve().parents[1] / "windows-setup" / "launcher.py"
TREE = ast.parse(LAUNCHER.read_text(encoding="utf-8"), filename=str(LAUNCHER))
HELPER_NAMES = {
    "wsl_navigation_click_action",
    "wsl_navigation_readiness_action",
    "wsl_entry_button_rect",
    "wsl_entry_layout",
}
HELPERS = {node.name: node for node in TREE.body
           if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in HELPER_NAMES}
NAMESPACE: dict[str, object] = {}
exec(compile(ast.Module(body=list(HELPERS.values()), type_ignores=[]), str(LAUNCHER), "exec"), NAMESPACE)


def rects_intersect(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    return ax < bx + bw and bx < ax + aw and ay < by + bh and by < ay + ah


class LauncherWslEntryTests(unittest.TestCase):
    def test_stopped_service_starts_padnote_then_late_ready_opens_only_the_page(self):
        click = NAMESPACE["wsl_navigation_click_action"]
        readiness = NAMESPACE["wsl_navigation_readiness_action"]
        action = click(ready=False, can_start=True, stopping=False, closing=False,
                       pending=False, cooldown_active=False)
        self.assertEqual(action, "start_service")
        # A start request does not navigate before readiness and remains pending
        # while the service takes longer than the ordinary startup hint window.
        self.assertEqual(readiness(pending=True, ready=False, closing=False), "wait")
        self.assertEqual(readiness(pending=True, ready=False, closing=False), "wait")
        self.assertEqual(readiness(pending=True, ready=True, closing=False, cooldown_active=True), "wait")
        self.assertEqual(readiness(pending=True, ready=True, closing=False, cooldown_active=False), "open")

    def test_ready_service_opens_and_double_click_cooldown_allows_later_reopen(self):
        click = NAMESPACE["wsl_navigation_click_action"]
        readiness = NAMESPACE["wsl_navigation_readiness_action"]
        args = dict(ready=True, can_start=False, stopping=False, closing=False, pending=False)
        self.assertEqual(click(**args, cooldown_active=False), "open")
        self.assertEqual(readiness(pending=False, ready=True, closing=False), "none")
        self.assertEqual(click(**args, cooldown_active=True), "cooldown")
        # After the brief cooldown, a new explicit click can open the page again.
        self.assertEqual(click(**args, cooldown_active=False), "open")

    def test_repeated_pending_request_is_deduplicated(self):
        click = NAMESPACE["wsl_navigation_click_action"]
        self.assertEqual(click(ready=False, can_start=False, stopping=False, closing=False,
                               pending=True, cooldown_active=False), "already_pending")

    def test_stop_exit_and_close_cancel_pending_navigation(self):
        readiness = NAMESPACE["wsl_navigation_readiness_action"]
        self.assertEqual(readiness(pending=True, ready=False, closing=False, process_exited=True), "cancel")
        self.assertEqual(readiness(pending=True, ready=True, closing=True), "cancel")
        # Stop button clears its pending flag before stopping the service.
        self.assertEqual(readiness(pending=False, ready=False, closing=False), "none")

    def test_button_geometry_has_no_overlap_at_narrow_and_wide_widths(self):
        layout = NAMESPACE["wsl_entry_layout"]
        for width in (620, 680, 820):
            regions = layout(width)
            button = regions["button"]
            heading = regions["tutorial_heading"]
            self.assertEqual(button[3], 36)
            self.assertFalse(rects_intersect(button, heading), f"width={width}: {button} overlaps {heading}")

    def test_button_is_focusable_and_route_has_no_hermes_action(self):
        wndproc = next(node for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name == "wndproc")
        branches = [node for node in ast.walk(wndproc) if isinstance(node, ast.If)
                    and isinstance(node.test, ast.Compare)
                    and isinstance(node.test.left, ast.Name) and node.test.left.id == "ident"
                    and node.test.comparators and isinstance(node.test.comparators[0], ast.Name)
                    and node.test.comparators[0].id == "BUTTON_WSL_FIND"]
        self.assertEqual(len(branches), 1)
        called = {node.func.id for node in ast.walk(branches[0]) if isinstance(node, ast.Call)
                  and isinstance(node.func, ast.Name)}
        self.assertIn("open_wsl_management_page", called)
        self.assertIn("start_service", called)
        self.assertNotIn("start_hermes_action", called)
        wsl_control = next(node for node in ast.walk(wndproc) if isinstance(node, ast.Call)
                           and isinstance(node.func, ast.Name) and node.func.id == "control"
                           and len(node.args) > 6 and isinstance(node.args[1], ast.Constant)
                           and node.args[1].value == "在 WSL 中查找")
        self.assertIsInstance(wsl_control.args[6], ast.Name)
        self.assertEqual(wsl_control.args[6].id, "BUTTON_WSL_FIND")
        control = next(node for node in TREE.body if isinstance(node, ast.FunctionDef) and node.name == "control")
        self.assertIn("WS_TABSTOP", ast.dump(control))
        self.assertIn('WSL_MANAGEMENT_URL = "http://127.0.0.1:8766/#wsl-hermes"', LAUNCHER.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
