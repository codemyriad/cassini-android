"""Unit tests for scripts/devicefarm-qualify.py: paginated $160 D-1126/$200 D89 budget guard, TestSpec, and report parsing."""

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

        explicit_args = MODULE.build_parser().parse_args(
            ["--test-class", "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test01HardwareCaptureLifecycleInterruptionsAndAacControl"],
        )
        self.assertEqual(
            "io.codemyriad.cassini.qualification.HandsetQualificationDeviceTest#test01HardwareCaptureLifecycleInterruptionsAndAacControl",
            MODULE.resolve_test_class(explicit_args.test_class, explicit_args.long_engine),
        )

    def test_timeout_minutes_scales_for_combined_long_lanes_and_validates_explicit_bounds(self) -> None:
        core_args = MODULE.build_parser().parse_args([])
        core_selector = MODULE.resolve_test_class(core_args.test_class, core_args.long_engine)
        self.assertEqual(105, MODULE.resolve_timeout_minutes(core_args.timeout_minutes, core_selector, core_args.long_engine))

        for engine in ("parakeet", "whistle"):
            # Implicit combined selector (core + test03) -> 150 min.
            implicit = MODULE.build_parser().parse_args(["--long-engine", engine])
            implicit_sel = MODULE.resolve_test_class(implicit.test_class, implicit.long_engine)
            self.assertEqual(150, MODULE.resolve_timeout_minutes(implicit.timeout_minutes, implicit_sel, implicit.long_engine))

            # Explicit whole-class selector alongside --long-engine -> 150 min.
            explicit_full = MODULE.build_parser().parse_args(["--test-class", MODULE.DEFAULT_TEST_CLASS, "--long-engine", engine])
            explicit_full_sel = MODULE.resolve_test_class(explicit_full.test_class, explicit_full.long_engine)
            self.assertEqual(
                150,
                MODULE.resolve_timeout_minutes(explicit_full.timeout_minutes, explicit_full_sel, explicit_full.long_engine),
            )

        explicit_args = MODULE.build_parser().parse_args(["--long-engine", "whistle", "--timeout-minutes", "85"])
        explicit_sel = MODULE.resolve_test_class(explicit_args.test_class, explicit_args.long_engine)
        self.assertEqual(85, MODULE.resolve_timeout_minutes(explicit_args.timeout_minutes, explicit_sel, explicit_args.long_engine))

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
        # Adding a 1-device 150-min run ($25.50 -> $196.50 <= $200.00) is admitted; 2-device ($51.00 -> $222.00) is denied.
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

    def test_schedule_run_guarded_serializes_concurrent_submissions_under_200_usd(self) -> None:
        import tempfile
        import threading

        p1 = MODULE.PROJECT_ARN
        now = dt.datetime(2026, 11, 20, 14, 0, tzinfo=dt.timezone.utc)

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
        barrier = threading.Barrier(2)
        outcomes: list[str] = []
        outcomes_lock = threading.Lock()

        with tempfile.TemporaryDirectory() as tmp:
            lock_path = Path(tmp) / "schedule.lock"

            def worker(idx: int) -> None:
                barrier.wait(timeout=5.0)
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

            threads = [threading.Thread(target=worker, args=(i,)) for i in range(2)]
            for t in threads:
                t.start()
            for t in threads:
                t.join(timeout=10.0)

        self.assertEqual(1, fake.scheduled_count)
        self.assertEqual(["admitted", "denied"], sorted(outcomes))

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


if __name__ == "__main__":
    unittest.main()
