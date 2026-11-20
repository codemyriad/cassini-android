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
        self.assertEqual(
            75,
            MODULE.resolve_timeout_minutes(core_args.timeout_minutes, core_args.test_class, core_args.long_engine),
        )

        combined_whistle = MODULE.build_parser().parse_args(["--long-engine", "whistle"])
        self.assertEqual(
            150,
            MODULE.resolve_timeout_minutes(
                combined_whistle.timeout_minutes,
                combined_whistle.test_class,
                combined_whistle.long_engine,
            ),
        )

        explicit_args = MODULE.build_parser().parse_args(["--long-engine", "whistle", "--timeout-minutes", "85"])
        self.assertEqual(
            85,
            MODULE.resolve_timeout_minutes(
                explicit_args.timeout_minutes,
                explicit_args.test_class,
                explicit_args.long_engine,
            ),
        )

        with self.assertRaises(SystemExit):
            MODULE.resolve_timeout_minutes(151, None, "whistle")
        with self.assertRaises(SystemExit):
            MODULE.resolve_timeout_minutes(0, None, "none")

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
