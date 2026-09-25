from __future__ import annotations

import sys
import threading
import time
import unittest
from pathlib import Path

from padnote_assistant.codex_transport import (
    CodexChildExited,
    CodexProtocolError,
    CodexRequestCancelled,
    CodexRequestTimeout,
    CodexStdioTransport,
    CodexTooManyPending,
    CodexTransportClosed,
)


ROOT = Path(__file__).resolve().parents[1]
FAKE = ROOT / "tests" / "fixtures" / "fake_codex_app_server.py"
CLIENT = {"name": "padnote_fixture", "title": "PadNote Fixture", "version": "0"}


def transport(mode: str, **limits: int) -> CodexStdioTransport:
    return CodexStdioTransport([sys.executable, str(FAKE), mode], **limits)


class CodexTransportTests(unittest.TestCase):
    def test_command_and_timeouts_are_validated_before_any_send(self) -> None:
        for command in ("python fixture.py", b"python"):
            with self.subTest(command=command):
                with self.assertRaises(ValueError):
                    CodexStdioTransport(command)  # type: ignore[arg-type]

        invalid_timeouts = (-1.0, float("nan"), float("inf"), float("-inf"),
                            True, 10 ** 10000)
        for invalid in invalid_timeouts:
            with self.subTest(initialize_timeout=invalid):
                with transport("hang") as client:
                    with self.assertRaises(ValueError):
                        client.initialize(CLIENT, timeout=invalid)
                    self.assertEqual("fixture", client.initialize(CLIENT)["platformFamily"])

        with transport("count-requests") as client:
            client.initialize(CLIENT)
            for invalid in invalid_timeouts:
                with self.subTest(request_timeout=invalid):
                    with self.assertRaises(ValueError):
                        client.request("fixture/must-not-send", {}, timeout=invalid)
            report = client.request("fixture/report", {}, timeout=1)
            self.assertEqual(["fixture/report"], report["methods"])

    def test_initialize_notification_and_out_of_order_response_correlation(self) -> None:
        with transport("normal") as client:
            self.assertEqual("fixture", client.initialize(CLIENT)["platformFamily"])
            first = client.send_request("thread/start", {"which": 1})
            second = client.send_request("turn/start", {"which": 2})
            self.assertEqual({"which": 2}, second.result(1)["echo"])
            self.assertEqual({"which": 1}, first.result(1)["echo"])
            event = client.next_notification(timeout=0)
            self.assertEqual("turn/started", event.method)
            self.assertEqual(0, client.pending_count)

    def test_requests_are_rejected_before_handshake_and_initialize_is_once(self) -> None:
        with transport("hang") as client:
            with self.assertRaises(CodexProtocolError):
                client.send_request("thread/start", {})
            client.initialize(CLIENT)
            with self.assertRaises(CodexProtocolError):
                client.initialize(CLIENT)

    def test_reverse_approval_is_held_until_explicit_caller_reply(self) -> None:
        with transport("reverse") as client:
            client.initialize(CLIENT)
            trigger = client.send_request("fixture/trigger", {})
            approval = client.next_server_request(timeout=1)
            self.assertEqual("approval-1", approval.request_id)
            self.assertEqual("item/commandExecution/requestApproval", approval.method)
            self.assertEqual("fixture/approvalHeld",
                             client.next_notification(timeout=1).method)
            client.reply_server_request(approval.request_id,
                                        result={"decision": "decline"})
            reply = trigger.result(1)["approvalReply"]
            self.assertEqual("approval-1", reply["id"])
            self.assertEqual({"decision": "decline"}, reply["result"])
            with self.assertRaises(CodexProtocolError):
                client.reply_server_request(approval.request_id, result={})
            report = client.request("fixture/report", {}, timeout=1)
            self.assertEqual([], report["messagesBeforeReport"])

    def test_pending_bound_local_cancel_and_shutdown_wake_all_waiters(self) -> None:
        client = transport("hang", max_pending_requests=2)
        client.initialize(CLIENT)
        first = client.send_request("fixture/hang", {"n": 1})
        second = client.send_request("fixture/hang", {"n": 2})
        with self.assertRaises(CodexTooManyPending):
            client.send_request("fixture/hang", {"n": 3})
        self.assertTrue(first.cancel())
        with self.assertRaises(CodexRequestCancelled):
            first.result(0)
        third = client.send_request("fixture/hang", {"n": 3})
        started = time.monotonic()
        returncode = client.shutdown(timeout=0.6)
        self.assertLess(time.monotonic() - started, 1.2)
        self.assertEqual(returncode, client.shutdown(timeout=0.1))
        with self.assertRaises(CodexTransportClosed):
            second.result(0)
        with self.assertRaises(CodexTransportClosed):
            third.result(0)

    def test_late_response_for_cancelled_request_is_ignored_without_miscorrelation(self) -> None:
        with transport("late-response") as client:
            client.initialize(CLIENT)
            cancelled = client.send_request("fixture/cancel", {})
            self.assertEqual("fixture/requestReceived",
                             client.next_notification(timeout=1).method)
            self.assertTrue(cancelled.cancel())
            with self.assertRaises(CodexRequestCancelled):
                cancelled.result(0)
            time.sleep(0.3)
            current = client.send_request("fixture/current", {})
            self.assertEqual({"current": True}, current.result(1))

    def test_incoming_and_outgoing_frames_are_bounded(self) -> None:
        with transport("oversize", max_frame_bytes=256) as client:
            client.initialize(CLIENT)
            pending = client.send_request("fixture/oversize", {})
            with self.assertRaises(CodexProtocolError):
                pending.result(1)

        with transport("hang", max_frame_bytes=256) as client:
            client.initialize(CLIENT)
            with self.assertRaises(CodexProtocolError):
                client.send_request("fixture/large", {"text": "x" * 400})
            self.assertEqual(0, client.pending_count)

    def test_child_exit_wakes_correlated_request(self) -> None:
        client = transport("exit")
        try:
            client.initialize(CLIENT)
            pending = client.send_request("fixture/exit", {})
            with self.assertRaises(CodexChildExited) as caught:
                pending.result(1)
            self.assertEqual(7, caught.exception.returncode)
        finally:
            client.shutdown(timeout=0.5)

    def test_notification_queue_overflow_fails_closed(self) -> None:
        client = transport("notification-overflow", max_notifications=2)
        try:
            client.initialize(CLIENT)
            time.sleep(0.2)
            first = client.next_notification(timeout=1)
            second = client.next_notification(timeout=1)
            self.assertEqual([0, 1], [first.params["index"], second.params["index"]])
            with self.assertRaises(CodexProtocolError):
                client.next_notification(timeout=0.5)
        finally:
            client.shutdown(timeout=0.5)

    def test_incoming_event_byte_budget_fails_closed(self) -> None:
        client = transport("notification-byte-overflow",
                           max_incoming_event_bytes=700)
        try:
            client.initialize(CLIENT)
            time.sleep(0.2)
            first = client.next_notification(timeout=1)
            self.assertEqual(0, first.params["index"])
            with self.assertRaises(CodexProtocolError):
                client.next_notification(timeout=1)
        finally:
            client.shutdown(timeout=0.5)

    def test_malformed_correlated_response_fails_request_and_transport(self) -> None:
        client = transport("malformed-response")
        try:
            client.initialize(CLIENT)
            pending = client.send_request("fixture/malformed", {})
            with self.assertRaises(CodexProtocolError):
                pending.result(1)
            with self.assertRaises(CodexProtocolError):
                client.send_request("thread/start", {})
        finally:
            client.shutdown(timeout=0.5)

    def test_blocked_child_stdin_does_not_defeat_request_or_shutdown_deadlines(self) -> None:
        client = transport("blocked-stdin", max_frame_bytes=3 * 1024 * 1024,
                           max_outgoing_bytes=3 * 1024 * 1024)
        try:
            client.initialize(CLIENT)
            started = time.monotonic()
            with self.assertRaises(CodexRequestTimeout):
                client.request("fixture/blocked", {"text": "x" * (2 * 1024 * 1024)},
                               timeout=0.2)
            self.assertLess(time.monotonic() - started, 0.8)

            client.notify("fixture/queued", {"text": "y" * (1600 * 1024)})
            with self.assertRaises(CodexTooManyPending):
                client.notify("fixture/exceeds-total-byte-budget",
                              {"text": "z" * (1600 * 1024)})

            waiter_errors: list[BaseException] = []

            def wait_without_deadline() -> None:
                try:
                    client.request("fixture/queued-behind-blocked-write", {})
                except BaseException as error:
                    waiter_errors.append(error)

            waiter = threading.Thread(target=wait_without_deadline,
                                      name="fixture-blocked-request")
            waiter.start()
            pending_deadline = time.monotonic() + 0.5
            while client.pending_count != 1 and time.monotonic() < pending_deadline:
                time.sleep(0.005)
            self.assertEqual(1, client.pending_count)

            started = time.monotonic()
            returncode = client.shutdown(timeout=0.8)
            self.assertLess(time.monotonic() - started, 1.2)
            waiter.join(0.2)
            self.assertFalse(waiter.is_alive())
            self.assertEqual(1, len(waiter_errors))
            self.assertIsInstance(waiter_errors[0], CodexTransportClosed)
            self.assertIsNotNone(returncode)
            self.assertIsNotNone(client._process.poll())
            self.assertFalse(client._writer.is_alive())
            self.assertFalse(client._reader.is_alive())
            self.assertFalse(client._stderr_reader.is_alive())
        finally:
            client.shutdown(timeout=0.2)

    def test_more_than_pending_limit_cancelled_queued_frames_are_never_sent(self) -> None:
        with transport("delayed-stdin", max_frame_bytes=3 * 1024 * 1024) as client:
            client.initialize(CLIENT)
            first = client.send_request("fixture/blocking",
                                        {"text": "x" * (2 * 1024 * 1024)})
            cancelled = []
            for index in range(40):
                handle = client.send_request("fixture/cancelled", {"index": index})
                self.assertTrue(handle.cancel())
                cancelled.append(handle)
            barrier = client.send_request("fixture/barrier", {})
            self.assertEqual({"received": "fixture/blocking"}, first.result(3))
            self.assertEqual(0, barrier.result(3)["cancelledSeen"])
            for handle in cancelled:
                with self.assertRaises(CodexRequestCancelled):
                    handle.result(0)

    def test_nonfinite_json_is_rejected_in_both_directions(self) -> None:
        for mode in ("nonfinite-nan", "nonfinite-infinity", "nonfinite-overflow"):
            with self.subTest(mode=mode):
                client = transport(mode)
                try:
                    client.initialize(CLIENT)
                    with self.assertRaises(CodexProtocolError):
                        client.next_notification(timeout=1)
                finally:
                    client.shutdown(timeout=0.5)

        with transport("hang") as client:
            client.initialize(CLIENT)
            for value in (float("nan"), float("inf"), float("-inf")):
                with self.subTest(outgoing=value):
                    with self.assertRaises(CodexProtocolError):
                        client.notify("fixture/nonfinite", {"value": value})


if __name__ == "__main__":
    unittest.main()
