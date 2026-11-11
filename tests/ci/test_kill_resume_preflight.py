import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE_SCRIPT = ROOT / "scripts" / "device-kill-resume.sh"


class ProbePreflightTest(unittest.TestCase):
    def test_finished_instrumentation_without_a_death_event_is_rejected_safely(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copyfile(SOURCE_SCRIPT, scripts / SOURCE_SCRIPT.name)
            shutil.copyfile(ROOT / "scripts/heavy-lock.sh", scripts / "heavy-lock.sh")
            clip = root / "clip.wav"
            clip.touch()
            make_clip = scripts / "make-long-clip.sh"
            make_clip.write_text('#!/bin/sh\nprintf "%s\\n" "$PROBE_CLIP"\n')
            make_clip.chmod(0o755)
            adb = root / "adb"
            adb.write_text(
                """#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shlex
import signal
import sys

args = sys.argv[1:]
with Path(os.environ["PROBE_TRACE"]).open("a") as trace:
    trace.write(json.dumps(args) + "\\n")
if args[0] == "logcat":
    signal.pause()
elif args[0] == "shell":
    command = shlex.split(args[1]) if len(args) == 2 else args[1:]
    if command[:3] == ["dumpsys", "activity", "processes"] or command[:1] == ["ps"]:
        # The snapshot saw a running test. It then finishes successfully without a process-death event.
        Path(os.environ["PROBE_FINISHED"]).touch()
        print("Active instrumentation:" if command[0] == "dumpsys" else "am instrument -w test/Runner")
    elif command[:1] == ["date"]:
        print("11-11 01:00:00.000")
    elif command[-2:] == ["id", "-u"]:
        print("10207")
    else:
        sys.exit("Unexpected adb mutation: " + repr(command))
else:
    sys.exit("Unexpected adb command: " + repr(args))
"""
            )
            adb.chmod(0o755)
            trace = root / "trace.jsonl"
            finished = root / "finished"
            environment = dict(os.environ)
            environment.update(
                PATH=str(root) + os.pathsep + os.environ["PATH"],
                CASSINI_HEAVY_LOCK_HELD="1",
                PROBE_TRACE=str(trace),
                PROBE_FINISHED=str(finished),
                PROBE_CLIP=str(clip),
            )
            process = subprocess.Popen(
                ["bash", str(scripts / SOURCE_SCRIPT.name)],
                env=environment,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                start_new_session=True,
            )
            try:
                stdout, stderr = process.communicate(timeout=5)
            finally:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate()
            self.assertEqual(1, process.returncode, stdout + stderr)
            self.assertIn("Instrumentation is active. Run this probe after it finishes.", stderr)
            self.assertTrue(finished.exists())
            commands = [json.loads(line) for line in trace.read_text().splitlines()]
            self.assertFalse(any(command[0] == "logcat" for command in commands))
            self.assertFalse(any("force-stop" in " ".join(command) for command in commands))


if __name__ == "__main__":
    unittest.main()
