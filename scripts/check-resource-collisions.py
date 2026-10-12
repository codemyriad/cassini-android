#!/usr/bin/env -S python3 -I
"""Fail when :app and :ui declare a resource with the same name.

The Android build merges the resources of :ui into :app. If both modules declare a resource with the same type and
name, the :app copy silently replaces the :ui copy. This script lists the resource names that each module declares
in src/main/res and fails if a name is in both. It compares names without the type too, so that `R.string.x` and
`R.color.x` in different modules also fail: one name for one thing.

Usage: python3 -I scripts/check-resource-collisions.py [REPO_ROOT]
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MODULES = ("app", "ui")

# Elements of a values file that declare a resource, mapped to the resource type.
VALUE_TYPES = {
    "array": "array",
    "attr": "attr",
    "bool": "bool",
    "color": "color",
    "declare-styleable": "styleable",
    "dimen": "dimen",
    "drawable": "drawable",
    "fraction": "fraction",
    "integer": "integer",
    "integer-array": "array",
    "plurals": "plurals",
    "string": "string",
    "string-array": "array",
    "style": "style",
}


def file_resource_name(path):
    """The resource name of a file resource: the file name without its extensions (a.9.png gives a)."""
    return path.name.split(".", 1)[0]


def value_resources(path):
    """Yields (type, name) for each resource that a values XML file declares."""
    root = ET.parse(path).getroot()
    for element in root:
        name = element.get("name")
        if not name:
            continue
        if element.tag == "item":
            kind = element.get("type")
            if kind:
                yield kind, name
            continue
        kind = VALUE_TYPES.get(element.tag)
        if kind is None:
            continue
        yield kind, name
        if element.tag == "declare-styleable":
            for attr in element.iter("attr"):
                # An attr with a format declares a new attribute; one without only refers to an existing one.
                if attr.get("format") or len(attr):
                    attr_name = attr.get("name")
                    if attr_name and not attr_name.startswith("android:"):
                        yield "attr", attr_name


def module_resources(res):
    """Maps each (type, name) declared under res to the files that declare it."""
    found = {}
    if not res.is_dir():
        return found
    for directory in sorted(p for p in res.iterdir() if p.is_dir()):
        kind = directory.name.split("-", 1)[0]
        for file in sorted(p for p in directory.iterdir() if p.is_file()):
            if kind == "values":
                if file.suffix != ".xml":
                    continue
                entries = list(value_resources(file))
            else:
                entries = [(kind, file_resource_name(file))]
            for entry in entries:
                found.setdefault(entry, []).append(file)
    return found


def main(argv):
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent
    resources = {module: module_resources(root / module / "src" / "main" / "res") for module in MODULES}
    for module in MODULES:
        if not resources[module]:
            print(f"{module}/src/main/res declares no resources", file=sys.stderr)
            return 2
    by_name = {module: {} for module in MODULES}
    for module in MODULES:
        for (kind, name), files in resources[module].items():
            by_name[module].setdefault(name, []).append((kind, files))
    collisions = sorted(set(by_name["app"]) & set(by_name["ui"]))
    for name in collisions:
        print(f"Resource name {name!r} is declared in both modules:", file=sys.stderr)
        for module in MODULES:
            for kind, files in by_name[module][name]:
                paths = ", ".join(str(f.relative_to(root)) for f in files)
                print(f"  {module}: {kind}/{name} in {paths}", file=sys.stderr)
    if collisions:
        print(f"{len(collisions)} resource name(s) collide between :app and :ui", file=sys.stderr)
        return 1
    counts = ", ".join(f":{module} {len(by_name[module])}" for module in MODULES)
    print(f"No resource name collisions ({counts} names)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
