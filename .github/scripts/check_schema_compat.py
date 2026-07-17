#!/usr/bin/env python3
"""Backward-compatibility gate for event schema contracts (contracts/events).

Compares each *.schema.json against a base git ref (the PR merge-base) and
fails on changes that could break existing consumers:

  BREAKING:
    - deleting a schema file
    - removing a property from "properties"
    - changing a property's "type", "const", "pattern", "enum" or "format"
    - adding a property to "required"
    - changing the top-level "type"

  ALLOWED:
    - adding a new schema file (new event/topic)
    - adding a new OPTIONAL property (not in "required")
    - removing an entry from "required" (loosening)
    - description/title/doc-only changes

Anything beyond these rules (e.g. restructuring away from a flat object)
requires a new versioned topic per CONVENTIONS §6.4 — and will be flagged
here as breaking, which is the point.

Usage: check_schema_compat.py <base-ref>   (e.g. origin/main or a merge-base SHA)
"""
import json
import subprocess
import sys
from pathlib import Path

SCHEMA_DIR = Path("contracts/events")
CHECKED_KEYS = ("type", "const", "pattern", "enum", "format")


def load_base(ref: str, path: Path):
    proc = subprocess.run(
        ["git", "show", f"{ref}:{path.as_posix()}"],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        return None  # file did not exist at base -> new schema, allowed
    return json.loads(proc.stdout)


def compare(old: dict, new: dict, name: str) -> list[str]:
    errors = []
    if old.get("type") != new.get("type"):
        errors.append(f"{name}: top-level type changed {old.get('type')!r} -> {new.get('type')!r}")

    old_props = old.get("properties", {})
    new_props = new.get("properties", {})

    for prop, old_def in old_props.items():
        if prop not in new_props:
            errors.append(f"{name}: property '{prop}' was REMOVED")
            continue
        new_def = new_props[prop]
        for key in CHECKED_KEYS:
            if key in old_def and old_def.get(key) != new_def.get(key):
                errors.append(
                    f"{name}: property '{prop}' changed '{key}': "
                    f"{old_def.get(key)!r} -> {new_def.get(key)!r}"
                )

    added_required = set(new.get("required", [])) - set(old.get("required", []))
    if added_required:
        errors.append(f"{name}: added to required: {sorted(added_required)} "
                      "(existing producers may not send these)")

    for prop in new.get("required", []):
        if prop not in new_props:
            errors.append(f"{name}: required property '{prop}' missing from properties")

    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    base_ref = sys.argv[1]

    schemas = sorted(SCHEMA_DIR.glob("*.schema.json"))
    if not schemas:
        print(f"No schemas found under {SCHEMA_DIR} — refusing to pass an empty gate")
        return 1

    # Deleted schema files are breaking too.
    proc = subprocess.run(
        ["git", "ls-tree", "-r", "--name-only", base_ref, SCHEMA_DIR.as_posix()],
        capture_output=True, text=True,
    )
    base_files = set(proc.stdout.split()) if proc.returncode == 0 else set()
    current_files = {s.as_posix() for s in schemas}
    all_errors = [f"schema file DELETED: {f}" for f in sorted(base_files - current_files)
                  if f.endswith(".schema.json")]

    for schema_path in schemas:
        new = json.loads(schema_path.read_text())
        old = load_base(base_ref, schema_path)
        if old is None:
            print(f"NEW    {schema_path.name} (no base version — allowed)")
            continue
        errors = compare(old, new, schema_path.name)
        if errors:
            all_errors.extend(errors)
            print(f"BREAK  {schema_path.name}")
        else:
            print(f"OK     {schema_path.name}")

    if all_errors:
        print("\nBackward-incompatible schema changes detected:")
        for e in all_errors:
            print(f"  - {e}")
        print("\nEvolve compatibly (add optional fields) or introduce a new "
              "versioned topic (CONVENTIONS §6.4).")
        return 1
    print("\nAll event schemas are backward compatible with the base ref.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
