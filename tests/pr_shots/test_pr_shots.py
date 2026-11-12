"""Android W0.8: immutable vendor, explicit paired inventory and read-only git diff."""

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image, PngImagePlugin

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/pr-shots.py"
GOLDENS = ROOT / "ui/src/test/screenshots"
SPEC = importlib.util.spec_from_file_location("pr_shots", SCRIPT)
shots = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(shots)


def validate_manifest(rows, expected_files=None):
    paths = {r["path"] for r in rows}
    expected_files = (
        expected_files
        if expected_files is not None
        else {p.relative_to(GOLDENS).as_posix() for p in GOLDENS.rglob("*.png")}
    )
    assert len(paths) == len(rows) and paths == expected_files
    groups = {}
    for row in rows:
        path = row["path"]
        dark = path.startswith("dark/") or path.split("/")[0].endswith("-dark") or path.endswith("-dark.png")
        assert row["scheme"] == ("dark" if dark else "light"), path
        groups.setdefault(shots.screen_id(row), []).append(row)
        fixed = path in {f"{d}/01-recording.png" for d in ("light", "dark", "fr", "wide")} or path.startswith(
            "variants/recording-"
        )
        rendered = "dark" if fixed else row["scheme"]
        assert row.get("rendered_scheme", row["scheme"]) == rendered, path
        with Image.open(GOLDENS / path) as image:
            red, green, blue, alpha = image.convert("RGBA").getpixel((0, 0))
        assert alpha == 255 and ((red + green + blue) / 3 < 128) == (rendered == "dark"), path
    for group in groups.values():
        modes = {r["scheme"] for r in group}
        if len(group) == 1:
            assert (
                modes == {"light"}
                and group[0].get("single_scheme_reason") == "Legacy light-only coverage (ANDROID-SCR-03)"
            )
        else:
            assert len(group) == 2 and modes == {"light", "dark"}
            assert all("single_scheme_reason" not in r for r in group)


class ScreenshotContract(unittest.TestCase):
    def test_vendor_is_exact_pinned_source_apart_from_header(self):
        lines = SCRIPT.read_bytes().splitlines(keepends=True)
        self.assertIn(b"cassini-ux@62c43f579941606948ac4175d5d1013af37d6163:", lines[1])
        canonical = lines[0] + b"".join(lines[2:])
        self.assertEqual(
            "d4898061024ba464e88c66dc12563d1a61aaa5d6675ccc22e3af62aa2314c994", hashlib.sha256(canonical).hexdigest()
        )

    def test_manifest_pairs_and_rendered_scheme(self):
        rows = json.loads((GOLDENS / "shots.json").read_text())["shots"]
        validate_manifest(rows)
        # Correct paths/labels cannot hide a wrongly rendered dark capture.
        with patch.object(Image, "open", side_effect=lambda *_args, **_kwargs: Image.new("RGBA", (1, 1), "white")):
            with self.assertRaises(AssertionError):
                validate_manifest(rows)
        with self.assertRaises(AssertionError):
            validate_manifest([r for r in rows if r["path"] != "dark/00-library.png"])
        remaining = [r for r in rows if r["path"] != "dark/00-library.png"]
        with self.assertRaises(AssertionError):
            validate_manifest(remaining, {r["path"] for r in remaining})
        altered = [dict(r) for r in rows]
        next(r for r in altered if r["path"] == "dark/00-library.png")["scheme"] = "light"
        with self.assertRaises(AssertionError):
            validate_manifest(altered)

    def test_git_diff_keeps_both_modes_ignores_png_metadata_and_reports_ui_gap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            def git(*argv):
                return subprocess.check_output(["git", *argv], cwd=root, stderr=subprocess.DEVNULL).decode().strip()

            def commit():
                git("add", "--all")
                git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.test", "commit", "-qm", "fixture")
                return git("rev-parse", "HEAD")

            def compare(base, head, folder):
                subprocess.run(
                    [
                        sys.executable,
                        "-I",
                        str(SCRIPT),
                        "diff",
                        "--layout",
                        "android",
                        "--repo",
                        "owner/app",
                        "--pr",
                        "1",
                        "--git",
                        base,
                        head,
                        "--out",
                        folder,
                    ],
                    cwd=root,
                    timeout=30,
                    check=True,
                    capture_output=True,
                )
                return json.loads((root / folder / "changes.json").read_text())

            git("init", "-q")
            for path in ("light/00-library.png", "dark/00-library.png", "light/08-settings.png"):
                target = root / "ui/src/test/screenshots" / path
                target.parent.mkdir(parents=True, exist_ok=True)
                Image.new("RGBA", (2, 1), "white").save(target)
            base = commit()
            image = Image.new("RGBA", (2, 1), "white")
            image.putpixel((0, 0), (0, 0, 0, 255))
            image.save(root / "ui/src/test/screenshots/light/00-library.png")
            metadata = PngImagePlugin.PngInfo()
            metadata.add_text("encoder", "Different bytes, same pixels")
            Image.new("RGBA", (2, 1), "white").save(
                root / "ui/src/test/screenshots/light/08-settings.png", pnginfo=metadata
            )
            head = commit()
            result = compare(base, head, "out")
            self.assertEqual({"changed", "unchanged"}, {r["status"] for r in result["rows"]})
            for row in result["rows"]:
                self.assertIsNone(row["after"])
                if row["screen"] == "library":
                    self.assertIn("after_git_path", row)
            self.assertEqual([], list((root / "out").rglob("*.png")))
            ui = root / "app/src/main/Changed.kt"
            ui.parent.mkdir(parents=True)
            ui.write_text("// fixture")
            next_head = commit()
            result = compare(head, next_head, "next-out")
            self.assertEqual({"unchanged"}, {r["status"] for r in result["rows"]})
            self.assertIn("UI code changed, no golden changed.", (root / "next-out/block.md").read_text())


