"""Dialog transitions require their own window notification before a predicate is rechecked."""

import os
import signal
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/ci-emulator-smoke.sh"


class WindowWaitTest(unittest.TestCase):
    def test_dialog_appearance_publishes_a_window_event(self):
        self.check_transition("visible", notify=True)

    def test_dialog_removal_publishes_a_window_event(self):
        self.check_transition("hidden", notify=True)

    def test_suppressed_appearance_notification_fails_despite_the_dialog_changing(self):
        self.check_transition("visible", notify=False)

    def test_suppressed_removal_notification_fails_despite_the_dialog_changing(self):
        self.check_transition("hidden", notify=False)

    def test_process_exit_with_no_dialog_wakes_preflight(self):
        self.check_transition("hidden", notify=True, event="[0,123,fixture]")

    def check_transition(self, target, *, notify, event="WINDOW"):
        source = SCRIPT.read_text()
        function = source[source.index("wait_for() ("):source.index("error_dialog() {")]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "wait.sh"
            helper.write_text(function)
            trigger = root / "transition"
            os.mkfifo(trigger)
            (root / "state").write_text("initial")
            (root / "tests.apk").touch()
            adb = root / "adb"
            adb.write_text(
                """#!/usr/bin/env python3
import os
from pathlib import Path
import sys
import signal

root = Path(os.environ["WINDOW_TEST_ROOT"])
args = sys.argv[1:]
if args[0] == "push":
    pass
elif args[0] == "shell" and "app_process" in args[1]:
    print("START 12345", flush=True)
    (root / "ready").touch()
    print("READY", flush=True)
    with (root / "transition").open() as trigger:
        trigger.readline()
    (root / "state").write_text(os.environ["WINDOW_TARGET"])
    (root / "changed").touch()
    if os.environ["WINDOW_NOTIFY"] == "yes":
        print(os.environ["WINDOW_EVENT"], flush=True)
    else:
        # A lifecycle notification cannot stand in for the missing dialog-window event.
        print("[0,123,fixture-lifecycle]", flush=True)
        print("READ_TIMEOUT", flush=True)
    sys.stdin.read(1)
    print("STOPPED", flush=True)
elif args[0] == "logcat":
    signal.pause()
elif args[0] == "shell" and args[1].startswith("date "):
    print("11-11 01:00:00.000")
elif args[0] == "shell" and args[1] in ("rm", "kill"):
    pass
else:
    sys.exit("Unexpected adb call: " + repr(args))
"""
            )
            adb.chmod(0o755)
            command = r"""
set -euo pipefail
source "$WINDOW_TEST_ROOT/wait.sh"
# The fake producer marks expiry after changing state. Readiness still has an outer hang guard.
read() {
    builtin read "$@" || return
    [ "$line" != READ_TIMEOUT ]
}
predicate() {
    printf 'checked\n' >> "$WINDOW_TEST_ROOT/checks"
    if [ -e "$WINDOW_TEST_ROOT/ready" ] && [ ! -e "$WINDOW_TEST_ROOT/triggered" ]; then
        : > "$WINDOW_TEST_ROOT/triggered"
        printf 'change\n' > "$WINDOW_TEST_ROOT/transition"
        return 1
    fi
    [ "$(cat "$WINDOW_TEST_ROOT/state")" = "$WINDOW_TARGET" ]
}
wait_for 30 'Dialog did not reach the expected state' "$WINDOW_NOTIFICATIONS" predicate
"""
            environment = dict(os.environ)
            environment.update(
                PATH=str(root) + os.pathsep + os.environ["PATH"],
                CASSINI_TEST_APK=str(root / "tests.apk"),
                WINDOW_TEST_ROOT=str(root),
                WINDOW_TARGET=target,
                WINDOW_NOTIFY="yes" if notify else "no",
                WINDOW_EVENT=event,
                WINDOW_NOTIFICATIONS="window" if event == "WINDOW" else "state",
            )
            process = subprocess.Popen(
                ["bash", "-c", command],
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                start_new_session=True,
            )
            try:
                stdout, stderr = process.communicate(timeout=30)
            finally:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate()
            self.assertTrue((root / "changed").exists(), stdout + stderr)
            self.assertEqual(target, (root / "state").read_text())
            self.assertEqual(0 if notify else 1, process.returncode, stdout + stderr)
            self.assertEqual(2 if notify else 1, len((root / "checks").read_text().splitlines()))
            if not notify:
                self.assertIn("no matching window change", stderr)


if __name__ == "__main__":
    unittest.main()
