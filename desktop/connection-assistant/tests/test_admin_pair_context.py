from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from padnote_assistant.admin_ui import render_admin_page
from padnote_assistant.bridge import BridgeService


class AdminPairContextTests(unittest.TestCase):
    def test_real_pair_code_keeps_selected_instance_context_for_rendering(self) -> None:
        with tempfile.TemporaryDirectory(prefix="padnote-pair-context-") as temporary:
            service = BridgeService(Path(temporary))
            try:
                service.lan_identity = SimpleNamespace(sha256="f" * 64)
                service.lan_port = 8767
                first = service.add_instance("hermes", "First Hermes", "http://127.0.0.1:8642", "first-test-key")
                second = service.add_instance("hermes", "Second Hermes", "http://127.0.0.1:8643", "second-test-key")
                for item in (first, second):
                    service.store.update_instance_check(
                        item["instance_id"], health="ready", detail="ready",
                        features={"task_bundle": True}, executable=True)

                payload = service.create_pair_payload(second["instance_id"], "https://computer.example.ts.net")

                # The tablet protocol payload intentionally carries no instance id.
                self.assertNotIn("instance_id", payload)
                context = service.last_pair_context
                self.assertEqual(context["payload"], payload)
                self.assertEqual(context["instance_id"], second["instance_id"])
                self.assertEqual(context["expires_at"], service.last_pair_expires_at)
                self.assertEqual(service.last_pair_payload, payload)

                with patch("padnote_assistant.lan.lan_addresses", return_value=["192.168.1.10"]):
                    page = render_admin_page(service, "csrf", 8766, 8765, "", lambda _text: "<svg>real-qr</svg>")
                self.assertIn('<h3>Second Hermes</h3>', page)
                self.assertIn('data-pair-agent="' + second["instance_id"] + '"', page)
                self.assertIn("<svg>real-qr</svg>", page)
                self.assertIn('value="' + second["instance_id"] + '" selected>Second Hermes</option>', page)

                lan_payload = service.create_lan_pair_payload(first["instance_id"], "192.168.1.10")
                self.assertNotIn("instance_id", lan_payload)
                self.assertEqual(service.last_pair_context["payload"], lan_payload)
                self.assertEqual(service.last_pair_context["instance_id"], first["instance_id"])
            finally:
                service.close()


if __name__ == "__main__":
    unittest.main()
