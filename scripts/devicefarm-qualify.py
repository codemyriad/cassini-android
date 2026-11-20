#!/usr/bin/env python3
"""Build, upload, schedule, and collect D-1126 Android handset qualification runs on AWS Device Farm (D89).

Usage:
  AWS_PROFILE=cassini-devicefarm uv run --with boto3 python3 scripts/devicefarm-qualify.py \\
      --device-arn <ARN> --pool-name d1126-pixel8-api35 --name d1126-pixel8-5min \\
      --test-class \\
      io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test02PairedFiveMinuteEquivalenceAndPressure

When AWS credentials live on a separate control host (such as drmeyers) with tight local disk, pass
`--control-ssh drmeyers`: control-plane API calls run over SSH while APK uploads and log downloads stream
directly between this host and S3 without storing artifacts on the SSH host or logging presigned URLs.

Enforces the D89/D-1126 $160.00/month scheduling denial threshold (within the $200.00/month budget) across
all projects and all paginated ListRuns pages before scheduling any run.
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import fcntl
import json
import math
import os
import re
import shlex
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
REGION = "us-west-2"
PROJECT_ARN = "arn:aws:devicefarm:us-west-2:613912634239:project:6c67e094-43ab-4c2a-af51-f93bd2b1bfac"
RATE_PER_MINUTE = 0.17
MONTHLY_BUDGET_USD = 200.0
MONTHLY_DENY_USD = 160.0
DEFAULT_TEST_CLASS = "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest"
DEFAULT_CORE_METHODS = (
    "qualificationHarnessTelemetryAndPrefixContractSmoke",
    "test01HardwareCaptureLifecycleInterruptionsAndAacControl",
    "test02PairedFiveMinuteEquivalenceAndPressure",
)
LONG_METHOD = "test03ThirtyMinuteContinuousQualification"
DEFAULT_CORE_TIMEOUT_MINUTES = 105
DEFAULT_COMBINED_LONG_TIMEOUT_MINUTES = 150
MAX_DEVICE_FARM_TIMEOUT_MINUTES = 150
DEFAULT_SCHEDULE_LOCK_PATH = Path.home() / ".cache/cassini-devicefarm/schedule.lock"
APP_PACKAGE = "io.codemyriad.cassini.talk.debug"

SAFE_CLASS_RE = re.compile(r"^[A-Za-z0-9_.$#,-]+$")
SAFE_KEY_RE = re.compile(r"^[A-Za-z0-9_.-]+$")
SAFE_VAL_RE = re.compile(r"^[A-Za-z0-9_.,:/@#=-]+$")
SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")

SSH_CONTROL_SCRIPT = """
import boto3, datetime as dt, json, sys

req = json.loads(sys.stdin.read())
df = boto3.client("devicefarm", region_name=req["region"])
fn = getattr(df, req["op"])
resp = fn(**req["kwargs"])

def default_json(obj):
    if isinstance(obj, (dt.datetime, dt.date)):
        return {"__datetime__": obj.isoformat()}
    raise TypeError(f"Unsupported type: {type(obj)!r}")

