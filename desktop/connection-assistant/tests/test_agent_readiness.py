from __future__ import annotations

import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

from padnote_assistant.bridge import BridgeService
from padnote_assistant.hermes import CapabilityResult
from padnote_assistant.state import ConflictError
from test_core import ready_instance, run_payload


class AgentReadinessTests(unittest.TestCase):
    def test_known_upstream_run_remains_pollable_after_health_failure(self):
        calls = {"create": 0, "get": []}

        class PollingHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                calls["create"] += 1
                return {"run_id": "upstream-existing", "status": "running"}

            def get_run(self, run_id):
                calls["get"].append(run_id)
                return {"run_id": run_id, "status": "running"}

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", PollingHermes):
            service = BridgeService(Path(directory))
            try:
                instance, token, _ = ready_instance(service)
                status, receipt = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                service.store.update_instance_check(
                    instance["instance_id"], health="not_running", detail="offline",
                    features={}, executable=False)

                result = service.get_run(instance["instance_id"], token, receipt["task_id"])

                self.assertEqual("upstream-existing", calls["get"][0])
                self.assertEqual("running", result["status"])
                self.assertEqual(1, calls["create"])
            finally:
                service.close()

    def test_concurrent_probes_deduplicate_and_passive_poll_stays_fast(self):
        entered = threading.Event()
        release = threading.Event()
        calls = []
        probe_errors = []

        class BlockingHermes:
            def __init__(self, *args, **kwargs):
                pass

            def check_capabilities(self):
                calls.append("check")
                entered.set()
                if not release.wait(timeout=2):
                    raise AssertionError("test did not release fake health probe")
                return CapabilityResult("ready", "fixture ready", {"run_submission": True}, True)

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockingHermes):
            service = BridgeService(Path(directory))
            try:
                instance, token, _ = ready_instance(service)
                instance_id = instance["instance_id"]

                def first_probe():
                    try:
                        service.capabilities(instance_id, token, probe_agent=True)
                    except Exception as error:
                        probe_errors.append(error)

                worker = threading.Thread(target=first_probe)
                worker.start()
                self.assertTrue(entered.wait(timeout=2))

                pending = service.capabilities(instance_id, token, probe_agent=True)
                self.assertEqual("checking", pending["readiness"]["agent_status"])
                self.assertEqual("pending", pending["readiness"]["source"])
                self.assertFalse(pending["readiness"]["fresh"])

                started = time.monotonic()
                passive = service.capabilities(instance_id, token)
                self.assertLess(time.monotonic() - started, 0.25)
                self.assertEqual("cached", passive["readiness"]["source"])
                self.assertEqual(["check"], calls)
            finally:
                release.set()
                if "worker" in locals():
                    worker.join(timeout=2)
                service.close()
            self.assertFalse(worker.is_alive())
            self.assertEqual([], probe_errors)

    def test_probe_cannot_publish_ready_after_config_identity_changes(self):
        entered = threading.Event()
        release = threading.Event()

        class BlockingReadyHermes:
            def __init__(self, *args, **kwargs):
                pass

            def check_capabilities(self):
                entered.set()
                if not release.wait(timeout=2):
                    raise AssertionError("test did not release fake health probe")
                return CapabilityResult("ready", "fixture ready", {"run_submission": True}, True)

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockingReadyHermes):
            service = BridgeService(Path(directory))
            try:
                instance, token, _ = ready_instance(service)
                instance_id = instance["instance_id"]
                prior = service.store.get_instance(instance_id)
                errors = []

                def probe():
                    try:
                        service.capabilities(instance_id, token, probe_agent=True)
                    except Exception as error:
                        errors.append(error)

                worker = threading.Thread(target=probe)
                worker.start()
                self.assertTrue(entered.wait(timeout=2))
                service.store.attach_instance_config(instance_id, "/tmp/new-config.toml", "new-fingerprint")
                release.set()
                worker.join(timeout=2)

                self.assertFalse(worker.is_alive())
                self.assertEqual(1, len(errors))
                self.assertIsInstance(errors[0], ConflictError)
                changed = service.store.get_instance(instance_id)
                self.assertEqual("new-fingerprint", changed["config_fingerprint"])
                self.assertEqual(prior.get("checked_at"), changed.get("checked_at"))
            finally:
                release.set()
                service.close()

    def test_missing_zero_future_and_stale_checks_are_not_fresh_ready(self):
        now = 1000
        instance = {"health": "ready", "executable": True, "retired_at": None}
        cases = (("missing", None), ("zero", 0), ("future", now + 1), ("stale", now - 61))
        with tempfile.TemporaryDirectory() as directory:
            service = BridgeService(Path(directory), clock=lambda: now)
            try:
                for label, checked_at in cases:
                    with self.subTest(label=label):
                        snapshot = dict(instance)
                        if checked_at is not None:
                            snapshot["checked_at"] = checked_at
                        readiness = service._readiness_snapshot(snapshot, key_present=True)
                        self.assertFalse(readiness["fresh"])
                        self.assertNotEqual("ready", readiness["agent_status"])
                        self.assertEqual("unknown", readiness["agent_status"])
            finally:
                service.close()

    def test_explicit_probe_reports_unavailable_agent_not_bridge_failure(self):
        constructor_kwargs = []
        calls = {"create": 0}

        class UnavailableHermes:
            def __init__(self, *args, **kwargs):
                constructor_kwargs.append(kwargs)

            def check_capabilities(self):
                return CapabilityResult("not_running", "fixture unavailable", {}, False)

            def create_run(self, *args, **kwargs):
                calls["create"] += 1
                raise AssertionError("health probe must not submit a run")

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", UnavailableHermes):
            service = BridgeService(Path(directory))
            try:
                instance, token, _ = ready_instance(service)
                value = service.capabilities(instance["instance_id"], token, probe_agent=True)

                self.assertEqual(3.0, constructor_kwargs[-1]["timeout"])
                self.assertTrue(value["readiness"]["bridge_reachable"])
                self.assertEqual("unavailable", value["readiness"]["agent_status"])
                self.assertEqual("live", value["readiness"]["source"])
                self.assertTrue(value["readiness"]["fresh"])
                self.assertFalse(value["features"]["run_submission"])
                self.assertTrue(value["features"]["run_status"])
                self.assertTrue(value["features"]["artifacts"])
                self.assertEqual("device-a", service.store.authenticate(
                    instance["instance_id"], token)["device_id"])
                self.assertEqual({}, service.store.snapshot()["runs"])
                self.assertEqual(0, calls["create"])
            finally:
                service.close()

    def test_passive_capabilities_does_not_wait_behind_blocked_submission(self):
        entered = threading.Event()
        release = threading.Event()
        calls = {"check": 0}

        class BlockingHermes:
            def __init__(self, *args, **kwargs):
                pass

            def create_run(self, *args, **kwargs):
                entered.set()
                if not release.wait(timeout=2):
                    raise AssertionError("test did not release fake Hermes")
                return {"run_id": "run_readiness", "status": "started"}

            def check_capabilities(self):
                calls["check"] += 1
                raise AssertionError("passive capabilities must not probe Hermes")

        with tempfile.TemporaryDirectory() as directory, mock.patch(
                "padnote_assistant.bridge.HermesClient", BlockingHermes):
            service = BridgeService(Path(directory), async_submission=True)
            try:
                instance, token, _ = ready_instance(service)
                status, _ = service.submit_run(
                    instance["instance_id"], token, "client-task-1", run_payload())
                self.assertEqual(202, status)
                self.assertTrue(entered.wait(timeout=2))

                started = time.monotonic()
                value = service.capabilities(instance["instance_id"], token)
                elapsed = time.monotonic() - started

                self.assertLess(elapsed, 0.25)
                self.assertEqual("cached", value["readiness"]["source"])
                self.assertTrue(value["features"]["run_status"])
                self.assertTrue(value["features"]["artifacts"])
                self.assertEqual(0, calls["check"])
            finally:
                release.set()
                service.close()


if __name__ == "__main__":
    unittest.main()
