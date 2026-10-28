from __future__ import annotations

import unittest
from unittest.mock import patch

from padnote_assistant.hermes import (
    decode_approval_token,
    encode_approval_token,
    normalize_approval,
    normalize_run,
    HermesClient,
    HermesError,
)


class HermesControlIdentityTests(unittest.TestCase):
    def test_codec_round_trip_preserves_colons_in_prompt_id(self):
        token = encode_approval_token("prompt:with:colons", 7)
        self.assertEqual("pc1:7:prompt:with:colons", token)
        self.assertEqual(("prompt:with:colons", 7), decode_approval_token(token))

    def test_codec_rejects_bool_generation_noncanonical_integer_and_c1(self):
        with self.assertRaises(ValueError):
            encode_approval_token("prompt", True)
        for token in ("pc1:01:prompt", "pc1:١:prompt", "pc1:1:bad\x85id"):
            with self.subTest(token=repr(token)), self.assertRaises(ValueError):
                decode_approval_token(token)
        with self.assertRaises(ValueError):
            encode_approval_token("bad\x85id", 1)

    def test_codec_limits_utf8_bytes_not_character_count(self):
        prompt_id = "🚀" * 64
        self.assertLessEqual(len(f"pc1:1:{prompt_id}"), 256)
        with self.assertRaises(ValueError):
            encode_approval_token(prompt_id, 1)
        with self.assertRaises(ValueError):
            decode_approval_token(f"pc1:1:{prompt_id}")

    def test_legacy_approval_has_no_clickable_identity(self):
        approval, error = normalize_approval(
            {"approval": {"request_id": "old-raw-id", "title": "Review", "reason": "Check"}},
            "waiting_for_approval",
        )
        self.assertEqual("", approval["approval_id"])
        self.assertIn("Check", approval["description"])
        self.assertTrue(error)

    def test_canonical_pending_control_gets_bound_token(self):
        approval, error = normalize_approval({
            "execution_generation": 42,
            "pending_controls": [{"kind": "approval", "prompt_id": "prompt:42",
                                  "payload": {"title": "Review", "reason": "Check"}}],
        }, "waiting_for_approval")
        self.assertIsNone(error)
        self.assertEqual("pc1:42:prompt:42", approval["approval_id"])

    def test_identity_only_payload_does_not_offer_clickable_approval(self):
        approval, error = normalize_approval({
            "execution_generation": 42,
            "pending_controls": [{"kind": "approval", "prompt_id": "prompt:42",
                                  "payload": {"request_id": "raw", "session_id": "s"}}],
        }, "waiting_for_approval")
        self.assertEqual("", approval["approval_id"])
        self.assertTrue(error)
        self.assertIn("电脑端处理", approval["description"])

    def test_title_and_blank_details_do_not_authorize_but_command_args_do(self):
        for payload in ({"title": "Review"}, {"tool_name": "shell"},
                        {"command": " \n ", "args": {}}, {"request_id": "raw", "args": [None, ""]},
                        {"command": {}}, {"reason": []}, {"description": {"nested": ""}}):
            with self.subTest(payload=payload):
                approval, error = normalize_approval({"execution_generation": 1,
                    "pending_controls": [{"kind": "approval", "prompt_id": "p", "payload": payload}]},
                    "waiting_for_approval")
                self.assertEqual("", approval["approval_id"])
                self.assertTrue(error)
        approval, error = normalize_approval({"execution_generation": 1,
            "pending_controls": [{"kind": "approval", "prompt_id": "p",
                                  "payload": {"tool_name": "shell", "tool_args": {"path": "/tmp/file"}}}]},
            "waiting_for_approval")
        self.assertIsNone(error)
        self.assertEqual("pc1:1:p", approval["approval_id"])
        self.assertIn("/tmp/file", approval["description"])
        approval, error = normalize_approval({"execution_generation": 1,
            "pending_controls": [{"kind": "approval", "prompt_id": "p",
                                  "payload": {"command": {"count": 0, "enabled": False}}}]},
            "waiting_for_approval")
        self.assertIsNone(error)
        self.assertIn("false", approval["description"])

    def test_approve_run_posts_only_canonical_identity_fields(self):
        client = HermesClient("http://127.0.0.1:8642", None)
        with patch.object(client, "_request", return_value={"run_id": "run/id"}) as request:
            client.approve_run("run/id", encode_approval_token("prompt:42", 42), "once")
        request.assert_called_once_with(
            "POST", "/v1/runs/run%2Fid/approval",
            {"request_id": "prompt:42", "execution_generation": 42, "choice": "once"},
        )

    def test_control_receipts_require_matching_run_id_and_remain_uncertain(self):
        client = HermesClient("http://127.0.0.1:8642", None)
        for operation in (
            lambda: client.stop_run("run-1"),
            lambda: client.approve_run("run-1", encode_approval_token("prompt", 1), "deny"),
        ):
            for response in ({"run_id": "run-1"}, {"run_id": "other"}, {}):
                with patch.object(client, "_request", return_value=response) as request:
                    if response == {"run_id": "run-1"}:
                        operation()
                    else:
                        with self.assertRaises(HermesError) as raised:
                            operation()
                        self.assertEqual("upstream_invalid", raised.exception.code)
                        self.assertTrue(raised.exception.uncertain)
                    request.assert_called_once()

    def test_unknown_status_remains_running_with_no_resend_warning(self):
        result = normalize_run({"status": "unknown"})
        self.assertEqual("running", result["status"])
        self.assertIn("不要重新发送", result["error"])


if __name__ == "__main__":
    unittest.main()
