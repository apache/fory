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

ci/run_ci.sh install_bazel
export PATH="$HOME/.local/bin:$PATH"

# Function to verify the installed version against expected version
verify_version() {
    local installed_version=$1

    echo "Installed version: $installed_version"

    # Check if GITHUB_REF_NAME is available and use it for verification
    if [ -n "$GITHUB_REF_NAME" ]; then
        # Strip leading 'v' if present
        local expected_version
        expected_version="$(ci/deploy.sh parse_py_version $GITHUB_REF_NAME)"
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
    export PYTHON_PATH="/opt/python/$PY/bin/python"
    export PATH="/opt/python/$PY/bin:$OLD_PATH"
    echo "Using $PYTHON_PATH"
    ARCH=$(uname -m)
    if [ "$ARCH" = "aarch64" ]; then
        export PLAT="manylinux2014_aarch64"
    else
        export PLAT="manylinux2014_x86_64"
    fi
    python -m pip install cython wheel pytest auditwheel
    ci/deploy.sh build_pyfory

    latest_wheel=$(find dist -maxdepth 1 -type f -name '*.whl' -print0 | xargs -0 ls -t | head -n1)
    if [ -z "$latest_wheel" ]; then
      echo "No wheel found" >&2
      exit 1
    fi

    echo "Attempting to install $latest_wheel"
    python -m pip install "$latest_wheel"

    # Verify the installed version matches the expected version
    INSTALLED_VERSION=$(python -c "import pyfory; print(pyfory.__version__)")

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
