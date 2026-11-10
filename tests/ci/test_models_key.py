"""A verified model cache includes its install layout, not only its artifact digests."""

import copy
import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ci_models", ROOT / "scripts/fetch-ci-models.py")
MODELS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODELS)


class ModelCacheKeyTests(unittest.TestCase):
    def setUp(self):
        self.models = json.loads((ROOT / "scripts/ci-models.json").read_text())["models"]
        self.key = MODELS.cache_key(self.models)

    def test_install_identity_layout_and_marker_rotate_cache_without_new_artifacts(self):
        for field in ("id", "sourceSha256", "directory", "marker", "markerValue"):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.models)
                changed[0][field] += "-changed"
                self.assertNotEqual(self.key, MODELS.cache_key(changed))

    def test_raw_and_compressed_size_or_digest_rotate_cache(self):
        for packed in (False, True):
            for field in ("size", "sha256"):
                with self.subTest(packed=packed, field=field):
                    changed = copy.deepcopy(self.models)
                    artifact = changed[0]["artifacts"][0]
                    if packed:
                        artifact = artifact["download"]
                    artifact[field] += 1 if field == "size" else "-changed"
                    self.assertNotEqual(self.key, MODELS.cache_key(changed))

    def test_mapping_key_order_does_not_rotate_an_identical_cache(self):
        reordered = [dict(reversed(list(model.items()))) for model in self.models]
        self.assertEqual(self.key, MODELS.cache_key(reordered))