sys.stdout.write(json.dumps(resp, default=default_json))
"""


def _decode_datetimes(value: Any) -> Any:
    if isinstance(value, dict):
        if set(value.keys()) == {"__datetime__"} and isinstance(value["__datetime__"], str):
            return dt.datetime.fromisoformat(value["__datetime__"])
        return {k: _decode_datetimes(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_decode_datetimes(item) for item in value]
    return value


class SshDeviceFarmClient:
    """Executes lightweight AWS Device Farm control-plane operations over SSH without transferring files."""

    def __init__(self, ssh_target: str, aws_profile: str, region: str = REGION) -> None:
        self.ssh_target = ssh_target
        self.aws_profile = aws_profile
        self.region = region

    def _call(self, op: str, **kwargs: Any) -> dict[str, Any]:
        payload = json.dumps({"region": self.region, "op": op, "kwargs": kwargs})
        encoded = base64.b64encode(SSH_CONTROL_SCRIPT.encode("utf-8")).decode("ascii")
        remote_cmd = (
            f"env AWS_PROFILE={self.aws_profile} uv run --with boto3 "
            f"python3 -c 'import base64;exec(base64.b64decode(\"{encoded}\"))'"
        )
        proc = subprocess.run(
            ["ssh", self.ssh_target, remote_cmd],
            input=payload,
            text=True,
            capture_output=True,
            check=False,
            timeout=120,
        )
        if proc.returncode != 0:
            raise SystemExit(f"Device Farm control call {op} failed via SSH: {proc.stderr.strip()}")
        return _decode_datetimes(json.loads(proc.stdout))

    def list_projects(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("list_projects", **kwargs)

    def list_runs(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("list_runs", **kwargs)

    def create_upload(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("create_upload", **kwargs)

    def get_upload(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("get_upload", **kwargs)

    def list_device_pools(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("list_device_pools", **kwargs)

    def create_device_pool(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("create_device_pool", **kwargs)

    def schedule_run(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("schedule_run", **kwargs)

    def get_run(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("get_run", **kwargs)

    def list_jobs(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("list_jobs", **kwargs)

    def list_suites(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("list_suites", **kwargs)

    def list_tests(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("list_tests", **kwargs)

    def list_artifacts(self, **kwargs: Any) -> dict[str, Any]:
        return self._call("list_artifacts", **kwargs)


def paginate(fetch, list_key: str, **kwargs: Any) -> list[dict[str, Any]]:
    """Collects all pages of a Device Farm list_* API, guarding against repeated pagination tokens."""
    items: list[dict[str, Any]] = []
    seen_tokens: set[str] = set()
    next_token: str | None = None
    while True:
        call_kwargs = dict(kwargs)
        if next_token is not None:
            call_kwargs["nextToken"] = next_token
        response = fetch(**call_kwargs)
        items.extend(response.get(list_key, []))
        token = response.get("nextToken")
        if not token:
            break
        if token in seen_tokens:
            raise SystemExit(f"Repeated pagination nextToken while reading {list_key}.")
        seen_tokens.add(token)
        next_token = token
    return items


def _to_utc(timestamp: dt.datetime | str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(timestamp) if isinstance(timestamp, str) else timestamp
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def _run_overlaps_month(run: dict[str, Any], year: int, month: int) -> bool:
    for field in ("created", "started", "stopped"):
        raw = run.get(field)
        if raw is None:
            continue
        ts = _to_utc(raw)
        if ts.year == year and ts.month == month:
            return True
    return False


def check_budget(
    df: Any,
    *,
    now: dt.datetime | None = None,
    ceiling_usd: float = MONTHLY_DENY_USD,
    default_project_arn: str = PROJECT_ARN,
    projected_new_run_usd: float = 0.0,
) -> float:
    """Sums metered minutes + projected outstanding/new run exposure across all projects for the current UTC month."""
    if not math.isfinite(ceiling_usd) or ceiling_usd <= 0 or ceiling_usd > MONTHLY_DENY_USD:
        raise SystemExit(
            f"Invalid ceiling {ceiling_usd!r}; must be a finite value in (0, ${MONTHLY_DENY_USD:.2f}].",
        )
    if not math.isfinite(projected_new_run_usd) or projected_new_run_usd < 0:
        raise SystemExit(f"Invalid projected_new_run_usd {projected_new_run_usd!r}; must be finite and >= 0.")
    now_utc = _to_utc(now) if now is not None else dt.datetime.now(dt.timezone.utc)

    project_arns: list[str] = []
    if hasattr(df, "list_projects"):
        projects = paginate(df.list_projects, "projects")
        project_arns = [p["arn"] for p in projects if p.get("arn")]
    if default_project_arn and default_project_arn not in project_arns:
        project_arns.append(default_project_arn)

    metered_minutes = 0.0
    outstanding_projected_minutes = 0.0
    for project_arn in project_arns:
        runs = paginate(df.list_runs, "runs", arn=project_arn)
        for run in runs:
            run_metered = float(run.get("deviceMinutes", {}).get("metered", 0.0) or 0.0)
            overlaps_month = _run_overlaps_month(run, now_utc.year, now_utc.month)
            if overlaps_month:
                metered_minutes += run_metered
            status = str(run.get("status", "COMPLETED") or "COMPLETED").upper()
            if status != "COMPLETED":
                raw_total_jobs = run.get("totalJobs")
                if raw_total_jobs is None or int(raw_total_jobs) <= 0:
                    run_id = run.get("arn") or run.get("name") or "<unknown>"
                    raise SystemExit(
                        f"Budget guard (D89/D-1126): outstanding run {run_id} ({status}) has unknown device "
                        f"count (totalJobs={raw_total_jobs!r}); wait for it to finish or report totalJobs.",
                    )
                total_jobs = int(raw_total_jobs)
                run_timeout = float(run.get("jobTimeoutMinutes") or MAX_DEVICE_FARM_TIMEOUT_MINUTES)
                already_counted_metered = run_metered if overlaps_month else 0.0
                outstanding_projected_minutes += max(0.0, total_jobs * run_timeout - already_counted_metered)

    spent_usd = metered_minutes * RATE_PER_MINUTE
    outstanding_usd = outstanding_projected_minutes * RATE_PER_MINUTE
    total_projected_usd = spent_usd + outstanding_usd + projected_new_run_usd
    print(
        f"Device Farm month-to-date ({now_utc:%Y-%m}): {metered_minutes:.2f} metered min "
        f"(~${spent_usd:.2f} / ${ceiling_usd:.2f} scheduling deny threshold; "
        f"outstanding=~${outstanding_usd:.2f}, new=~${projected_new_run_usd:.2f}, "
        f"projected=~${total_projected_usd:.2f} / ${MONTHLY_BUDGET_USD:.2f} monthly budget)",
        flush=True,
    )
    if spent_usd >= ceiling_usd:
        raise SystemExit(
            f"Budget guard (D89/D-1126): ${spent_usd:.2f} >= ${ceiling_usd:.2f} monthly scheduling deny threshold.",
        )
    if total_projected_usd > MONTHLY_BUDGET_USD:
        raise SystemExit(
            f"Budget guard (D89/D-1126): projected monthly total ${total_projected_usd:.2f} "
            f"(metered=${spent_usd:.2f} + outstanding=${outstanding_usd:.2f} + new=${projected_new_run_usd:.2f}) "
            f"> ${MONTHLY_BUDGET_USD:.2f} monthly budget.",
        )
    return spent_usd


@contextlib.contextmanager
def _schedule_lock(lock_path: Path):
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)


@contextlib.contextmanager
def _remote_schedule_lock(control_ssh: str):
    remote_cmd = (
        "mkdir -p ~/.cache/cassini-devicefarm && "
        "flock -x ~/.cache/cassini-devicefarm/schedule.lock sh -c 'echo LOCKED; read _'"
    )
    proc = subprocess.Popen(
        ["ssh", control_ssh, remote_cmd],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    try:
        assert proc.stdout is not None
        line = proc.stdout.readline().strip()
        if line != "LOCKED":
            stderr = proc.stderr.read().strip() if proc.stderr is not None else ""
            raise SystemExit(
                f"Failed to acquire remote schedule lock on {control_ssh}: {stderr or line or 'EOF'}",
            )
        yield
    finally:
        if proc.stdin is not None:
            with contextlib.suppress(OSError):
                proc.stdin.write("\n")
                proc.stdin.flush()
                proc.stdin.close()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


def schedule_run_guarded(
    df: Any,
    *,
    lock_path: Path,
    ceiling_usd: float,
    projected_new_run_usd: float,
    schedule_kwargs: dict[str, Any],
    control_ssh: str | None = None,
    now: dt.datetime | None = None,
) -> dict[str, Any]:
    """Serializes the final budget check and schedule_run call (locally or on --control-ssh)."""
    lock_ctx = _remote_schedule_lock(control_ssh) if control_ssh else _schedule_lock(lock_path)
    with lock_ctx:
        check_budget(
            df,
            now=now,
            ceiling_usd=ceiling_usd,
            projected_new_run_usd=projected_new_run_usd,
        )
        return df.schedule_run(**schedule_kwargs)["run"]


def validate_runner_args(test_class: str, extra_args: dict[str, str]) -> list[str]:
    """Validates instrumentation runner arguments so custom TestSpec shell commands remain injection-safe."""
    if not SAFE_CLASS_RE.fullmatch(test_class):
        raise SystemExit(f"Unsafe instrumentation class selector: {test_class!r}")
    flags = ["-e", "class", test_class]
    for key, value in sorted(extra_args.items()):
        if not SAFE_KEY_RE.fullmatch(key):
            raise SystemExit(f"Unsafe instrumentation argument key: {key!r}")
        if not SAFE_VAL_RE.fullmatch(value):
            raise SystemExit(f"Unsafe instrumentation argument value for {key}: {value!r}")
        flags.extend(["-e", key, value])
    return flags


def parse_test_selectors(resolved_test_class: str) -> tuple[list[tuple[str, str]], list[str]]:
    """Parses comma-separated selectors into `(explicit_class_methods, whole_classes)`."""
    explicit: list[tuple[str, str]] = []
    whole_classes: list[str] = []
    for raw in resolved_test_class.split(","):
        sel = raw.strip()
        if not sel:
            continue
        if "#" in sel:
            cls, method = sel.split("#", 1)
            explicit.append((cls, method))
        else:
            whole_classes.append(sel)
    return explicit, whole_classes


def _min_expected_test_count(explicit: list[tuple[str, str]], whole_classes: list[str]) -> int:
    explicit_classes = {cls for cls, _ in explicit}
    uncovered_whole = sum(1 for cls in whole_classes if cls not in explicit_classes)
    return max(1, len(explicit) + uncovered_whole)


def verify_expected_methods_in_log(log_text: str, resolved_test_class: str) -> None:
    """Verifies instrumentation log ran cleanly and every requested selector completed with status code 0."""
    bad_markers = (
        "FAILURES!!!",
        "INSTRUMENTATION_FAILED",
        "INSTRUMENTATION_SHELL_FAILED",
        "shortMsg=Process crashed",
    )
    for marker in bad_markers:
        if marker in log_text:
            raise SystemExit(f"Instrumentation log contains failure marker {marker!r}.")

    passed_pairs: set[tuple[str, str]] = set()
    cur_class: str | None = None
    cur_test: str | None = None
    for raw_line in log_text.splitlines():
        line = raw_line.strip()
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            cur_class = line.removeprefix("INSTRUMENTATION_STATUS: class=").strip()
        elif line.startswith("INSTRUMENTATION_STATUS: test="):
            cur_test = line.removeprefix("INSTRUMENTATION_STATUS: test=").strip()
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code = line.removeprefix("INSTRUMENTATION_STATUS_CODE:").strip()
            if code in {"-1", "-2", "-3", "-4"}:
                raise SystemExit(
                    f"Instrumentation reported non-passing status code {code} for {cur_class}#{cur_test}.",
                )
            if code == "0" and cur_class and cur_test:
                passed_pairs.add((cur_class, cur_test))
            cur_class = None
            cur_test = None

    ok_matches = re.findall(r"^OK \((\d+) tests?\)", log_text, flags=re.MULTILINE)
    if not ok_matches:
        raise SystemExit("Instrumentation log missing final 'OK (N tests)' summary.")
    ok_count = int(ok_matches[-1])
    explicit, whole_classes = parse_test_selectors(resolved_test_class)
    min_expected = _min_expected_test_count(explicit, whole_classes)
    if explicit and not whole_classes:
        if ok_count != len(explicit):
            raise SystemExit(
                f"Instrumentation ran OK ({ok_count} tests), expected {len(explicit)} requested methods.",
            )
    elif ok_count < min_expected:
        raise SystemExit(
            f"Instrumentation ran OK ({ok_count} tests), expected >= {min_expected}.",
        )
    missing_methods = [f"{cls}#{method}" for cls, method in explicit if (cls, method) not in passed_pairs]
    if missing_methods:
        raise SystemExit(f"Instrumentation log missing passed test(s): {missing_methods}.")
    passed_classes = {cls for cls, _ in passed_pairs}
    missing_classes = [cls for cls in whole_classes if cls not in passed_classes]
    if missing_classes:
        raise SystemExit(f"Instrumentation log missing passed class(es): {missing_classes}.")


def build_test_spec(test_class: str, extra_args: dict[str, str]) -> str:
    """Builds the Amazon Linux 2 Device Farm Instrumentation TestSpec YAML."""
    device_runner_flags = " ".join(shlex.quote(arg) for arg in validate_runner_args(test_class, extra_args))
    host_quoted_flags = shlex.quote(device_runner_flags)
    banner_text = shlex.quote(f"Running Cassini Android handset qualification ({test_class})")
    explicit, whole_classes = parse_test_selectors(test_class)
    exact_mode = bool(explicit) and not whole_classes
    min_expected = len(explicit) if exact_mode else _min_expected_test_count(explicit, whole_classes)
    ok_cmp = "-eq" if exact_mode else "-ge"
    selector_checks: list[str] = []
    if explicit:
        pair_list = " ".join(shlex.quote(f"{cls}#{method}") for cls, method in explicit)
        selector_checks.append(
            f"""
        for pair in {pair_list}; do
          cls="${{pair%%#*}}"
          m="${{pair#*#}}"
          awk -v tc="$cls" -v tm="$m" '
            /^INSTRUMENTATION_STATUS: class=/ {{ cc = substr($0, 31); gsub(/\\r/, "", cc) }}
            /^INSTRUMENTATION_STATUS: test=/ {{ ct = substr($0, 30); gsub(/\\r/, "", ct) }}
            /^INSTRUMENTATION_STATUS_CODE:/ {{
              if ($0 ~ /^INSTRUMENTATION_STATUS_CODE: 0([[:space:]]|$)/ && cc == tc && ct == tm) found = 1
              cc = ""
              ct = ""
            }}
            END {{ exit(found ? 0 : 1) }}
          ' "$DEVICEFARM_LOG_DIR/instrument.log" || {{ echo "Missing passed test: $pair"; exit 1; }}
        done""",
        )
    if whole_classes:
        class_list = " ".join(shlex.quote(cls) for cls in whole_classes)
        selector_checks.append(
            f"""
        for cls in {class_list}; do
          awk -v tc="$cls" '
            /^INSTRUMENTATION_STATUS: class=/ {{ cc = substr($0, 31); gsub(/\\r/, "", cc) }}
            /^INSTRUMENTATION_STATUS: test=/ {{ ct = substr($0, 30); gsub(/\\r/, "", ct) }}
            /^INSTRUMENTATION_STATUS_CODE:/ {{
              if ($0 ~ /^INSTRUMENTATION_STATUS_CODE: 0([[:space:]]|$)/ && cc == tc && ct != "") found = 1
              cc = ""
              ct = ""
            }}
            END {{ exit(found ? 0 : 1) }}
          ' "$DEVICEFARM_LOG_DIR/instrument.log" || {{ echo "Missing passed class: $cls"; exit 1; }}
        done""",
        )
    method_check_block = "".join(selector_checks)
    return f"""version: 0.1
