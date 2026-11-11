"""A changed generated label must still match its checksum-pinned shared catalog."""
import subprocess
import sys
import unittest
from pathlib import Path


class SharedStringsTest(unittest.TestCase):
    def test_d96_resources_match_the_published_catalog_in_every_locale(self):
        root = Path(__file__).resolve().parents[2]
        subprocess.run([sys.executable, '-I', 'scripts/generate-d96-strings.py', '--check'], cwd=root, check=True)
