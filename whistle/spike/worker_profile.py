"""Sample PSS of only this owned AVD's Whistle child, triggered by Android process events."""

from __future__ import annotations

import json
import re
import subprocess
import threading
import time
import uuid
from pathlib import Path


class WorkerProfile:
    """A measurement cadence is not a test wait: readiness and completion use explicit events."""

    def __init__(self, adb: Path, serial: str, output: Path, enabled: bool):
        self.adb = [str(adb), "-s", serial]
        self.output = output
        self.enabled = enabled
        self.stop = threading.Event()
        self.ready = threading.Event()
        self.started = threading.Event()
        self.pid = None
        self.samples = []
        self.processes = {}
        self.errors = []
        self.token = uuid.uuid4().hex
        self.log = None
        self.reader = None
        self.sampler = None

    def __enter__(self):
        if not self.enabled:
            return self
        for name in ["pss.json", "pss-meminfo.txt", "pss-process-events.txt"]:
            (self.output / name).unlink(missing_ok=True)
        self.log = subprocess.Popen(
            self.adb
            + [
                "logcat",
                "-b",
                "events",
                "-b",
                "main",
                "-v",
                "brief",
                "-T",
                "1",
                "am_proc_start:I",
                "am_proc_died:I",
                "CassiniWorkerPss:I",
                "*:S",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.reader = threading.Thread(target=self.read_events, daemon=True)
        self.sampler = threading.Thread(target=self.sample, daemon=True)
        self.reader.start()
        self.sampler.start()
        try:
            subprocess.run(self.adb + ["shell", "log", "-t", "CassiniWorkerPss", self.token], check=True, timeout=10)
            if not self.ready.wait(10):
                raise RuntimeError("PSS sampler did not observe its logcat readiness marker")
        except BaseException:
            self.__exit__(None, None, None)
            raise
        return self

    def read_events(self):
        name = rb"io\.codemyriad\.cassini:whistle(?::[^,\]\s]+)?"
        start = re.compile(rb"am_proc_start.*\[\d+,(\d+),(\d+),(" + name + rb"),")
        died = re.compile(rb"am_proc_died.*\[\d+,(\d+)," + name + rb",")
        try:
            with (self.output / "pss-process-events.txt").open("wb") as events:
                for line in self.log.stdout:
                    events.write(line)
                    if self.token.encode() in line:
                        self.ready.set()
                    found = start.search(line)
                    if found:
                        pid = int(found[1])
                        self.processes[pid] = dict(uid=int(found[2]), name=found[3].decode())
                        self.pid = pid
                        self.started.set()
                    found = died.search(line)
                    if found and self.pid == int(found[1]):
                        self.pid = None
                    if self.stop.is_set():
                        break
        except Exception as error:
            self.errors.append(f"Process-event reader: {error}")

    def sample(self):
        self.started.wait()  # Actual am_proc_start or explicit shutdown; never a settling delay.
        while not self.stop.is_set():
            pid = self.pid
            if pid is not None:
                try:
                    result = subprocess.run(
                        self.adb + ["shell", "dumpsys", "meminfo", "-d", str(pid)],
                        capture_output=True,
                        timeout=3,
                        check=True,
                    )
                    text = result.stdout.decode()
                    header = f"MEMINFO in pid {pid} [{self.processes[pid]['name']}]"
                    total = re.search(r"TOTAL PSS:\s*(\d+)", text)
                    if header in text and total:
                        row = dict(
                            monotonicNs=time.monotonic_ns(),
                            pid=pid,
                            uid=self.processes[pid]["uid"],
                            pssKiB=int(total[1]),
                        )
                        self.samples.append(row)
                        with (self.output / "pss-meminfo.txt").open("a") as raw:
                            raw.write(json.dumps(row) + "\n" + text + "\n")
                except (subprocess.SubprocessError, UnicodeError) as error:
                    self.errors.append(f"PSS sample for owned child {pid}: {error}")
            # This timeout defines the measurement cadence; no test readiness/pass condition depends on it.
            self.stop.wait(0.05)

    def __exit__(self, *_):
        if not self.enabled:
            return False
        self.stop.set()
        self.started.set()
        if self.log is not None:
            self.log.terminate()
            try:
                self.log.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.log.kill()
                self.log.wait(timeout=5)
        for thread in [self.reader, self.sampler]:
            if thread is not None:
                thread.join(timeout=5)
                if thread.is_alive():
                    self.errors.append("Sampler thread did not finish before deadline")
        times = [s["monotonicNs"] for s in self.samples]
        gaps = [(times[i] - times[i - 1]) / 1e6 for i in range(1, len(times))]
        report = dict(
            schemaVersion=1,
            processPrefix="io.codemyriad.cassini:whistle",
            observedProcesses=self.processes,
            samples=self.samples,
            sampleCount=len(self.samples),
            requestedCadenceMs=50,
            sampledPeakPssKiB=max((s["pssKiB"] for s in self.samples), default=None),
            maximumSampleGapMs=max(gaps, default=None),
            errors=self.errors,
            scope="Debug owned arm64 AVD; sampled child PSS, not an exact kernel high-water mark. "
            "dumpsys collection adds overhead; no recording/model data is read.",
        )
        (self.output / "pss.json").write_text(json.dumps(report, indent=2) + "\n")
        return False
