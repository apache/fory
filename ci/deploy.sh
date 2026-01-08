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


# Print commands and their arguments as they are executed.
if [ "${DEPLOY_QUIET:-0}" != "1" ]; then
    set -x
fi

# Cause the script to exit if a single command fails.
set -e

# Prefer Python from $PYTHON_PATH if it exists, otherwise use default python
if [ -n "$PYTHON_PATH" ] && [ -x "$PYTHON_PATH" ]; then
  PYTHON_CMD="$PYTHON_PATH"
  PIP_CMD="$PYTHON_PATH -m pip"
else
  PYTHON_CMD="python"
  PIP_CMD="pip"
fi

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

bump_version() {
  python "$ROOT/ci/release.py" bump_version -l all -version "$1"
}

bump_java_version() {
  python "$ROOT/ci/release.py" bump_version -l java -version "$1"
}

# Replicates the behavior of _update_python_version in ci/release.py
parse_py_version() {
  local version="$1"
  if [ -z "$version" ]; then
    # Get the latest tag from the current Git repository
    version=$(git describe --tags --abbrev=0)
  fi
  # Check if the tag starts with 'v' and strip it
  if [[ $version == v* ]]; then
    version="${version:1}"
  fi
  version="${version//-alpha/a}"
  version="${version//-beta/b}"
  version="${version//-rc/rc}"
  version="${version//-/}"
  echo "$version"
}

bump_py_version() {
  local version
  version=$(parse_py_version "$1")
  python "$ROOT/ci/release.py" bump_version -l python -version "$version"
}

bump_javascript_version() {
  python "$ROOT/ci/release.py" bump_version -l javascript -version "$1"
}

deploy_jars() {
  cd "$ROOT/java"
  mvn -T10 clean deploy --no-transfer-progress -DskipTests -Prelease
}

build_pyfory() {
  echo "$($PYTHON_CMD -V), path $(which "$PYTHON_CMD")"
  install_pyarrow
  $PIP_CMD install cython wheel pytest
  pushd "$ROOT/python"
  $PIP_CMD list
  echo "Install pyfory"
  # Fix strange installed deps not found
  $PIP_CMD install setuptools -U

  if [[ "$OSTYPE" == "darwin"* ]]; then
    if [ -n "${PYFORY_WHEEL_PLAT:-}" ]; then
      echo "PYFORY_WHEEL_PLAT: $PYFORY_WHEEL_PLAT"
      $PYTHON_CMD setup.py bdist_wheel --plat-name "$PYFORY_WHEEL_PLAT" --dist-dir="$ROOT/dist"
    else
      MACOS_VERSION=$(sw_vers -productVersion | cut -d. -f1-2)
      echo "MACOS_VERSION: $MACOS_VERSION"
      if [[ "$MACOS_VERSION" == "13"* ]]; then
        export MACOSX_DEPLOYMENT_TARGET=10.13
        $PYTHON_CMD setup.py bdist_wheel --plat-name macosx_10_13_x86_64 --dist-dir="$ROOT/dist"
      else
        $PYTHON_CMD setup.py bdist_wheel --dist-dir="$ROOT/dist"
      fi
    fi
  elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then

    # Windows tends to drop alpha/beta markers - force it through setup.cfg
    if [ -n "$GITHUB_REF_NAME" ]; then
      version=$(parse_py_version "$GITHUB_REF_NAME")
      echo "Using version from GITHUB_REF_NAME: $version"
      echo "[metadata]" > setup.cfg
      echo "version = $version" >> setup.cfg
    fi

    $PYTHON_CMD setup.py bdist_wheel --dist-dir="$ROOT/dist"
    # Clean up
    rm setup.cfg
  else
    $PYTHON_CMD setup.py bdist_wheel --dist-dir="$ROOT/dist"
  fi

  if [ -n "$PLAT" ]; then
    # In manylinux container, repair the wheel to embed shared libraries
    # and rename the wheel with the manylinux tag.
    PYARROW_LIB_DIR=$($PYTHON_CMD -c 'import pyarrow; print(":".join(pyarrow.get_library_dirs()))')
    export LD_LIBRARY_PATH="$PYARROW_LIB_DIR:$LD_LIBRARY_PATH"
    auditwheel repair "$ROOT/dist"/pyfory-*-linux_*.whl --plat "$PLAT" --exclude '*arrow*' --exclude '*parquet*' --exclude '*numpy*' -w "$ROOT/dist"
    rm "$ROOT/dist"/pyfory-*-linux_*.whl
  elif [[ "$OSTYPE" == "darwin"* ]]; then
    echo "Skip macos wheel repair"
  elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
    echo "Skip windows wheel repair"
  fi

  echo "Wheels for $PYTHON_CMD:"
  ls -l "$ROOT/dist"
  popd
}

install_pyarrow() {
  pyversion=$($PYTHON_CMD -V | cut -d' ' -f2)
  if [[ $pyversion  ==  3.13* ]]; then
    $PIP_CMD install pyarrow==18.0.0
    $PIP_CMD install numpy
  else
    $PIP_CMD install pyarrow==15.0.0
    # Automatically install numpy
  fi
}

deploy_scala() {
  echo "Start to build jars"
  sbt +publishSigned
  echo "Start to prepare upload"
  sbt sonatypePrepare
  echo "Start to upload jars"
  sbt sonatypeBundleUpload
  echo "Deploy scala jars succeed!"
}

case "$1" in
java) # Deploy jars to maven repository.
  deploy_jars
  ;;
*)
  echo "Execute command $*"
  "$@"
  ;;
esac
