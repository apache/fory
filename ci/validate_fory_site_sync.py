#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import argparse
import pathlib
import re
import shutil
import subprocess
import sys
from typing import List, Tuple

TARGET_REPO = "apache/fory-site@main"


def parse_sync_mappings(sync_file: pathlib.Path) -> List[Tuple[str, str]]:
    mappings: List[Tuple[str, str]] = []
    source = None
    current_target = None
    for raw in sync_file.read_text(encoding="utf-8").splitlines():
        no_comment = raw.split("#", 1)[0].rstrip()
        if not no_comment.strip():
            continue

        top_level = re.match(r"^([^\s][^:]*):\s*$", no_comment)
        if top_level:
            current_target = top_level.group(1).strip()
            source = None
            continue

        if current_target != TARGET_REPO:
            continue

        stripped = no_comment.strip()
        source_match = re.match(r"^-\s*source:\s*(.+)$", stripped)
        if source_match:
            source = source_match.group(1).strip().strip("'\"")
            continue

        dest_match = re.match(r"^dest:\s*(.+)$", stripped)
        if dest_match and source:
            dest = dest_match.group(1).strip().strip("'\"")
            mappings.append((source, dest))
            source = None

    if not mappings:
        raise RuntimeError(f"no sync mappings found for {TARGET_REPO} in {sync_file}")
    return mappings


def to_workspace_path(root: pathlib.Path, relative_path: str) -> pathlib.Path:
    posix_path = pathlib.PurePosixPath(relative_path)
    if posix_path.is_absolute() or ".." in posix_path.parts:
        raise ValueError(f"invalid sync path: {relative_path}")
    return root.joinpath(*posix_path.parts)


def sync_files(
    fory_root: pathlib.Path, site_root: pathlib.Path, sync_file: pathlib.Path
) -> None:
    for source, dest in parse_sync_mappings(sync_file):
        src_path = to_workspace_path(fory_root, source)
        dst_path = to_workspace_path(site_root, dest)
        if not src_path.exists():
            raise FileNotFoundError(f"source path does not exist: {src_path}")
        if src_path.is_dir():
            if dst_path.exists():
                shutil.rmtree(dst_path)
            shutil.copytree(src_path, dst_path)
        else:
            dst_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src_path, dst_path)
        print(f"synced {source} -> {dest}")


def rewrite_versions_block(text: str) -> str:
    marker = "versions:"
    idx = text.find(marker)
    if idx == -1:
        return text

    brace_start = text.find("{", idx)
    if brace_start == -1:
        return text

    depth = 0
    end = -1
    i = brace_start
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i
                break
        i += 1

    if end == -1:
        return text

    j = end + 1
    while j < len(text) and text[j].isspace():
        j += 1
    if j < len(text) and text[j] == ",":
        j += 1

    replacement = (
        "versions: {\n"
        "            current: {\n"
        "              label: 'dev',\n"
        "            },\n"
        "          },"
    )
    return text[:idx] + replacement + text[j:]


def patch_docusaurus_config(path: pathlib.Path) -> None:
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"locales:\s*\[[^\]]*\]", "locales: ['en-US']", text, count=1)
    text = re.sub(r"lastVersion:\s*'[^']*'", "lastVersion: 'current'", text)
    text = rewrite_versions_block(text)
    path.write_text(text, encoding="utf-8")


def prune_for_fast_build(site_root: pathlib.Path) -> None:
    for directory in ("i18n", "versioned_docs", "versioned_sidebars"):
        shutil.rmtree(site_root / directory, ignore_errors=True)

    versions_json = site_root / "versions.json"
    if versions_json.exists():
        versions_json.write_text("[]\n", encoding="utf-8")

    patch_docusaurus_config(site_root / "docusaurus.config.ts")
    patch_docusaurus_config(site_root / "docusaurus.config.js")


def run_site_commands(site_root: pathlib.Path) -> None:
    for command in (
        ("npm", "install"),
        ("npm", "run", "lint", "--if-present"),
        ("npm", "run", "build"),
    ):
        subprocess.run(command, cwd=site_root, check=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Sync docs to fory-site and validate build."
    )
    parser.add_argument("fory_root", nargs="?", default="fory")
    parser.add_argument("fory_site_root", nargs="?", default="fory-site")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    fory_root = pathlib.Path(args.fory_root)
    site_root = pathlib.Path(args.fory_site_root)
    sync_file = fory_root / ".github" / "sync.yml"

    if not sync_file.is_file():
        raise FileNotFoundError(f"sync mapping file not found: {sync_file}")
    if not site_root.is_dir():
        raise FileNotFoundError(f"fory-site directory not found: {site_root}")

    sync_files(fory_root, site_root, sync_file)
    prune_for_fast_build(site_root)
    run_site_commands(site_root)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as exc:
        print(
            f"command failed with exit code {exc.returncode}: {' '.join(exc.cmd)}",
            file=sys.stderr,
        )
        sys.exit(exc.returncode)
