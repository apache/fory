#!/usr/bin/env bash

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

# Usage:
#   ./build_manylinux_wheel.sh --os <matrix.os> --python <python-version> \
#     --arch <ARCH> [--workspace <path>] [--x86-image <image>] \
#     [--aarch64-image <image>] [--docker-image <image>] [--dry-run]
#
# Examples:
#   ./build_manylinux_wheel.sh --os ubuntu-latest --python 3.10 --arch X64
#   ./build_manylinux_wheel.sh --os ubuntu-24.04-arm --python 3.11 --arch ARM64 \
#     --aarch64-image quay.io/pypa/manylinux_2014_aarch64:latest
#
# Notes:
#   --arch accepts values: X86, X64, ARM, or ARM64 (case-insensitive).
#   This script requires --arch to be provided explicitly.
set -euo pipefail

print_usage() {
  cat <<EOF
Usage: $0 --os <matrix.os> --python <python-version> --arch <ARCH> [options]

Required:
  --os                matrix.os value (e.g. ubuntu-latest or ubuntu-24.04-arm)
  --python            Python version (e.g. 3.10)
  --arch              Architecture (X86, X64, ARM, or ARM64)

Optional:
  --workspace         Path to workspace to mount into container (default: cwd)
  --x86-image         manylinux x86_64 docker image (overrides default env)
  --aarch64-image     manylinux aarch64 docker image (overrides default env)
  --docker-image      Explicit docker image to use (skips auto selection)
  --dry-run           Print the docker command without executing it
  -h, --help          Show this help
EOF
}

# Defaults - can be overridden by options
WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
MANYLINUX_X86_64_IMAGE="${MANYLINUX_X86_64_IMAGE:-quay.io/pypa/manylinux_2_28_x86_64:latest}"
MANYLINUX_AARCH64_IMAGE="${MANYLINUX_AARCH64_IMAGE:-quay.io/pypa/manylinux_2_28_aarch64:latest}"
DOCKER_IMAGE=""
ARCH=""
DRY_RUN=0

# Parse args
while [[ $# -gt 0 ]]; do
  case "$1" in
    --os) MATRIX_OS="$2"; shift 2;;
    --python) PY_VERSION_RAW="$2"; shift 2;;
    --workspace) WORKSPACE="$2"; shift 2;;
    --x86-image) MANYLINUX_X86_64_IMAGE="$2"; shift 2;;
    --aarch64-image) MANYLINUX_AARCH64_IMAGE="$2"; shift 2;;
    --docker-image) DOCKER_IMAGE="$2"; shift 2;;
    --arch) ARCH="$2"; shift 2;;
    --dry-run) DRY_RUN=1; shift;;
    -h|--help) print_usage; exit 0;;
    *) echo "Unknown argument: $1"; print_usage; exit 2;;
  esac
done

if [[ -z "${MATRIX_OS:-}" ]] || [[ -z "${PY_VERSION_RAW:-}" ]] || [[ -z "${ARCH:-}" ]]; then
  echo "Error: --os, --python and --arch are required."
  print_usage
  exit 2
fi

# Normalize ARCH to uppercase
ARCH="${ARCH^^}"

# Normalize Python version: remove dots (e.g. 3.10 -> 310)
PY_VERSION_NO_DOTS="${PY_VERSION_RAW//./}"

# Determine DOCKER_IMAGE and PLAT strictly from ARCH (unless --docker-image supplied)
PLAT=""
case "$ARCH" in
  X86|X64)
    PLAT="manylinux_2_28_x86_64"
    DOCKER_IMAGE="${DOCKER_IMAGE:-$MANYLINUX_X86_64_IMAGE}"
    ;;
  ARM|ARM64)
    PLAT="manylinux_2_28_aarch64"
    DOCKER_IMAGE="${DOCKER_IMAGE:-$MANYLINUX_AARCH64_IMAGE}"
    ;;
  *)
    echo "Error: Unsupported ARCH '$ARCH'. Use one of: X86, X64, ARM, ARM64."
    exit 2
    ;;
esac

echo "Matrix OS: $MATRIX_OS"
echo "Arch (input): $ARCH"
echo "Selected docker image: $DOCKER_IMAGE"
echo "Platform (PLAT): $PLAT"
echo "Python version (raw): $PY_VERSION_RAW"
echo "PY_VERSION without dots: $PY_VERSION_NO_DOTS"
echo "Workspace: $WORKSPACE"

# Basic checks
if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker is required but not installed or not on PATH."
  exit 3
fi

SCRIPT='set -e
yum install -y git sudo wget || true
git config --global --add safe.directory /work
ls -alh /opt/python || true
echo "PY_VERSION: $PY_VERSION"
ls /opt/python/cp${PY_VERSION}-cp${PY_VERSION} || true
ls /opt/python/cp${PY_VERSION}-cp${PY_VERSION}/bin || true
export PATH=/opt/python/cp${PY_VERSION}-cp${PY_VERSION}/bin:$PATH
echo "PATH: $PATH"
echo "Using Python from: $(which python || echo not-found)"
echo "Python version: $(python -V 2>&1 || true)"
bash ci/run_ci.sh install_bazel
bash ci/deploy.sh build_pyfory'

DOCKER_CMD=(docker run --rm
  -e "PY_VERSION=$PY_VERSION_NO_DOTS"
  -e "PLAT=$PLAT"
  -v "$WORKSPACE":/work
  -w /work
  "$DOCKER_IMAGE"
  bash -lc "$SCRIPT"
)

# Show the final command (joined) for clarity
echo
echo "Docker command to be executed:"
printf ' %q' "${DOCKER_CMD[@]}"
echo
echo

if [[ $DRY_RUN -eq 1 ]]; then
  echo "Dry run enabled; not executing docker command."
  exit 0
fi

# Execute
"${DOCKER_CMD[@]}"