class AndroidPublication(unittest.TestCase):
    def setUp(self):
        inventory = json.loads((GOLDENS / "shots.json").read_text())["shots"]
        self.data = {
            "version": 1, "app": "android", "repo": "owner/app", "pr": 199,
            "git_images": True, "root": "ui/src/test/screenshots",
            "base": {"sha": "a" * 40, "run_id": "committed", "runner": "linux", "image": "ubuntu"},
            "head": {"sha": "b" * 40, "run_id": "123", "runner": "linux", "image": "ubuntu"},
            "rows": [dict(row, status="changed", changed_pct=10, bbox=None,
                          before=None, after=None, diff=None,
                          before_git_path=f"ui/src/test/screenshots/{row['path']}",
                          after_git_path=f"ui/src/test/screenshots/{row['path']}") for row in inventory],
        }

    def dry_run(self, data, prose):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "changes.json").write_text(json.dumps(data))
            args = SimpleNamespace(repo=data["repo"], pr=data["pr"], dir=root, dry_run=True)
            pr = {"head": {"sha": data["head"]["sha"]}, "body": prose}
            output = io.StringIO()
            with patch.object(shots, "github", return_value=pr), \
                    patch.object(shots, "push_host") as push, \
                    patch.object(shots, "command") as command, contextlib.redirect_stdout(output):
                shots.publish(args)
                push.assert_not_called()
                command.assert_not_called()
            return output.getvalue()

    def test_full_real_inventory_keeps_distinct_english_core_screens_inline(self):
        self.assertEqual(181, len({shots.screen_id(r) for r in self.data["rows"]}))
        rendered = shots.block(self.data)
        self.assertLess(len(rendered.encode("utf-8")), shots.MAX_BLOCK_CHARACTERS)
        self.assertNotIn("<details>", rendered)
        self.assertIn("/tree/" + "b" * 40 + "/ui/src/test/screenshots", rendered)
        self.assertIn("first 12 of 181", rendered)
        table = [line for line in rendered.splitlines() if line.startswith("| ")][1:]
        # Check the rendered image paths, not just the sorting helper.
        english_paths = {r["path"] for r in self.data["rows"] if r["lang"] == "en" and r["device"] == "phone"}
        for line in table:
            paths = re.findall(r'/ui/src/test/screenshots/([^"<>]+)', line)
            self.assertEqual(2, len(paths))
            self.assertTrue(set(paths) <= english_paths, line)
        labels = {line.split(" · ")[0].removeprefix("| ") for line in table}
        self.assertEqual(12, len(labels))
        for label in ("Library", "Recording", "Read and listen", "Transcribing", "Name a speaker",
                      "Note actions and export", "Settings", "Saved voices", "Trash"):
            self.assertIn(label, labels)
        full = shots.render_block(self.data, True, False, None)
        self.assertIn("Library · light (fr)", full)
        tablet = copy.deepcopy(self.data)
        for row in tablet["rows"]:
            if row["screen"] == "library" and row["lang"] == "en":
                row["device"] = "tablet"
        qualified = shots.render_block(tablet, True, False, None)
        self.assertIn("Library · light (tablet)", qualified)
        dry = self.dry_run(self.data, "Authored description.\n" * 1500)
        self.assertEqual(rendered, dry.rstrip("\n") + "\n")

    def test_added_real_screen_precedes_secondary_and_localized_screens(self):
        for row in self.data["rows"]:
            if row["screen"] == "model-download" and row["lang"] == "en" and row["device"] == "phone":
                row.update(status="added", before_git_path=None)
        ordered = list(dict.fromkeys(shots.screen_id(r) for r in shots.sorted_rows(self.data["rows"])))
        for secondary in ("transcribing", "settings"):
            self.assertLess(ordered.index(("model-download", "en", "phone")),
                            ordered.index((secondary, "en", "phone")))

    def test_long_multibyte_prose_compacts_even_when_block_alone_fits(self):
        selected = list(dict.fromkeys(shots.screen_id(r) for r in shots.sorted_rows(self.data["rows"])))[:20]
        data = copy.deepcopy(self.data)
        data["rows"] = [r for r in data["rows"] if shots.screen_id(r) in selected]
        full = shots.block(data)
        self.assertIn("<details>", full)
        self.assertLess(len(full.encode("utf-8")), shots.MAX_BLOCK_CHARACTERS)
        compact = shots.render_block(data, True, False, None, omit_details=True)
        prose = "é" * ((shots.MAX_PR_BODY_BYTES - len(compact.encode("utf-8")) - 102) // 2)
        self.assertGreater(len(shots.replace_block(prose, full).encode("utf-8")), shots.MAX_PR_BODY_BYTES)
        rendered = self.dry_run(data, prose)
        self.assertNotIn("<details>", rendered)
        self.assertLessEqual(len(shots.replace_block(prose, rendered).encode("utf-8")), shots.MAX_PR_BODY_BYTES)
        # Dry-run must fail just like publishing when even the compact block cannot fit.
        with self.assertRaisesRegex(ValueError, "PR body exceeds GitHub limit"):
            self.dry_run(data, "é" * 32_500)

    def test_block_limit_measures_utf8_bytes(self):
        data = copy.deepcopy(self.data)
        selected = list(dict.fromkeys(shots.screen_id(r) for r in shots.sorted_rows(data["rows"])))[:40]
        data["rows"] = [r for r in data["rows"] if shots.screen_id(r) in selected]
        for row in data["rows"]:
            row["label"] = "界" * 120
        full = shots.render_block(data, True, False, None)
        self.assertLess(len(full), shots.MAX_BLOCK_CHARACTERS)
        self.assertGreater(len(full.encode("utf-8")), shots.MAX_BLOCK_CHARACTERS)
        compact = shots.block(data)
        self.assertNotIn("<details>", compact)
        self.assertLess(len(compact.encode("utf-8")), shots.MAX_BLOCK_CHARACTERS)

    def test_oversized_body_fails_before_host_checkout_or_push(self):
        data = copy.deepcopy(self.data)
        data["git_images"] = False
        row = data["rows"][0]
        data["rows"] = [row]
        row.pop("before_git_path")
        row.pop("after_git_path")
        for side in ("before", "after"):
            row[side] = f"{side}/{shots.key(row)}.png"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for side in ("before", "after"):
                path = root / row[side]
                path.parent.mkdir()
                Image.new("RGBA", (1, 1), "white").save(path)
            (root / "changes.json").write_text(json.dumps(data))
            pr = {"head": {"sha": data["head"]["sha"]}, "body": "é" * 32_500}
            args = SimpleNamespace(repo=data["repo"], pr=data["pr"], dir=root, dry_run=False)
            with patch.object(shots, "github", return_value=pr), \
                    patch.object(shots, "checkout_host") as checkout, \
                    patch.object(shots, "push_host") as push, patch.object(shots, "command") as command:
                with self.assertRaisesRegex(ValueError, "PR body exceeds GitHub limit"):
                    shots.publish(args)
                checkout.assert_not_called()
                push.assert_not_called()
                command.assert_not_called()
