#!/usr/bin/env python

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

"""
Host-side wrapper for building Python wheels in manylinux containers.

Usage:
  ./build_linux_wheels.py --arch X86 --python cp38-cp38
  ./build_linux_wheels.py --arch AARCH64 --python cp313-cp313 --release

Environment:
  - GITHUB_WORKSPACE (optional; defaults to cwd)
"""

from __future__ import annotations

import argparse
import os
import shlex
import shutil
import subprocess
import sys
from typing import List

# Path to the container build script
CONTAINER_SCRIPT_PATH = "ci/tasks/python_container_build_script.sh"

DEFAULT_X86_IMAGES = [
    "quay.io/pypa/manylinux2014_x86_64:latest",
]

DEFAULT_AARCH64_IMAGES = [
    "quay.io/pypa/manylinux2014_aarch64:latest",
]

ARCH_ALIASES = {
    "X86": "x86",
    "X64": "x86",
    "X86_64": "x86",
    "AMD64": "x86",
    "ARM": "arm64",
    "ARM64": "arm64",
    "AARCH64": "arm64",
}


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument(
        "--arch", required=True, help="Architecture (e.g. X86, X64, AARCH64)"
    )
    p.add_argument(
        "--python", required=True, help="Python version (e.g. cp38-cp38, cp313-cp313)"
    )
    p.add_argument(
        "--bazel-bin",
        default="",
        help="Path to host bazel executable to mount into the container",
    )
    p.add_argument("--release", action="store_true", help="Run in release mode")
    p.add_argument(
        "--dry-run", action="store_true", help="Print docker command without running"
    )
    return p.parse_args()


def normalize_arch(raw: str) -> str:
    key = raw.strip().upper()
    return ARCH_ALIASES.get(key, raw.strip().lower())


def get_image_for_arch(arch_normalized: str) -> str:
    if arch_normalized == "x86":
        return DEFAULT_X86_IMAGES[0]
    elif arch_normalized == "arm64":
        return DEFAULT_AARCH64_IMAGES[0]
    else:
        raise SystemExit(f"Unsupported arch: {arch_normalized!r}")


def build_docker_cmd(
    workspace: str,
    image: str,
    python_version: str,
    bazel_bin: str,
    release: bool = False,
) -> List[str]:
    workspace = os.path.abspath(workspace)
    bazel_bin = os.path.abspath(bazel_bin)
    github_ref_name = os.environ.get("GITHUB_REF_NAME", "")

    cmd = [
        "docker",
        "run",
        "-i",
        "--rm",
        "-v",
        f"{workspace}:/work",
        "-w",
        "/work",
        "-e",
        f"PYTHON_VERSIONS={python_version}",
        "-e",
        f"RELEASE_BUILD={'1' if release else '0'}",
        "-v",
        f"{bazel_bin}:/usr/local/bin/bazel:ro",
    ]

    if github_ref_name:
        cmd.extend(["-e", f"GITHUB_REF_NAME={github_ref_name}"])

    cmd.extend([image, "bash", CONTAINER_SCRIPT_PATH])
    return cmd


def main() -> int:
    args = parse_args()
    arch = normalize_arch(args.arch)
    image = get_image_for_arch(arch)
    workspace = os.environ.get("GITHUB_WORKSPACE", os.getcwd())
    bazel_bin = args.bazel_bin or shutil.which("bazel")

    script_path = os.path.join(workspace, CONTAINER_SCRIPT_PATH)
    if not os.path.exists(script_path):
        print(f"Container script not found at {script_path}", file=sys.stderr)
        return 2
    if not bazel_bin:
        print(
            "Unable to locate bazel on host. Please provide --bazel-bin.",
            file=sys.stderr,
        )
        return 2
    if not os.path.exists(bazel_bin):
        print(f"Host bazel binary does not exist: {bazel_bin}", file=sys.stderr)
        return 2

    docker_cmd = build_docker_cmd(
        workspace, image, args.python, bazel_bin, release=args.release
    )
    printable = " ".join(shlex.quote(c) for c in docker_cmd)
    print(f"+ {printable}")

    if args.dry_run:
        return 0

    try:
        completed = subprocess.run(docker_cmd)
        if completed.returncode != 0:
            print(f"Container exited with {completed.returncode}", file=sys.stderr)
        return completed.returncode
    except FileNotFoundError as e:
        print(f"Error running docker: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
