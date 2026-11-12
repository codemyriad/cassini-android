"""Android W0.8: immutable vendor, explicit paired inventory and read-only git diff."""

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
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
        self.assertIn(b"cassini-ux@afe3ba905880e7052dbcef425d204266153a2ae0:", lines[1])
        canonical = lines[0] + b"".join(lines[2:])
        self.assertEqual(
            "5d22dbac2cdf0439d6a8a6be9885f27140bf088a0e17574ed3b55667711550da", hashlib.sha256(canonical).hexdigest()
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
