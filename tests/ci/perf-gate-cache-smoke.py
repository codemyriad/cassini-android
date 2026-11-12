"""CI-only: prove CASSINI_PERF_GATE is a Test task input across incremental and build-cached runs."""

import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST_CLASS = 'io.codemyriad.cassini.core.export.ExportClockTest'
INIT_SCRIPT = """\
settingsEvaluated { settings ->
    settings.buildCache {
        local {
            directory = new File(System.getenv('PERF_GATE_CACHE_DIR'))
            enabled = true
            push = System.getenv('PERF_GATE_CACHE_PUSH') != 'false'
        }
        if (remote != null) {
            remote.enabled = false
        }
    }
}
"""


def run_gradle(cache_dir: Path, init_script: Path, *tasks: str, gate: str, push: bool = True) -> str:
    env = dict(
        os.environ,
        CASSINI_PERF_GATE=gate,
        PERF_GATE_CACHE_DIR=str(cache_dir),
        PERF_GATE_CACHE_PUSH='true' if push else 'false',
    )
    env.pop('ANDROID_HOME', None)
    env.pop('ANDROID_SDK_ROOT', None)
    cmd = [
        str(ROOT / 'gradlew'),
        '--no-daemon',
        '--configure-on-demand',
        '--build-cache',
        '--console=plain',
        '--init-script',
        str(init_script),
        *tasks,
    ]
    result = subprocess.run(
        cmd,
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        timeout=300,
    )
    if result.returncode != 0:
        print(result.stdout, end='')
        raise RuntimeError(f'Gradle failed (gate={gate!r}, tasks={tasks!r})')
    return result.stdout


def test_task_outcomes(output: str) -> list[str]:
    return [m.group(1) or 'EXECUTED' for m in re.finditer(r'^> Task :core:test(?: ([A-Z-]+))?$', output, re.M)]


def main() -> None:
    with tempfile.TemporaryDirectory(prefix='perf-gate-cache-', dir=os.environ.get('RUNNER_TEMP')) as tmp:
        tmp_path = Path(tmp)
        cache_dir = tmp_path / 'build-cache'
        cache_dir.mkdir()
        init_script = tmp_path / 'perf-gate-cache.init.gradle'
        init_script.write_text(INIT_SCRIPT)

        # 1. Populate local build cache with empty CASSINI_PERF_GATE (PR mode).
        first = run_gradle(cache_dir, init_script, ':core:cleanTest', ':core:test', '--tests', TEST_CLASS, gate='')
        if test_task_outcomes(first) != ['EXECUTED']:
            print(first, end='')
            raise RuntimeError(
                f'Expected initial empty-flag run to execute :core:test, got {test_task_outcomes(first)}',
            )

        # 2. Switching to CASSINI_PERF_GATE=1 immediately must not be UP-TO-DATE or FROM-CACHE.
        second = run_gradle(cache_dir, init_script, ':core:test', '--tests', TEST_CLASS, gate='1', push=False)
        if test_task_outcomes(second) != ['EXECUTED']:
            print(second, end='')
            raise RuntimeError(f'Expected flag-1 run after empty-flag run to execute, got {test_task_outcomes(second)}')

        # 3. Clean outputs and restore cached empty-flag outputs to prove build caching is active.
        restored = run_gradle(
            cache_dir, init_script, ':core:cleanTest', ':core:test', '--tests', TEST_CLASS, gate='', push=False,
        )
        if test_task_outcomes(restored) != ['FROM-CACHE']:
            print(restored, end='')
            raise RuntimeError(f'Expected empty-flag run to restore FROM-CACHE, got {test_task_outcomes(restored)}')

        # 4. Flag-1 after restoring cached empty-flag outputs must re-execute both in-place and after cleanTest.
        after_restore = run_gradle(cache_dir, init_script, ':core:test', '--tests', TEST_CLASS, gate='1', push=False)
        if test_task_outcomes(after_restore) != ['EXECUTED']:
            print(after_restore, end='')
            raise RuntimeError(
                f'Expected flag-1 run after FROM-CACHE restore to execute, got {test_task_outcomes(after_restore)}',
            )
        after_clean = run_gradle(
            cache_dir, init_script, ':core:cleanTest', ':core:test', '--tests', TEST_CLASS, gate='1', push=False,
        )
        if test_task_outcomes(after_clean) != ['EXECUTED']:
            print(after_clean, end='')
            raise RuntimeError(
                f'Expected clean flag-1 run against empty-flag cache to execute, got {test_task_outcomes(after_clean)}',
            )

        # Clean filtered test outputs before the full :core:test suite runs.
        run_gradle(cache_dir, init_script, ':core:cleanTest', gate='', push=False)
        print('CASSINI_PERF_GATE task input: incremental and FROM-CACHE invalidation PASS')


if __name__ == '__main__':
    main()