android_test_host: amazon_linux_2

phases:
  install:
    commands:
      - echo "Device Farm host ready"

  pre_test:
    commands:
      - adb -s "$DEVICEFARM_DEVICE_UDID" wait-for-device
      - adb -s "$DEVICEFARM_DEVICE_UDID" shell input keyevent KEYCODE_WAKEUP || true
      - adb -s "$DEVICEFARM_DEVICE_UDID" shell wm dismiss-keyguard || true
      - adb -s "$DEVICEFARM_DEVICE_UDID" shell settings put global hide_error_dialogs 1 || true
      - adb -s "$DEVICEFARM_DEVICE_UDID" shell pm grant {APP_PACKAGE} android.permission.RECORD_AUDIO || true
      - adb -s "$DEVICEFARM_DEVICE_UDID" shell pm grant {APP_PACKAGE} android.permission.POST_NOTIFICATIONS || true
      - adb -s "$DEVICEFARM_DEVICE_UDID" logcat -c || true
      - >-
        adb -s "$DEVICEFARM_DEVICE_UDID" shell
        "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release;
        getprop ro.build.version.sdk; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo"
        | tee "$DEVICEFARM_LOG_DIR/device-info.txt"

  test:
    commands:
      - echo {banner_text}
      - >-
        adb -s "$DEVICEFARM_DEVICE_UDID" shell
        "am instrument -r -w --no-window-animation "{host_quoted_flags}"
        $DEVICEFARM_TEST_PACKAGE_NAME/$DEVICEFARM_TEST_PACKAGE_RUNNER 2>&1
        || echo INSTRUMENTATION_SHELL_FAILED"
        | tee "$DEVICEFARM_LOG_DIR/instrument.log"
      - |-
        STARTED=$(grep -c "INSTRUMENTATION_STATUS_CODE: 1" "$DEVICEFARM_LOG_DIR/instrument.log" || true)
        ERRORED=$(grep -c "INSTRUMENTATION_STATUS_CODE: -1" "$DEVICEFARM_LOG_DIR/instrument.log" || true)
        FAILED=$(grep -c "INSTRUMENTATION_STATUS_CODE: -2" "$DEVICEFARM_LOG_DIR/instrument.log" || true)
        IGNORED=$(grep -c "INSTRUMENTATION_STATUS_CODE: -3" "$DEVICEFARM_LOG_DIR/instrument.log" || true)
        SKIPPED=$(grep -c "INSTRUMENTATION_STATUS_CODE: -4" "$DEVICEFARM_LOG_DIR/instrument.log" || true)
        echo "Instrumentation: started=$STARTED errored=$ERRORED failed=$FAILED ignored=$IGNORED skipped=$SKIPPED"
        if [ "$STARTED" -lt {min_expected} ] || [ "$ERRORED" -ne 0 ] || [ "$FAILED" -ne 0 ]; then
          exit 1
        fi
        if [ "$IGNORED" -ne 0 ] || [ "$SKIPPED" -ne 0 ]; then
          exit 1
        fi
        BAD_PAT="FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_SHELL_FAILED|shortMsg=Process crashed"
        if grep -Eq "$BAD_PAT" "$DEVICEFARM_LOG_DIR/instrument.log"; then
          exit 1
        fi
        OK_COUNT=$(sed -nE 's/^OK \\(([0-9]+) tests?\\).*/\\1/p' "$DEVICEFARM_LOG_DIR/instrument.log" | tail -n 1)
        if [ -z "$OK_COUNT" ] || ! [ "$OK_COUNT" {ok_cmp} {min_expected} ]; then
          echo "Unexpected OK count: got=$OK_COUNT expected={min_expected}"
          exit 1
        fi{method_check_block}

  post_test:
    commands:
      - adb -s "$DEVICEFARM_DEVICE_UDID" logcat -d > "$DEVICEFARM_LOG_DIR/logcat.txt" 2>&1 || true
      - >-
        adb -s "$DEVICEFARM_DEVICE_UDID" shell
        "run-as {APP_PACKAGE} cat files/handset-qual-reports.json"
        > "$DEVICEFARM_LOG_DIR/handset-qual-reports.json" 2>/dev/null || true

