"""Unit tests for scripts/devicefarm-qualify.py: $160 D-1126/$200 D89 budget guard, TestSpec, and report parsing."""

from __future__ import annotations

import datetime as dt
import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/devicefarm-qualify.py"
SPEC = importlib.util.spec_from_file_location("devicefarm_qualify", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def _run(year: int, month: int, day: int, hour: int, metered: float, tz: dt.tzinfo = dt.timezone.utc) -> dict:
    return {
        "created": dt.datetime(year, month, day, hour, 0, tzinfo=tz),
        "deviceMinutes": {"metered": metered},
    }


class FakeDeviceFarmClient:
    def __init__(self, project_pages: list[dict], run_pages_by_project: dict[str, list[dict]]) -> None:
        self.project_pages = project_pages
        self.run_pages_by_project = run_pages_by_project
        self.project_calls: list[str | None] = []
        self.run_calls: list[tuple[str, str | None]] = []

    def list_projects(self, nextToken: str | None = None) -> dict:
        self.project_calls.append(nextToken)
        index = 0 if nextToken is None else int(nextToken.removeprefix("proj-page-"))
        return self.project_pages[index]

    def list_runs(self, arn: str, nextToken: str | None = None) -> dict:
        self.run_calls.append((arn, nextToken))
        pages = self.run_pages_by_project.get(arn, [{"runs": []}])
        index = 0 if nextToken is None else int(nextToken.removeprefix("run-page-"))
        return pages[index]


class DeviceFarmQualifyTest(unittest.TestCase):
    def test_budget_guard_paginates_all_list_runs_and_projects_and_denies_at_160_usd(self) -> None:
        p1 = "arn:aws:devicefarm:us-west-2:111111111111:project:one"
        p2 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 20, 14, 0, tzinfo=dt.timezone.utc)

        # Page 1 of p1 has only 100 min ($17.00); later pages across p1 + p2 push total to 1,000 min ($170.00 >= $160).
        fake = FakeDeviceFarmClient(
            project_pages=[
                {"projects": [{"arn": p1}], "nextToken": "proj-page-1"},
                {"projects": [{"arn": p2}]},
            ],
            run_pages_by_project={
                p1: [
                    {"runs": [_run(2026, 11, 2, 10, 100.0), _run(2026, 10, 31, 23, 999.0)], "nextToken": "run-page-1"},
                    {"runs": [_run(2026, 11, 10, 8, 400.0)], "nextToken": "run-page-2"},
                    {"runs": [_run(2026, 11, 18, 12, 300.0)]},
                ],
                p2: [
                    {"runs": [_run(2026, 11, 20, 1, 200.0)]},
                ],
            },
        )

        with self.assertRaises(SystemExit) as ctx:
            MODULE.check_budget(fake, now=now)
        self.assertIn("$170.00 >= $160.00", str(ctx.exception))
        self.assertEqual([None, "proj-page-1"], fake.project_calls)
        self.assertEqual(
            [(p1, None), (p1, "run-page-1"), (p1, "run-page-2"), (p2, None)],
            fake.run_calls,
        )

    def test_budget_guard_admits_immediately_below_160_and_denies_at_exact_160(self) -> None:
        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 20, 14, 0, tzinfo=dt.timezone.utc)

        # Immediately below $160.00 ($159.9989) must be admitted.
        below_minutes = 941.17
        admit_client = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={p1: [{"runs": [_run(2026, 11, 15, 10, below_minutes)]}]},
        )
        spent = MODULE.check_budget(admit_client, now=now)
        self.assertLess(spent, 160.0)
        self.assertAlmostEqual(below_minutes * 0.17, spent, places=4)

        # Exactly $160.00 must be denied.
        at_threshold_minutes = 160.0 / MODULE.RATE_PER_MINUTE
        deny_client = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={p1: [{"runs": [_run(2026, 11, 15, 10, at_threshold_minutes)]}]},
        )
        with self.assertRaises(SystemExit) as ctx:
            MODULE.check_budget(deny_client, now=now)
        self.assertIn("$160.00 >= $160.00", str(ctx.exception))

    def test_budget_guard_allows_multi_page_spend_below_160_and_normalizes_timezones(self) -> None:
        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 20, 14, 0, tzinfo=dt.timezone.utc)
        plus_two = dt.timezone(dt.timedelta(hours=2))
        fake = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={
                p1: [
                    {
                        "runs": [
                            # 2026-11-01 01:00+02:00 is 2026-10-31 23:00Z -> October UTC, must NOT count in November.
                            _run(2026, 11, 1, 1, 800.0, tz=plus_two),
                            _run(2026, 11, 5, 12, 300.0),
                        ],
                        "nextToken": "run-page-1",
                    },
                    {
                        "runs": [
                            {"created": "2026-11-19T20:00:00+00:00", "deviceMinutes": {"metered": 200.0}},
                        ],
                    },
                ],
            },
        )
        spent = MODULE.check_budget(fake, now=now)
        self.assertAlmostEqual(500.0 * 0.17, spent, places=4)

    def test_budget_guard_refuses_cyclic_next_token_and_ceiling_override_above_160(self) -> None:
        p1 = MODULE.PROJECT_ARN
        cyclic = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={
                p1: [{"runs": [], "nextToken": "run-page-0"}],
            },
        )
        with self.assertRaises(SystemExit) as cycle_err:
            MODULE.check_budget(cyclic)
        self.assertIn("Repeated pagination nextToken", str(cycle_err.exception))

        valid = FakeDeviceFarmClient(project_pages=[{"projects": []}], run_pages_by_project={})
        with self.assertRaises(SystemExit):
            MODULE.check_budget(valid, ceiling_usd=160.01)
        with self.assertRaises(SystemExit):
            MODULE.check_budget(valid, ceiling_usd=200.0)

    def test_budget_guard_rejects_non_finite_cli_ceiling_values(self) -> None:
        valid = FakeDeviceFarmClient(project_pages=[{"projects": []}], run_pages_by_project={})
        for raw_value in ("nan", "inf", "-inf"):
            parsed = MODULE.build_parser().parse_args([f"--ceiling-usd={raw_value}"])
            with self.assertRaises(SystemExit):
                MODULE.check_budget(valid, ceiling_usd=parsed.ceiling_usd)

    def test_default_test_class_selector_matches_selected_lanes(self) -> None:
        default_args = MODULE.build_parser().parse_args([])
        default_selector = MODULE.resolve_test_class(default_args.test_class, default_args.long_engine)
        self.assertIn("test01HardwareCaptureLifecycleInterruptionsAndAacControl", default_selector)
        self.assertIn("test02PairedFiveMinuteEquivalenceAndPressure", default_selector)
        self.assertNotIn("test03ThirtyMinuteContinuousQualification", default_selector)

        long_args = MODULE.build_parser().parse_args(["--long-engine", "parakeet"])
        long_selector = MODULE.resolve_test_class(long_args.test_class, long_args.long_engine)
        self.assertIn("test03ThirtyMinuteContinuousQualification", long_selector)

        explicit_target = (
            "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest"
            "#test01HardwareCaptureLifecycleInterruptionsAndAacControl"
        )
        explicit_args = MODULE.build_parser().parse_args(["--test-class", explicit_target])
        self.assertEqual(
            explicit_target,
            MODULE.resolve_test_class(explicit_args.test_class, explicit_args.long_engine),
        )

    def test_timeout_minutes_scales_for_combined_long_lanes_and_validates_explicit_bounds(self) -> None:
        core_args = MODULE.build_parser().parse_args([])
        core_selector = MODULE.resolve_test_class(core_args.test_class, core_args.long_engine)
        self.assertEqual(
            105,
            MODULE.resolve_timeout_minutes(core_args.timeout_minutes, core_selector, core_args.long_engine),
        )

        for engine in ("parakeet", "whistle"):
            # Implicit combined selector (core + test03) -> 150 min.
            implicit = MODULE.build_parser().parse_args(["--long-engine", engine])
            implicit_sel = MODULE.resolve_test_class(implicit.test_class, implicit.long_engine)
            self.assertEqual(
                150,
                MODULE.resolve_timeout_minutes(implicit.timeout_minutes, implicit_sel, implicit.long_engine),
            )

            # Explicit whole-class selector alongside --long-engine -> 150 min.
            explicit_full = MODULE.build_parser().parse_args(
                ["--test-class", MODULE.DEFAULT_TEST_CLASS, "--long-engine", engine],
            )
            explicit_full_sel = MODULE.resolve_test_class(explicit_full.test_class, explicit_full.long_engine)
            self.assertEqual(
                150,
                MODULE.resolve_timeout_minutes(
                    explicit_full.timeout_minutes,
                    explicit_full_sel,
                    explicit_full.long_engine,
                ),
            )

        explicit_args = MODULE.build_parser().parse_args(["--long-engine", "whistle", "--timeout-minutes", "85"])
        explicit_sel = MODULE.resolve_test_class(explicit_args.test_class, explicit_args.long_engine)
        self.assertEqual(
            85,
            MODULE.resolve_timeout_minutes(explicit_args.timeout_minutes, explicit_sel, explicit_args.long_engine),
        )

        with self.assertRaises(SystemExit):
            MODULE.resolve_timeout_minutes(151, MODULE.DEFAULT_TEST_CLASS, "whistle")
        with self.assertRaises(SystemExit):
            MODULE.resolve_timeout_minutes(0, MODULE.DEFAULT_TEST_CLASS, "none")

    def test_budget_guard_rejects_multi_device_and_outstanding_runs_exceeding_200_usd_budget(self) -> None:
        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 20, 14, 0, tzinfo=dt.timezone.utc)

        # $159.00 metered + 2-handset x 150-min run ($51.00) = $210.00 > $200.00 monthly budget -> DENIED.
        near_cap_client = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={p1: [{"runs": [_run(2026, 11, 15, 10, 159.0 / MODULE.RATE_PER_MINUTE)]}]},
        )
        two_device_150m_usd = MODULE.project_run_cost_usd(device_count=2, timeout_minutes=150)
        with self.assertRaises(SystemExit) as ctx:
            MODULE.check_budget(near_cap_client, now=now, projected_new_run_usd=two_device_150m_usd)
        self.assertIn("$210.00", str(ctx.exception))

        # $120.00 metered + 1 outstanding RUNNING 2-device 150-min run ($51.00) = $171.00 committed.
        # Adding 1-device ($25.50 -> $196.50 <= $200) is admitted; 2-device ($51 -> $222) is denied.
        outstanding_run = {
            "created": dt.datetime(2026, 11, 20, 13, 0, tzinfo=dt.timezone.utc),
            "status": "RUNNING",
            "totalJobs": 2,
            "jobTimeoutMinutes": 150,
            "deviceMinutes": {"metered": 0.0},
        }
        outstanding_client = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={
                p1: [{"runs": [_run(2026, 11, 10, 10, 120.0 / MODULE.RATE_PER_MINUTE), outstanding_run]}],
            },
        )
        one_device_150m_usd = MODULE.project_run_cost_usd(device_count=1, timeout_minutes=150)
        spent = MODULE.check_budget(outstanding_client, now=now, projected_new_run_usd=one_device_150m_usd)
        self.assertAlmostEqual(120.0, spent, places=2)
        with self.assertRaises(SystemExit) as ctx2:
            MODULE.check_budget(outstanding_client, now=now, projected_new_run_usd=two_device_150m_usd)
        self.assertIn("$222.00", str(ctx2.exception))

    def test_budget_guard_counts_prior_month_outstanding_runs_and_fails_check_budget_only(self) -> None:
        from unittest import mock

        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 1, 0, 5, tzinfo=dt.timezone.utc)
        # Created in October, still PENDING in November: 8 devices x 150 min x $0.17 = $204.00 > $200.00.
        prior_month_pending = {
            "created": dt.datetime(2026, 10, 31, 23, 55, tzinfo=dt.timezone.utc),
            "status": "PENDING",
            "totalJobs": 8,
            "jobTimeoutMinutes": 150,
            "deviceMinutes": {"metered": 0.0},
        }
        client = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={p1: [{"runs": [prior_month_pending]}]},
        )
        one_device_150m_usd = MODULE.project_run_cost_usd(device_count=1, timeout_minutes=150)
        with self.assertRaises(SystemExit) as submit_err:
            MODULE.check_budget(client, now=now, projected_new_run_usd=one_device_150m_usd)
        self.assertIn("$229.50", str(submit_err.exception))

        # --check-budget-only must also exit non-zero when metered + outstanding exceeds $200.00.
        with mock.patch.object(MODULE, "open_client", return_value=client):
            with self.assertRaises(SystemExit) as cli_err:
                MODULE.main(["--check-budget-only"])
        self.assertIn("$204.00", str(cli_err.exception))

        # Completed cross-month rollover run (created in Oct, stopped in Nov with 960 min = $163.20) must be counted.
        completed_rollover = {
            "created": dt.datetime(2026, 10, 31, 23, 50, tzinfo=dt.timezone.utc),
            "started": dt.datetime(2026, 11, 1, 0, 1, tzinfo=dt.timezone.utc),
            "stopped": dt.datetime(2026, 11, 1, 2, 0, tzinfo=dt.timezone.utc),
            "status": "COMPLETED",
            "deviceMinutes": {"metered": 960.0},
        }
        rollover_client = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={p1: [{"runs": [completed_rollover]}]},
        )
        two_device_150m_usd = MODULE.project_run_cost_usd(device_count=2, timeout_minutes=150)
        with self.assertRaises(SystemExit) as rollover_err:
            MODULE.check_budget(rollover_client, now=now, projected_new_run_usd=two_device_150m_usd)
        self.assertIn("$163.20 >= $160.00", str(rollover_err.exception))

    def test_budget_guard_rejects_unknown_total_jobs_and_keeps_full_prior_month_active_reservation(self) -> None:
        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 1, 0, 5, tzinfo=dt.timezone.utc)

        # 1. Outstanding run with missing, zero, or negative totalJobs must fail closed (cannot assume 1 device).
        for bad_total_jobs in (None, 0, -1):
            unknown_jobs_run: dict = {
                "arn": "arn:aws:devicefarm:us-west-2:1:run:pending-unknown",
                "created": now,
                "status": "SCHEDULING",
                "deviceMinutes": {"metered": 0.0},
            }
            if bad_total_jobs is not None:
                unknown_jobs_run["totalJobs"] = bad_total_jobs
            unknown_client = FakeDeviceFarmClient(
                project_pages=[{"projects": [{"arn": p1}]}],
                run_pages_by_project={p1: [{"runs": [unknown_jobs_run]}]},
            )
            with self.assertRaises(SystemExit) as unknown_err:
                MODULE.check_budget(unknown_client, now=now, projected_new_run_usd=17.0)
            self.assertIn("unknown device count", str(unknown_err.exception))

        # 2. Active prior-month-timestamped run with 100 metered min NOT counted in Nov's metered_minutes:
        #    must keep its full 150-min ($25.50) reservation rather than subtracting 100 min ($8.50).
        #    $150.00 Nov metered + $25.50 full active reservation + $25.50 new run = $201.00 > $200.00 -> DENIED.
        prior_month_active = {
            "created": dt.datetime(2026, 10, 31, 22, 0, tzinfo=dt.timezone.utc),
            "started": dt.datetime(2026, 10, 31, 22, 5, tzinfo=dt.timezone.utc),
            "status": "RUNNING",
            "totalJobs": 1,
            "jobTimeoutMinutes": 150,
            "deviceMinutes": {"metered": 100.0},
        }
        active_rollover_client = FakeDeviceFarmClient(
            project_pages=[{"projects": [{"arn": p1}]}],
            run_pages_by_project={
                p1: [{"runs": [_run(2026, 11, 1, 0, 150.0 / MODULE.RATE_PER_MINUTE), prior_month_active]}],
            },
        )
        one_device_150m_usd = MODULE.project_run_cost_usd(device_count=1, timeout_minutes=150)
        with self.assertRaises(SystemExit) as active_err:
            MODULE.check_budget(active_rollover_client, now=now, projected_new_run_usd=one_device_150m_usd)
        self.assertIn("$201.00", str(active_err.exception))

    def test_schedule_run_guarded_serializes_concurrent_submissions_under_200_usd(self) -> None:
        import fcntl
        import tempfile
        import threading

        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 20, 14, 0, tzinfo=dt.timezone.utc)
        entered_schedule = threading.Event()
        release_schedule = threading.Event()

        class ThreadSafeFakeClient:
            def __init__(self) -> None:
                self._lock = threading.Lock()
                self._runs: list[dict] = [_run(2026, 11, 15, 10, 159.0 / MODULE.RATE_PER_MINUTE)]
                self.scheduled_count = 0

            def list_projects(self, nextToken: str | None = None) -> dict:
                return {"projects": [{"arn": p1}]}

            def list_runs(self, arn: str, nextToken: str | None = None) -> dict:
                with self._lock:
                    return {"runs": list(self._runs)}

            def schedule_run(self, **kwargs) -> dict:
                entered_schedule.set()
                release_schedule.wait(timeout=5.0)
                with self._lock:
                    self.scheduled_count += 1
                    run_arn = f"arn:aws:devicefarm:us-west-2:1:run:{self.scheduled_count}"
                    self._runs.append(
                        {
                            "arn": run_arn,
                            "created": now,
                            "status": "SCHEDULING",
                            "totalJobs": 1,
                            "jobTimeoutMinutes": kwargs["executionConfiguration"]["jobTimeoutMinutes"],
                            "deviceMinutes": {"metered": 0.0},
                        },
                    )
                    return {"run": {"arn": run_arn}}

        fake = ThreadSafeFakeClient()
        one_device_150m_usd = MODULE.project_run_cost_usd(device_count=1, timeout_minutes=150)
        outcomes: list[str] = []
        outcomes_lock = threading.Lock()

        with tempfile.TemporaryDirectory() as tmp:
            lock_path = Path(tmp) / "schedule.lock"

            def worker(idx: int) -> None:
                try:
                    MODULE.schedule_run_guarded(
                        fake,
                        lock_path=lock_path,
                        ceiling_usd=MODULE.MONTHLY_DENY_USD,
                        projected_new_run_usd=one_device_150m_usd,
                        schedule_kwargs={"executionConfiguration": {"jobTimeoutMinutes": 150}, "name": f"run-{idx}"},
                        now=now,
                    )
                    result = "admitted"
                except SystemExit:
                    result = "denied"
                with outcomes_lock:
                    outcomes.append(result)

            t1 = threading.Thread(target=worker, args=(0,))
            t1.start()
            self.assertTrue(entered_schedule.wait(timeout=5.0), "First worker did not enter schedule_run")

            # Verify an independent file descriptor cannot acquire the exclusive lock while worker 1 holds it.
            with lock_path.open("a+", encoding="utf-8") as probe:
                with self.assertRaises(BlockingIOError):
                    fcntl.flock(probe.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

            t2 = threading.Thread(target=worker, args=(1,))
            t2.start()
            release_schedule.set()
            t1.join(timeout=10.0)
            t2.join(timeout=10.0)

        self.assertEqual(1, fake.scheduled_count)
        self.assertEqual(["admitted", "denied"], sorted(outcomes))

    def test_schedule_run_guarded_uses_remote_ssh_lock_when_control_ssh_is_set(self) -> None:
        import io
        from unittest import mock

        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 20, 14, 0, tzinfo=dt.timezone.utc)
        events: list[str] = []

        class FakeRemoteLockProc:
            def __init__(self, cmd: list[str], **kwargs) -> None:
                self.cmd = cmd
                self.stdout = io.StringIO("LOCKED\n")
                self.stderr = io.StringIO("")
                self.stdin = io.StringIO()
                events.append(f"lock_acquire:{cmd[1]}")

            def wait(self, timeout: int = 15) -> int:
                events.append("lock_release")
                return 0

            def kill(self) -> None:
                events.append("lock_kill")

        class FakeClient:
            def list_projects(self, nextToken: str | None = None) -> dict:
                events.append("list_projects")
                return {"projects": [{"arn": p1}]}

            def list_runs(self, arn: str, nextToken: str | None = None) -> dict:
                events.append("list_runs")
                return {"runs": []}

            def schedule_run(self, **kwargs) -> dict:
                events.append("schedule_run")
                return {"run": {"arn": "arn:run:1"}}

        with mock.patch("subprocess.Popen", side_effect=FakeRemoteLockProc) as popen_mock:
            result = MODULE.schedule_run_guarded(
                FakeClient(),
                lock_path=Path("/unused/local.lock"),
                ceiling_usd=MODULE.MONTHLY_DENY_USD,
                projected_new_run_usd=17.0,
                control_ssh="drmeyers",
                schedule_kwargs={"name": "remote-run"},
                now=now,
            )

        self.assertEqual({"arn": "arn:run:1"}, result)
        self.assertEqual("ssh", popen_mock.call_args.args[0][0])
        self.assertEqual("drmeyers", popen_mock.call_args.args[0][1])
        self.assertIn("flock -x ~/.cache/cassini-devicefarm/schedule.lock", popen_mock.call_args.args[0][2])
        self.assertEqual(
            ["lock_acquire:drmeyers", "list_projects", "list_runs", "schedule_run", "lock_release"],
            events,
        )

    def test_test_spec_validates_arguments_and_rejects_shell_metacharacters(self) -> None:
        spec = MODULE.build_test_spec(
            "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test02Paired",
            {"cassiniHandsetQual": "true", "shortSeconds": "300"},
        )
        self.assertIn("android_test_host: amazon_linux_2", spec)
        self.assertIn("-e cassiniHandsetQual true", spec)
        self.assertIn("android.permission.RECORD_AUDIO", spec)

        with self.assertRaises(SystemExit):
            MODULE.build_test_spec("bad;rm -rf /", {})
        with self.assertRaises(SystemExit):
            MODULE.build_test_spec("io.codemyriad.Test", {"badKey": "$(whoami)"})

    @staticmethod
    def _extract_testspec_check_script(spec_yaml: str) -> str:
        marker = "      - |-\n"
        start = spec_yaml.index(marker) + len(marker)
        lines: list[str] = []
        for line in spec_yaml[start:].splitlines():
            if line and not line.startswith("        "):
                break
            lines.append(line[8:] if line.startswith("        ") else "")
        return "\n".join(lines) + "\n"

    def _run_testspec_check(self, selector: str, log_text: str) -> int:
        import os
        import subprocess
        import tempfile

        spec = MODULE.build_test_spec(selector, {"cassiniHandsetQual": "true"})
        script = self._extract_testspec_check_script(spec)
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "instrument.log").write_text(log_text, encoding="utf-8")
            env = dict(os.environ, DEVICEFARM_LOG_DIR=tmp)
            proc = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
        return proc.returncode

    @staticmethod
    def _instrument_log(passed: list[tuple[str, str]]) -> str:
        lines: list[str] = []
        for cls, method in passed:
            lines.extend(
                [
                    f"INSTRUMENTATION_STATUS: class={cls}",
                    f"INSTRUMENTATION_STATUS: test={method}",
                    "INSTRUMENTATION_STATUS_CODE: 1",
                    f"INSTRUMENTATION_STATUS: class={cls}",
                    f"INSTRUMENTATION_STATUS: test={method}",
                    "INSTRUMENTATION_STATUS_CODE: 0",
                ],
            )
        suffix = "test" if len(passed) == 1 else "tests"
        lines.append(f"OK ({len(passed)} {suffix})")
        return "\n".join(lines) + "\n"

    def test_expected_method_verification_rejects_smoke_only_log_for_three_method_request(self) -> None:
        default_selector = MODULE.resolve_test_class(None, "none")
        spec = MODULE.build_test_spec(default_selector, {"cassiniHandsetQual": "true"})
        self.assertIn('if [ "$STARTED" -lt 3 ]', spec)
        self.assertIn('! [ "$OK_COUNT" -eq 3 ]', spec)
        self.assertIn("test01HardwareCaptureLifecycleInterruptionsAndAacControl", spec)
        self.assertIn("test02PairedFiveMinuteEquivalenceAndPressure", spec)

        qual_cls = MODULE.DEFAULT_TEST_CLASS
        smoke_only_log = self._instrument_log([(qual_cls, "qualificationHarnessTelemetryAndPrefixContractSmoke")])
        with self.assertRaises(SystemExit) as err:
            MODULE.verify_expected_methods_in_log(smoke_only_log, default_selector)
        self.assertIn("expected 3", str(err.exception))
        self.assertNotEqual(0, self._run_testspec_check(default_selector, smoke_only_log))

        full_three_method_log = self._instrument_log(
            [
                (qual_cls, "qualificationHarnessTelemetryAndPrefixContractSmoke"),
                (qual_cls, "test01HardwareCaptureLifecycleInterruptionsAndAacControl"),
                (qual_cls, "test02PairedFiveMinuteEquivalenceAndPressure"),
            ],
        )
        MODULE.verify_expected_methods_in_log(full_three_method_log, default_selector)
        self.assertEqual(0, self._run_testspec_check(default_selector, full_three_method_log))

    def test_expected_method_verification_enforces_class_names_and_mixed_whole_class_selectors(self) -> None:
        same_method_selector = "pkg.A#check,pkg.B#check"
        wrong_class_log = self._instrument_log([("pkg.A", "check"), ("pkg.C", "different")])
        with self.assertRaises(SystemExit) as err:
            MODULE.verify_expected_methods_in_log(wrong_class_log, same_method_selector)
        self.assertIn("pkg.B#check", str(err.exception))
        self.assertNotEqual(0, self._run_testspec_check(same_method_selector, wrong_class_log))

        both_classes_log = self._instrument_log([("pkg.A", "check"), ("pkg.B", "check")])
        MODULE.verify_expected_methods_in_log(both_classes_log, same_method_selector)
        self.assertEqual(0, self._run_testspec_check(same_method_selector, both_classes_log))

        mixed_selector = "pkg.A,pkg.B#required"
        smoke_only_mixed_log = self._instrument_log([("pkg.A", "smoke")])
        with self.assertRaises(SystemExit) as mixed_err:
            MODULE.verify_expected_methods_in_log(smoke_only_mixed_log, mixed_selector)
        self.assertIn("expected >= 2", str(mixed_err.exception))
        self.assertNotEqual(0, self._run_testspec_check(mixed_selector, smoke_only_mixed_log))

        missing_whole_class_only_b = self._instrument_log([("pkg.B", "required")])
        with self.assertRaises(SystemExit):
            MODULE.verify_expected_methods_in_log(missing_whole_class_only_b, mixed_selector)
        self.assertNotEqual(0, self._run_testspec_check(mixed_selector, missing_whole_class_only_b))

        missing_whole_class_two_tests = self._instrument_log([("pkg.B", "required"), ("pkg.C", "other")])
        with self.assertRaises(SystemExit) as cls_err:
            MODULE.verify_expected_methods_in_log(missing_whole_class_two_tests, mixed_selector)
        self.assertIn("pkg.A", str(cls_err.exception))
        self.assertNotEqual(0, self._run_testspec_check(mixed_selector, missing_whole_class_two_tests))

        passing_mixed_log = self._instrument_log([("pkg.A", "smoke"), ("pkg.B", "required")])
        MODULE.verify_expected_methods_in_log(passing_mixed_log, mixed_selector)
        self.assertEqual(0, self._run_testspec_check(mixed_selector, passing_mixed_log))

    def test_nested_dollar_class_selector_is_preserved_through_adb_shell_and_verifier_loop(self) -> None:
        import os
        import subprocess
        import tempfile

        selector = "pkg.Outer$Inner#check"
        spec = MODULE.build_test_spec(selector, {"cassiniHandsetQual": "true"})

        # 1) Verifier loop must match literal pkg.Outer$Inner#check and reject expanded pkg.Outer#check.
        expanded_log = self._instrument_log([("pkg.Outer", "check")])
        with self.assertRaises(SystemExit):
            MODULE.verify_expected_methods_in_log(expanded_log, selector)
        self.assertNotEqual(0, self._run_testspec_check(selector, expanded_log))

        literal_log = self._instrument_log([("pkg.Outer$Inner", "check")])
        MODULE.verify_expected_methods_in_log(literal_log, selector)
        self.assertEqual(0, self._run_testspec_check(selector, literal_log))

        # 2) Folded adb shell command must preserve literal pkg.Outer$Inner#check across host bash AND device sh.
        test_phase = spec.split("  test:\n    commands:\n", 1)[1].split("\n      - |-\n", 1)[0]
        folded_lines = [
            line.strip()
            for line in test_phase.split("      - >-\n", 1)[1].splitlines()
            if line.strip()
        ]
        adb_cmd = " ".join(folded_lines)
        with tempfile.TemporaryDirectory() as tmp:
            harness = (
                'adb() { shift 2; shift; sh -c \'am() { printf "%s\\n" "$@"; }; eval "$1"\' _ "$1"; }\n'
                f"{adb_cmd}\n"
            )
            env = dict(
                os.environ,
                DEVICEFARM_DEVICE_UDID="dev1",
                DEVICEFARM_TEST_PACKAGE_NAME="io.test",
                DEVICEFARM_TEST_PACKAGE_RUNNER="io.test.Runner",
                DEVICEFARM_LOG_DIR=tmp,
                Inner="EXPANDED_BUG",
            )
            proc = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", harness],
                env=env,
                capture_output=True,
                text=True,
                check=True,
            )
        argv = proc.stdout.splitlines()
        self.assertIn("pkg.Outer$Inner#check", argv)
        self.assertNotIn("pkg.OuterEXPANDED_BUG#check", argv)
        self.assertNotIn("pkg.Outer#check", argv)

    def test_extract_reports_from_text_parses_multiple_framed_blocks(self) -> None:
        log = (
            "INSTRUMENTATION_STATUS: stream=\n"
            "CASSINI_HANDSET_QUAL_BEGIN:test01\n"
            '{"test": "test01", "passed": true}\n'
            "CASSINI_HANDSET_QUAL_END:test01\n"
            "noise between blocks\n"
            "CASSINI_HANDSET_QUAL_BEGIN:test02\n"
            '{"test": "test02", "speedup": 2.15}\n'
            "CASSINI_HANDSET_QUAL_END:test02\n"
        )
        reports = MODULE.extract_reports_from_text(log)
        self.assertEqual(
            [{"test": "test01", "passed": True}, {"test": "test02", "speedup": 2.15}],
            reports,
        )

    def test_extract_reports_ignores_device_farm_logcat_json_artifacts(self) -> None:
        import io
        import tempfile
        from unittest import mock

        class FakeArtifactClient:
            def list_jobs(self, arn: str) -> dict:
                return {"jobs": [{"arn": "job:1", "name": "Pixel 8"}]}

            def list_suites(self, arn: str) -> dict:
                return {"suites": [{"arn": "suite:1", "name": "Tests"}]}

            def list_tests(self, arn: str) -> dict:
                return {"tests": [{"arn": "test:1", "name": "Tests"}]}

            def list_artifacts(self, arn: str, type: str) -> dict:
                if type == "LOG":
                    return {
                        "artifacts": [
                            {
                                "name": "ListArtifactType.log",
                                "extension": "json",
                                "url": "https://example.invalid/logcat.json",
                            },
                        ],
                    }
                return {
                    "artifacts": [
                        {
                            "name": "handset-qual-reports",
                            "extension": "json",
                            "url": "https://example.invalid/reports.json",
                        },
                    ],
                }

        payloads = {
            "https://example.invalid/logcat.json": '[{"msg": "unrelated logcat line"}]',
            "https://example.invalid/reports.json": '[{"test": "test01", "passed": true}]',
        }

        def fake_urlopen(url: str, timeout: int = 180) -> io.BytesIO:
            return io.BytesIO(payloads[url].encode("utf-8"))

        with tempfile.TemporaryDirectory() as tmp, mock.patch("urllib.request.urlopen", side_effect=fake_urlopen):
            extracted = MODULE.extract_reports(FakeArtifactClient(), "run:1", Path(tmp))
        self.assertEqual([{"test": "test01", "passed": True}], extracted)

    def test_concurrent_same_name_submissions_isolate_testspecs(self) -> None:
        import tempfile
        import threading
        from unittest import mock

        for bad_name in ("../escape", "a/b", ".hidden", "-dash", "bad..name"):
            with self.assertRaises(SystemExit):
                MODULE.create_run_out_dir(bad_name)

        first_in_apk_upload = threading.Event()
        second_spec_uploaded = threading.Event()
        spec_lock = threading.Lock()
        uploaded_specs: dict[str, tuple[Path, str]] = {}
        scheduled_specs: list[str] = []
        errors: list[BaseException] = []

        class FakeClient:
            def list_projects(self, nextToken: str | None = None) -> dict:
                return {"projects": [{"arn": MODULE.PROJECT_ARN}]}

            def list_runs(self, arn: str, nextToken: str | None = None) -> dict:
                return {"runs": []}

            def list_device_pools(self, arn: str, type: str = "PRIVATE") -> dict:
                return {"devicePools": [{"name": "pool", "arn": "arn:pool:1"}]}

            def update_device_pool(self, **kwargs) -> dict:
                return {"devicePool": {"arn": "arn:pool:1"}}

            def create_device_pool(self, **kwargs) -> dict:
                return {"devicePool": {"arn": "arn:pool:1"}}

            def schedule_run(self, **kwargs) -> dict:
                spec_arn = kwargs["test"]["testSpecArn"]
                with spec_lock:
                    scheduled_specs.append(uploaded_specs[spec_arn][1])
                    idx = len(scheduled_specs)
                return {"run": {"arn": f"arn:run:{idx}"}}

        def fake_upload(df, path: Path, upload_type: str) -> str:
            thread_name = threading.current_thread().name
            if upload_type == "ANDROID_APP" and thread_name == "alpha":
                first_in_apk_upload.set()
                assert second_spec_uploaded.wait(timeout=5.0), "beta did not upload its testspec in time"
            if upload_type == "INSTRUMENTATION_TEST_SPEC":
                content = path.read_text(encoding="utf-8")
                arn = f"arn:spec:{thread_name}"
                with spec_lock:
                    uploaded_specs[arn] = (path, content)
                if thread_name == "beta":
                    second_spec_uploaded.set()
                return arn
            return f"arn:{upload_type}:{thread_name}"

        with tempfile.TemporaryDirectory() as tmp:
            tmp_root = Path(tmp)
            app_apk = tmp_root / "app.apk"
            test_apk = tmp_root / "test.apk"
            app_apk.write_bytes(b"apk")
            test_apk.write_bytes(b"test")
            out_base = tmp_root / "out"
            lock_file = tmp_root / "schedule.lock"

            def run_submission(selector: str, wait_for_alpha: bool) -> None:
                try:
                    if wait_for_alpha:
                        assert first_in_apk_upload.wait(timeout=5.0), "alpha did not reach APK upload"
                    MODULE.main(
                        [
                            "--device-arn",
                            "arn:device:1",
                            "--pool-name",
                            "pool",
                            "--name",
                            "shared-run",
                            "--test-class",
                            selector,
                            "--out-dir",
                            str(out_base),
                            "--lock-file",
                            str(lock_file),
                            "--skip-build",
                            "--no-wait",
                        ],
                    )
                except BaseException as exc:  # pragma: no cover - surfaced below
                    errors.append(exc)

            with (
                mock.patch.object(MODULE, "open_client", return_value=FakeClient()),
                mock.patch.object(MODULE, "resolve_apks", return_value=(app_apk, test_apk)),
                mock.patch.object(MODULE, "upload_file", side_effect=fake_upload),
            ):
                t_alpha = threading.Thread(name="alpha", target=run_submission, args=("pkg.A#alpha", False))
                t_beta = threading.Thread(name="beta", target=run_submission, args=("pkg.B#beta", True))
                t_alpha.start()
                t_beta.start()
                t_alpha.join(timeout=10.0)
                t_beta.join(timeout=10.0)

        self.assertEqual([], errors)
        self.assertEqual(2, len(uploaded_specs))
        alpha_path, alpha_spec = uploaded_specs["arn:spec:alpha"]
        beta_path, beta_spec = uploaded_specs["arn:spec:beta"]
        self.assertNotEqual(alpha_path.parent, beta_path.parent)
        self.assertIn("pkg.A#alpha", alpha_spec)
        self.assertNotIn("pkg.B#beta", alpha_spec)
        self.assertIn("pkg.B#beta", beta_spec)
        self.assertNotIn("pkg.A#alpha", beta_spec)
        self.assertEqual(2, len(scheduled_specs))
        self.assertTrue(any("pkg.A#alpha" in s and "pkg.B#beta" not in s for s in scheduled_specs))
        self.assertTrue(any("pkg.B#beta" in s and "pkg.A#alpha" not in s for s in scheduled_specs))

    def test_collect_run_arn_isolates_two_runs_in_distinct_directories(self) -> None:
        import json
        import tempfile
        from unittest import mock

        arn1 = "arn:aws:devicefarm:us-west-2:123:run:proj/run-1"
        arn2 = "arn:aws:devicefarm:us-west-2:123:run:proj/run-2"
        runs = {
            arn1: {
                "arn": arn1,
                "name": "d1126-pixel8",
                "status": "COMPLETED",
                "result": "PASSED",
                "counters": {"passed": 3},
                "deviceMinutes": {"metered": 12.0},
            },
            arn2: {
                "arn": arn2,
                "name": "d1126-pixel8",
                "status": "COMPLETED",
                "result": "PASSED",
                "counters": {"passed": 3},
                "deviceMinutes": {"metered": 14.0},
            },
        }
        reports_by_arn = {
            arn1: [{"test": "run1-report"}],
            arn2: [{"test": "run2-report"}],
        }

        class FakeCollectClient:
            def get_run(self, arn: str) -> dict:
                return {"run": runs[arn]}

        with tempfile.TemporaryDirectory() as tmp:
            base_dir = Path(tmp) / "collected"
            with (
                mock.patch.object(MODULE, "open_client", return_value=FakeCollectClient()),
                mock.patch.object(MODULE, "extract_reports", side_effect=lambda _df, arn, _dir: reports_by_arn[arn]),
            ):
                MODULE.main(["--collect-run-arn", arn1, "--out-dir", str(base_dir)])
                MODULE.main(["--collect-run-arn", arn2, "--out-dir", str(base_dir)])

            run_dirs = sorted(p for p in base_dir.iterdir() if p.is_dir())
            self.assertEqual(2, len(run_dirs))
            collected_by_arn = {}
            for run_dir in run_dirs:
                run_meta = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
                report_data = json.loads((run_dir / "reports.json").read_text(encoding="utf-8"))
                collected_by_arn[run_meta["arn"]] = (run_dir, run_meta, report_data)

            self.assertEqual({arn1, arn2}, set(collected_by_arn.keys()))
            self.assertEqual([{"test": "run1-report"}], collected_by_arn[arn1][2])
            self.assertEqual([{"test": "run2-report"}], collected_by_arn[arn2][2])


if __name__ == "__main__":
    unittest.main()
