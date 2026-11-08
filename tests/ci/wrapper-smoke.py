"""CI-only: the real wrapper must consume a cached ZIP offline and refuse a wrong checksum."""

import hashlib
import importlib.util
import os
import subprocess
import tempfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('downloads', ROOT / 'scripts/ci-downloads.py')
DOWNLOADS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DOWNLOADS)


def invoke(home):
    env = dict(os.environ, GRADLE_USER_HOME=str(home))
    # A fresh wrapper home and an unreachable proxy ensure an upstream download cannot hide a cache miss.
    env['GRADLE_OPTS'] = (env.get('GRADLE_OPTS', '') + ' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9'
                          ' -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9')
    return subprocess.run([str(ROOT / 'gradlew'), '--no-daemon', '--version'], cwd=ROOT, env=env,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False, timeout=180)


def main():
    info = DOWNLOADS.gradle_info()
    with tempfile.TemporaryDirectory(prefix='wrapper-smoke-', dir=os.environ.get('RUNNER_TEMP')) as tmp:
        home = Path(tmp) / 'good'
        with patch.dict(os.environ, GRADLE_USER_HOME=str(home)):
            DOWNLOADS.prepare_gradle()
        result = invoke(home)
        print(result.stdout, end='')
        if result.returncode or f'Gradle {info["version"]}' not in result.stdout:
            raise RuntimeError('Real wrapper did not start from the verified cache while offline')
        bad_home = Path(tmp) / 'bad'
        key = DOWNLOADS.base36(int(hashlib.md5(info['url'].encode(), usedforsecurity=False).hexdigest(), 16))
        bad_zip = bad_home / 'wrapper/dists' / info['name'] / key / (info['name'] + '.zip')
        bad_zip.parent.mkdir(parents=True)
        bad_zip.write_bytes(b'incorrect distribution bytes')
        rejected = invoke(bad_home)
        if rejected.returncode == 0 or 'Verification of Gradle distribution failed' not in rejected.stdout:
            print(rejected.stdout, end='')
            raise RuntimeError('Real wrapper did not enforce distributionSha256Sum')
        print('Real wrapper: offline verified-cache start and wrong-checksum refusal PASS')


if __name__ == '__main__':
    main()