artifacts:
  - $DEVICEFARM_LOG_DIR
"""


def build_apks(root: Path = ROOT) -> tuple[Path, Path]:
    """Builds the arm64-v8a (or universal) debug APK under test and the debug instrumentation APK."""
    cmd = [
        str(root / "scripts/heavy-lock.sh"),
        str(root / "gradlew"),
        "--no-daemon",
        "-Pandroid.builder.sdkDownload=false",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
    ]
    print("+", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=root, check=True)
    return resolve_apks(root)


def resolve_apks(root: Path = ROOT) -> tuple[Path, Path]:
    arm_apk = root / "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
    universal_apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    app_apk = arm_apk if arm_apk.is_file() else universal_apk
    test_apk = root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if not app_apk.is_file() or not test_apk.is_file():
        raise SystemExit(f"Expected built APKs at {app_apk} and {test_apk}.")
    return app_apk, test_apk


def put_presigned_file(put_url: str, path: Path) -> None:
    """Streams a local file directly to a presigned S3 URL without printing or exposing the URL in argv."""
    # Pass URL via curl --config stdin so query tokens never appear in process arguments or logs.
    escaped_url = put_url.replace("\\", "\\\\").replace('"', '\\"')
    escaped_path = str(path).replace("\\", "\\\\").replace('"', '\\"')
    curl_cfg = (
        f'url = "{escaped_url}"\n'
        f'upload-file = "{escaped_path}"\n'
        'request = "PUT"\n'
        'header = "Content-Type: application/octet-stream"\n'
        "fail\n"
        "silent\n"
        "show-error\n"
    )
    proc = subprocess.run(
        ["curl", "--config", "-"],
        input=curl_cfg,
        text=True,
        capture_output=True,
        check=False,
        timeout=900,
    )
    if proc.returncode != 0:
        # Scrub any URL fragments from curl stderr before reporting.
        sanitized = re.sub(r"https?://\S+", "<redacted-url>", proc.stderr.strip())
        raise SystemExit(f"Upload of {path.name} failed (curl exit {proc.returncode}): {sanitized}")


def upload_file(df: Any, path: Path, upload_type: str, project_arn: str = PROJECT_ARN) -> str:
    resp = df.create_upload(projectArn=project_arn, name=path.name, type=upload_type)["upload"]
    arn = resp["arn"]
    put_url = resp["url"]
    mib = path.stat().st_size / (1024 * 1024)
    print(f"Uploading {path.name} ({mib:.1f} MiB) as {upload_type}...", flush=True)
    put_presigned_file(put_url, path)
    deadline = time.monotonic() + 180.0
    while True:
        upload = df.get_upload(arn=arn)["upload"]
        status = upload["status"]
        if status == "SUCCEEDED":
            return arn
        if status == "FAILED":
            raise SystemExit(f"Device Farm rejected upload {path.name}: {upload.get('metadata')}")
        if time.monotonic() >= deadline:
            raise SystemExit(f"Timed out waiting for Device Farm to process upload {path.name} ({status}).")
        time.sleep(2)


def ensure_device_pool(df: Any, name: str, device_arns: list[str], project_arn: str = PROJECT_ARN) -> str:
    expected_rule = [{"attribute": "ARN", "operator": "IN", "value": json.dumps(device_arns)}]
    for pool in paginate(df.list_device_pools, "devicePools", arn=project_arn, type="PRIVATE"):
        if pool.get("name") == name and pool.get("rules") == expected_rule:
            return pool["arn"]
    return df.create_device_pool(
        projectArn=project_arn,
        name=name,
        rules=expected_rule,
    )["devicePool"]["arn"]


def extract_reports_from_text(body: str) -> list[dict[str, Any]]:
    """Extracts structured JSON qualification reports framed by CASSINI_HANDSET_QUAL_BEGIN/END markers."""
    reports: list[dict[str, Any]] = []
    marker = "CASSINI_HANDSET_QUAL_BEGIN:"
    idx = 0
    while True:
        start = body.find(marker, idx)
        if start == -1:
            break
        nl = body.find("\n", start)
        if nl == -1:
            break
        name = body[start + len(marker) : nl].strip()
        end_marker = f"CASSINI_HANDSET_QUAL_END:{name}"
        end = body.find(end_marker, nl)
        if end == -1:
            break
        raw = body[nl + 1 : end]
        # Strip optional `INSTRUMENTATION_STATUS: stream=` or logcat prefixes if interleaved, or parse raw JSON.
        cleaned_lines = []
        for line in raw.splitlines():
            stripped = line.strip()
            if stripped.startswith("INSTRUMENTATION_STATUS") or stripped.startswith("INSTRUMENTATION_CODE"):
                continue
            cleaned_lines.append(line)
        candidate = "\n".join(cleaned_lines).strip()
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                reports.append(parsed)
        except json.JSONDecodeError:
            pass
        idx = end + len(end_marker)
    return reports


def extract_reports(df: Any, run_arn: str, out_dir: Path) -> list[dict[str, Any]]:
    reports: list[dict[str, Any]] = []
    seen_payloads: set[str] = set()
    out_dir.mkdir(parents=True, exist_ok=True)
    for job in paginate(df.list_jobs, "jobs", arn=run_arn):
        job_prefix = re.sub(r"[^A-Za-z0-9_.-]+", "_", job.get("name", "device"))
        for suite in paginate(df.list_suites, "suites", arn=job["arn"]):
            for test in paginate(df.list_tests, "tests", arn=suite["arn"]):
                for art_type in ("LOG", "FILE"):
                    for art in paginate(df.list_artifacts, "artifacts", arn=test["arn"], type=art_type):
                        ext = art.get("extension", "")
                        if ext not in ("log", "txt", "json") and "Output" not in art.get("name", ""):
                            continue
                        with urllib.request.urlopen(art["url"], timeout=180) as response:
                            body = response.read().decode("utf-8", errors="replace")
                        raw_label = (
                            f"{job_prefix}-{suite.get('name', 'suite')}-"
                            f"{test.get('name', 'test')}-{art.get('name', 'art')}.{ext or 'txt'}"
                        )
                        safe_name = re.sub(r"[^A-Za-z0-9_.-]+", "_", raw_label)
                        (out_dir / safe_name).write_text(body, encoding="utf-8")
                        candidates = extract_reports_from_text(body)
                        if "handset-qual-reports" in art.get("name", ""):
                            try:
                                parsed_json = json.loads(body)
                                if isinstance(parsed_json, list):
                                    candidates.extend(x for x in parsed_json if isinstance(x, dict))
                                elif isinstance(parsed_json, dict):
                                    candidates.append(parsed_json)
                            except json.JSONDecodeError:
                                pass
                        for item in candidates:
                            canonical = json.dumps(item, sort_keys=True)
                            if canonical not in seen_payloads:
                                seen_payloads.add(canonical)
                                reports.append(item)
    return reports


def open_client(control_ssh: str | None, aws_profile: str) -> Any:
    if control_ssh:
        return SshDeviceFarmClient(control_ssh, aws_profile=aws_profile, region=REGION)
    import boto3

    session = (
        boto3.Session(profile_name=aws_profile, region_name=REGION)
        if aws_profile
        else boto3.Session(region_name=REGION)
    )
    return session.client("devicefarm", region_name=REGION)


def resolve_test_class(test_class: str | None, long_engine: str) -> str:
    """Resolves the instrumentation class/method selector from selected lanes so unrequested lanes are not skipped."""
    if test_class:
        return test_class
    methods = list(DEFAULT_CORE_METHODS)
    if long_engine != "none":
        methods.append(LONG_METHOD)
    return ",".join(f"{DEFAULT_TEST_CLASS}#{method}" for method in methods)


def resolve_timeout_minutes(timeout_minutes: int | None, resolved_test_class: str, long_engine: str) -> int:
    """Derives AWS Device Farm jobTimeoutMinutes from resolved lanes (capped at Device Farm's 150-minute max)."""
    if timeout_minutes is not None:
        if timeout_minutes <= 0 or timeout_minutes > MAX_DEVICE_FARM_TIMEOUT_MINUTES:
            raise SystemExit(
                f"Invalid --timeout-minutes {timeout_minutes}; must be in [1, {MAX_DEVICE_FARM_TIMEOUT_MINUTES}].",
            )
        return timeout_minutes
    selectors = [part.strip() for part in resolved_test_class.split(",") if part.strip()]
    selects_whole_class = any("#" not in sel for sel in selectors)
    includes_long = any(sel.endswith(f"#{LONG_METHOD}") for sel in selectors)
    includes_core = any(
        any(sel.endswith(f"#{core}") for core in DEFAULT_CORE_METHODS)
        for sel in selectors
    )
    if long_engine != "none" and (selects_whole_class or (includes_long and includes_core)):
        return DEFAULT_COMBINED_LONG_TIMEOUT_MINUTES
    return DEFAULT_CORE_TIMEOUT_MINUTES


def project_run_cost_usd(device_count: int, timeout_minutes: int) -> float:
    return device_count * timeout_minutes * RATE_PER_MINUTE


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--device-arn",
        action="append",
        required=False,
        help="Target Device Farm device ARN (repeatable).",
    )
    parser.add_argument("--pool-name", help="Private device pool name.")
    parser.add_argument("--name", help="Device Farm run name.")
    parser.add_argument(
        "--test-class",
        default=None,
        help="Instrumentation class or class#method selector (defaults to selected lanes).",
    )
    parser.add_argument("--short-seconds", type=int, default=300)
    parser.add_argument("--long-seconds", type=int, default=1800)
    parser.add_argument("--no-parakeet", action="store_true")
    parser.add_argument("--no-whistle", action="store_true")
    parser.add_argument("--long-engine", choices=("parakeet", "whistle", "none"), default="none")
    parser.add_argument("--arg", action="append", default=[], help="Extra instrumentation KEY=VALUE argument.")
    parser.add_argument(
        "--timeout-minutes",
        type=int,
        default=None,
        help="Job timeout in minutes (defaults to 105 for core lanes, 150 when combined with --long-engine).",
    )
    parser.add_argument("--ceiling-usd", type=float, default=MONTHLY_DENY_USD)
    parser.add_argument(
        "--lock-file",
        type=Path,
        default=Path(os.environ.get("CASSINI_DEVICEFARM_LOCK_FILE", str(DEFAULT_SCHEDULE_LOCK_PATH))),
        help="Exclusive lock file serializing final budget check and schedule_run.",
    )
    parser.add_argument("--out-dir", type=Path, help="Output directory for logs and extracted reports.")
    parser.add_argument("--control-ssh", help="Optional SSH host holding AWS credentials (control-plane only).")
    parser.add_argument("--aws-profile", default=os.environ.get("AWS_PROFILE", "cassini-devicefarm"))
    parser.add_argument("--app-arn", help="Reuse an already-uploaded ANDROID_APP upload ARN.")
    parser.add_argument("--test-arn", help="Reuse an already-uploaded INSTRUMENTATION_TEST_PACKAGE upload ARN.")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--no-wait", action="store_true")
    parser.add_argument("--collect-run-arn", help="Collect artifacts and reports from an existing run ARN.")
    parser.add_argument(
        "--check-budget-only",
        action="store_true",
        help="Verify monthly spend against the D89 ceiling and exit.",
    )
    return parser


