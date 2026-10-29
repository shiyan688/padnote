from __future__ import annotations

import unittest
from types import SimpleNamespace

from padnote_assistant.wsl_hermes import WslHermesError, WslHermesManager


class WslDistroIdentityReviewTests(unittest.TestCase):
    def test_same_name_re_registered_under_new_guid_is_rejected_before_guest_call(self):
        old_guid = "{806d943b-0882-43b5-a010-1f092cfa8b22}"
        new_guid = "{906d943b-0882-43b5-a010-1f092cfa8b22}"
        current_registry = {"Ubuntu-24.04": old_guid}
        calls: list[list[str]] = []

        def runner(args, **kwargs):
            calls.append(list(args))
            if args[-2:] == ["--list", "--quiet"]:
                stdout = b"Ubuntu-24.04\r\n"
            elif args[-3:] == ["--list", "--running", "--quiet"]:
                stdout = b"Ubuntu-24.04\r\n"
            else:
                # Any guest operation means the stale selection reached the new
                # same-named distro; no valid helper payload is needed for this test.
                stdout = b"{}"
            return SimpleNamespace(returncode=0, stdout=stdout, stderr=b"")

        manager = WslHermesManager(
            runner=runner,
            registry_reader=lambda: dict(current_registry),
            platform="nt",
        )
        selected = manager.list_distros()[0]
        self.assertEqual(old_guid.lower(), selected["id"])

        # Model removal/re-registration between selection and the next explicit
        # operation. The display name stays the same while the stable GUID changes.
        current_registry["Ubuntu-24.04"] = new_guid
        with self.assertRaises(WslHermesError) as raised:
            manager.inspect(old_guid, "default")

        self.assertEqual("stale_distro", raised.exception.code)
        self.assertFalse(
            any("--distribution" in args for args in calls),
            "stale distro identity must be rejected before any guest command",
        )


if __name__ == "__main__":
    unittest.main()
