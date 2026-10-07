from __future__ import annotations

import unittest
from types import SimpleNamespace

from tests.fixtures.process_test_utils import (
    ERROR_ACCESS_DENIED,
    ERROR_INVALID_PARAMETER,
    WAIT_FAILED,
    WAIT_OBJECT_0,
    WAIT_TIMEOUT,
    _windows_process_is_alive,
)


class ProcessLivenessHelperTests(unittest.TestCase):
    def _api(self, *, handle=1, wait_result=WAIT_TIMEOUT, close_result=1):
        calls = []
        api = SimpleNamespace(
            OpenProcess=lambda access, inherit, pid: calls.append(("open", access, inherit, pid)) or handle,
            WaitForSingleObject=lambda current, timeout: calls.append(("wait", current, timeout)) or wait_result,
            CloseHandle=lambda current: calls.append(("close", current)) or close_result,
        )
        return api, calls

    def test_open_process_only_classifies_gone_or_access_denied(self):
        for code, expected in ((ERROR_INVALID_PARAMETER, False), (ERROR_ACCESS_DENIED, True)):
            with self.subTest(code=code):
                api, calls = self._api(handle=None)
                self.assertIs(expected, _windows_process_is_alive(42, api, lambda: code))
                self.assertEqual(["open"], [call[0] for call in calls])
        api, _ = self._api(handle=None)
        with self.assertRaisesRegex(OSError, "OpenProcess failed"):
            _windows_process_is_alive(42, api, lambda: 6)

    def test_wait_states_are_explicit_and_handle_is_closed(self):
        for wait_result, expected in ((WAIT_OBJECT_0, False), (WAIT_TIMEOUT, True)):
            with self.subTest(wait_result=wait_result):
                api, calls = self._api(wait_result=wait_result)
                self.assertIs(expected, _windows_process_is_alive(42, api, lambda: 0))
                self.assertEqual("close", calls[-1][0])
        api, calls = self._api(wait_result=WAIT_FAILED)
        with self.assertRaisesRegex(OSError, "WaitForSingleObject failed"):
            _windows_process_is_alive(42, api, lambda: 6)
        self.assertEqual("close", calls[-1][0])
        api, calls = self._api(wait_result=0xDEADBEEF)
        with self.assertRaisesRegex(RuntimeError, "unexpected status"):
            _windows_process_is_alive(42, api, lambda: 0)
        self.assertEqual("close", calls[-1][0])

    def test_close_handle_failure_is_not_silently_accepted(self):
        api, _ = self._api(wait_result=WAIT_OBJECT_0, close_result=0)
        with self.assertRaisesRegex(OSError, "CloseHandle failed"):
            _windows_process_is_alive(42, api, lambda: 6)


if __name__ == "__main__":
    unittest.main()