def create_run_out_dir(name: str, base_out_dir: Path | None = None) -> Path:
    """Validates run name as a safe path component and creates a unique per-invocation directory."""
    if not SAFE_NAME_RE.fullmatch(name) or ".." in name:
        raise SystemExit(f"Unsafe run name (must be a single safe path component): {name!r}")
    parent = base_out_dir or (ROOT / ".tools/devicefarm-out")
    parent.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix=f"{name}-", dir=parent))


def _safe_collect_prefix(name: str | None, run_info: dict[str, Any], run_arn: str) -> str:
    if name:
        return name
    raw = str(run_info.get("name") or run_arn.rsplit("/", 1)[-1].rsplit(":", 1)[-1] or "collected")
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "-", raw).strip(".-")
    while ".." in cleaned:
        cleaned = cleaned.replace("..", "-")
    return cleaned if cleaned and SAFE_NAME_RE.fullmatch(cleaned) else "collected"


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    df = open_client(args.control_ssh, args.aws_profile)

    if args.collect_run_arn:
        run_info = df.get_run(arn=args.collect_run_arn)["run"]
        print(
            f"[{run_info['status']}] result={run_info.get('result')} minutes={run_info.get('deviceMinutes')}",
            flush=True,
        )
        if run_info["status"] == "COMPLETED":
            collect_prefix = _safe_collect_prefix(args.name, run_info, args.collect_run_arn)
            base_dir = args.out_dir or (ROOT / ".tools/devicefarm-out/collected")
            out_dir = create_run_out_dir(collect_prefix, base_dir)
            reports = extract_reports(df, args.collect_run_arn, out_dir / "artifacts")
            (out_dir / "reports.json").write_text(json.dumps(reports, indent=2) + "\n", encoding="utf-8")
            (out_dir / "run.json").write_text(
                json.dumps(
                    {
                        "arn": run_info["arn"],
                        "name": run_info.get("name"),
                        "status": run_info.get("status"),
                        "result": run_info.get("result"),
                        "counters": run_info.get("counters"),
                        "deviceMinutes": run_info.get("deviceMinutes"),
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            print(f"Collected run output -> {out_dir}", flush=True)
            print(f"Extracted {len(reports)} qualification report(s) -> {out_dir / 'reports.json'}", flush=True)
        return

    if args.check_budget_only:
        check_budget(df, ceiling_usd=args.ceiling_usd)
        return

    if not args.device_arn or not args.pool_name or not args.name:
        raise SystemExit("--device-arn, --pool-name, and --name are required to schedule a run.")

    selected_test_class = resolve_test_class(args.test_class, args.long_engine)
    job_timeout_minutes = resolve_timeout_minutes(args.timeout_minutes, selected_test_class, args.long_engine)
    projected_new_run_usd = project_run_cost_usd(len(args.device_arn), job_timeout_minutes)
    check_budget(df, ceiling_usd=args.ceiling_usd, projected_new_run_usd=projected_new_run_usd)

    out_dir = create_run_out_dir(args.name, args.out_dir)

    app_apk, test_apk = resolve_apks(ROOT) if args.skip_build else build_apks(ROOT)

    extra_args: dict[str, str] = {
        "cassiniHandsetQual": "true",
        "downloadModels": "true",
        "shortSeconds": str(args.short_seconds),
        "longSeconds": str(args.long_seconds),
        "runParakeet": "false" if args.no_parakeet else "true",
        "runWhistle": "false" if args.no_whistle else "true",
        "longEngine": args.long_engine,
    }
    for item in args.arg:
        if "=" not in item:
            raise SystemExit(f"--arg expects KEY=VALUE, got {item!r}")
        key, val = item.split("=", 1)
        extra_args[key] = val

    spec_path = out_dir / f"{args.name}-testspec.yml"
    spec_path.write_text(build_test_spec(selected_test_class, extra_args), encoding="utf-8")

    app_arn = args.app_arn or upload_file(df, app_apk, "ANDROID_APP")
    test_arn = args.test_arn or upload_file(df, test_apk, "INSTRUMENTATION_TEST_PACKAGE")
    spec_arn = upload_file(df, spec_path, "INSTRUMENTATION_TEST_SPEC")
    pool_arn = ensure_device_pool(df, args.pool_name, args.device_arn)

    # Re-verify budget and schedule under an exclusive lock so concurrent invocations cannot oversubscribe $200.
    scheduled = schedule_run_guarded(
        df,
        lock_path=args.lock_file,
        ceiling_usd=args.ceiling_usd,
        projected_new_run_usd=projected_new_run_usd,
        control_ssh=args.control_ssh,
        schedule_kwargs={
            "projectArn": PROJECT_ARN,
            "appArn": app_arn,
            "devicePoolArn": pool_arn,
            "name": args.name,
            "test": {
                "type": "INSTRUMENTATION",
                "testPackageArn": test_arn,
                "testSpecArn": spec_arn,
            },
            "executionConfiguration": {"jobTimeoutMinutes": job_timeout_minutes},
        },
    )
    run_arn = scheduled["arn"]
    (out_dir / "scheduled.json").write_text(
        json.dumps(
            {
                "name": args.name,
                "runArn": run_arn,
                "poolArn": pool_arn,
                "appArn": app_arn,
                "testArn": test_arn,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Scheduled run: {args.name} -> {run_arn}", flush=True)
    if args.no_wait:
        return

    while True:
        run_info = df.get_run(arn=run_arn)["run"]
        print(
            f"  [{run_info['status']}] result={run_info.get('result')} "
            f"counters={run_info.get('counters')} minutes={run_info.get('deviceMinutes')}",
            flush=True,
        )
        if run_info["status"] == "COMPLETED":
            reports = extract_reports(df, run_arn, out_dir / "artifacts")
            (out_dir / "reports.json").write_text(json.dumps(reports, indent=2) + "\n", encoding="utf-8")
            (out_dir / "run.json").write_text(
                json.dumps(
                    {
                        "arn": run_info["arn"],
                        "name": run_info.get("name"),
                        "status": run_info.get("status"),
                        "result": run_info.get("result"),
                        "counters": run_info.get("counters"),
                        "deviceMinutes": run_info.get("deviceMinutes"),
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            print(f"Extracted {len(reports)} qualification report(s) -> {out_dir / 'reports.json'}", flush=True)
            if run_info.get("result") != "PASSED":
                raise SystemExit(f"Device Farm run ended with {run_info.get('result')}")
            return
        time.sleep(25)


if __name__ == "__main__":
    main()
