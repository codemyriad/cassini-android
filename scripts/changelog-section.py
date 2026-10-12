#!/usr/bin/env python3
"""Print the CHANGELOG.md section of one version, as the notes of its GitHub release.

Usage: scripts/changelog-section.py [VERSION] [--link-base URL]

VERSION is a tag (v0.2.0), a version (0.2.0) or Unreleased (the default). The script prints the text under the
heading "## [VERSION]" up to the next "## " heading, without the heading and without the link definitions at the
end of the file. With --link-base, each relative Markdown link becomes URL + its path from the repository root, so
the links also work on the release page. The script fails when the section is missing or empty.
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADING = re.compile(r"^## \[(?P<name>[^\]]+)\]")
LINK_DEFINITION = re.compile(r"^\[[^\]]+\]: \S+$")
INLINE_LINK = re.compile(r"(?<!!)\[(?P<text>[^\]]+)\]\((?P<target>[^)\s]+)\)")


def section(text: str, name: str) -> str:
    lines = text.splitlines()
    start = None
    for index, line in enumerate(lines):
        match = HEADING.match(line)
        if match and match.group("name").lower() == name.lower():
            start = index + 1
            break
    if start is None:
        raise SystemExit(f"CHANGELOG.md has no section [{name}].")
    body = []
    for line in lines[start:]:
        if line.startswith("## ") or LINK_DEFINITION.match(line):
            break
        body.append(line)
    result = "\n".join(body).strip()
    if not result:
        raise SystemExit(f"The section [{name}] of CHANGELOG.md is empty.")
    return result + "\n"


def absolute_links(text: str, base: str) -> str:
    def replace(match: re.Match) -> str:
        target = match.group("target")
        if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I) or target.startswith("#"):
            return match.group(0)
        return f"[{match.group('text')}]({base.rstrip('/')}/{target.removeprefix('./')})"

    return INLINE_LINK.sub(replace, text)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("version", nargs="?", default="Unreleased")
    parser.add_argument("--link-base", help="the URL of the repository root at the tag, for relative links")
    parser.add_argument("--changelog", type=Path, default=ROOT / "CHANGELOG.md")
    arguments = parser.parse_args()
    name = arguments.version
    if re.fullmatch(r"v\d.*", name):
        name = name[1:]
    text = section(arguments.changelog.read_text(encoding="utf-8"), name)
    if arguments.link_base:
        text = absolute_links(text, arguments.link_base)
    sys.stdout.write(text)


if __name__ == "__main__":
    main()
