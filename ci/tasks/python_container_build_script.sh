#!/usr/bin/bash
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

set -e
yum install -y git sudo wget || true

git config --global --add safe.directory /work

if ! command -v bazel >/dev/null 2>&1; then
    echo "bazel is required in container PATH"
    exit 1
fi
echo "Using bazel: $(bazel --version)"

# Function to verify the installed version against expected version
verify_version() {
    local installed_version=$1

    echo "Installed version: $installed_version"

    # Check if GITHUB_REF_NAME is available and use it for verification
    if [ -n "$GITHUB_REF_NAME" ]; then
        # Strip leading 'v' if present and capture only the actual output, not debug messages
        local expected_version
        expected_version="$(DEPLOY_QUIET=1 ci/deploy.sh parse_py_version $GITHUB_REF_NAME | tail -n1)"
        echo "Expected version: $expected_version"

        if [ "$installed_version" != "$expected_version" ]; then
            echo "Version mismatch: Expected $expected_version but got $installed_version"
            exit 1
        fi
        echo "Version verification successful"
    else
        echo "GITHUB_REF_NAME not available, skipping version verification"
    fi
}

# use the python interpreters preinstalled in manylinux
OLD_PATH=$PATH
for PY in $PYTHON_VERSIONS; do
    PYTHON_BIN_DIR="/opt/python/$PY/bin"
    if [ -x "$PYTHON_BIN_DIR/python" ]; then
        export PYTHON_PATH="$PYTHON_BIN_DIR/python"
    elif [ -x "$PYTHON_BIN_DIR/python3" ]; then
        export PYTHON_PATH="$PYTHON_BIN_DIR/python3"
    else
        echo "No Python executable found under $PYTHON_BIN_DIR" >&2
        ls -l "$PYTHON_BIN_DIR" >&2 || true
        exit 1
    fi
    export PATH="$PYTHON_BIN_DIR:$OLD_PATH"
    hash -r
    echo "Using $PYTHON_PATH"
    if [ -n "${MANYLINUX_PLAT:-}" ]; then
        export PLAT="$MANYLINUX_PLAT"
    else
        ARCH=$(uname -m)
        if [ "$ARCH" = "aarch64" ]; then
            export PLAT="manylinux2014_aarch64"
        else
            export PLAT="manylinux2014_x86_64"
        fi
    fi
    "$PYTHON_PATH" -m pip install cython wheel pytest auditwheel
    ci/deploy.sh build_pyfory

    latest_wheel=$(find dist -maxdepth 1 -type f -name '*.whl' -print0 | xargs -0 ls -t | head -n1)
    if [ -z "$latest_wheel" ]; then
      echo "No wheel found" >&2
      exit 1
    fi

    echo "Attempting to install $latest_wheel"
    "$PYTHON_PATH" -m pip install "$latest_wheel"

    # Verify the installed version matches the expected version
    INSTALLED_VERSION=$("$PYTHON_PATH" -c "import pyfory; print(pyfory.__version__)")

    # Only run version verification for release builds
    if [ "${RELEASE_BUILD:-0}" = "1" ]; then
        echo "Running version verification for release build"
        verify_version "$INSTALLED_VERSION"
    else
        echo "Skipping version verification for test build"
    fi

    bazel clean --expunge
done
export PATH=$OLD_PATH
