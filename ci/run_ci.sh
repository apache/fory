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

# NOTE: This script is being gradually migrated to Python (run_ci.py).
# It can be called directly or from run_ci.py as a fallback.
# To control which languages use the Python implementation, set environment variables:
#   USE_PYTHON_CPP=0        # Use shell script for C++
#   USE_PYTHON_RUST=0       # Use shell script for Rust
#   USE_PYTHON_JAVASCRIPT=0 # Use shell script for JavaScript
#   USE_PYTHON_JAVA=0       # Use shell script for Java
#   USE_PYTHON_KOTLIN=0     # Use shell script for Kotlin
#   USE_PYTHON_PYTHON=0     # Use shell script for Python
#   USE_PYTHON_GO=0         # Use shell script for Go
#   USE_PYTHON_FORMAT=0     # Use shell script for Format
#
# By default, JavaScript, Rust, and C++ use the Python implementation,
# while Java, Kotlin, Python, Go, and Format use the shell script implementation.

set -e
set -x

ROOT="$(git rev-parse --show-toplevel)"
echo "Root path: $ROOT, home path: $HOME"
cd "$ROOT"

export FORY_CI=true

install_python() {
  wget -q https://repo.anaconda.com/miniconda/Miniconda3-py38_23.5.2-0-Linux-x86_64.sh -O Miniconda3.sh
  bash Miniconda3.sh -b -p $HOME/miniconda && rm -f miniconda.*
  echo "$(python -V), path $(which python)"
}

install_pyfory() {
  echo "$(python -V), path $(which python)"
  "$ROOT"/ci/deploy.sh install_pyarrow
  pip install Cython wheel pytest
  pushd "$ROOT/python"
  pip list
  echo "Install pyfory"
  # Fix strange installed deps not found
  pip install setuptools -U
  pip install -v -e .
  popd
}

get_bazel_version() {
    cat "$ROOT/.bazelversion"
}

install_bazel() {
  REQUIRED_VERSION=$(get_bazel_version)

  if command -v bazel >/dev/null; then
    echo "existing bazel location $(which bazel)"
    INSTALLED_VERSION=$(bazel --version 2>/dev/null | sed 's/bazel //')
    echo "existing bazel version: $INSTALLED_VERSION, required: $REQUIRED_VERSION"
    if [ "$INSTALLED_VERSION" = "$REQUIRED_VERSION" ]; then
      echo "Bazel version matches, using cached binary"
      return
    else
      echo "Bazel version mismatch, re-downloading..."
      # Remove old bazel binary
      rm -f "$(which bazel)" 2>/dev/null || true
    fi
  fi

  ARCH="$(uname -m)"
  OPERATING_SYSTEM="$(uname -s)"

  # Normalize architecture names
  case "${ARCH}" in
    x86_64|amd64)  ARCH="x86_64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    *)             echo "Unsupported architecture: $ARCH"; exit 1 ;;
  esac

  # Handle OS-specific logic. Windows handled elsewhere
  case "${OPERATING_SYSTEM}" in
    Linux*)     OS="linux" ;;
    Darwin*)    OS="darwin" ;;
    *)          echo "Unsupported OS: $OPERATING_SYSTEM"; exit 1 ;;
  esac

  BAZEL_DIR="$HOME/.local/bin"
  mkdir -p "$BAZEL_DIR"

  # Construct platform-specific URL (use REQUIRED_VERSION defined at function start)
  BINARY_URL="https://github.com/bazelbuild/bazel/releases/download/${REQUIRED_VERSION}/bazel-${REQUIRED_VERSION}-${OS}-${ARCH}"

  echo "Downloading bazel from: $BINARY_URL"
  # Retry download with exponential backoff to avoid transient network errors in CI
  MAX_ATTEMPTS=5
  SLEEP_SECONDS=2
  attempt=1
  while [ $attempt -le $MAX_ATTEMPTS ]; do
    if curl -L -sSf -o "$BAZEL_DIR/bazel" "$BINARY_URL"; then
      break
    fi
    echo "Attempt $attempt to download bazel failed."
    if [ $attempt -eq $MAX_ATTEMPTS ]; then
      echo "Failed to download bazel after $MAX_ATTEMPTS attempts"
      exit 1
    fi
    sleep $SLEEP_SECONDS
    SLEEP_SECONDS=$((SLEEP_SECONDS * 2))
    attempt=$((attempt + 1))
  done
  chmod +x "$BAZEL_DIR/bazel"

  # Add to current shell's PATH
  export PATH="$BAZEL_DIR:$PATH"

  # Verify installation
  echo "Checking bazel installation..."
  bazel version || { echo "Bazel installation verification failed"; exit 1; }

  # Configure number of jobs based on memory
  if [[ "$OS" == linux ]]; then
    MEM=$(grep MemTotal < /proc/meminfo | awk '{print $2}')
    JOBS=$(( MEM / 1024 / 1024 / 3 ))
    echo "build --jobs=$JOBS" >> ~/.bazelrc
    grep "jobs" ~/.bazelrc
  fi
}

install_bazel_windows() {
  BAZEL_VERSION=$(get_bazel_version)
  choco install bazel --version="${BAZEL_VERSION}" --force
  VERSION=$(bazel version)
  echo "bazel version: $VERSION"
}

JDKS=(
"zulu21.28.85-ca-jdk21.0.0-linux_x64"
"zulu17.44.17-ca-crac-jdk17.0.8-linux_x64"
"zulu15.46.17-ca-jdk15.0.10-linux_x64"
"zulu13.54.17-ca-jdk13.0.14-linux_x64"
"zulu11.66.15-ca-jdk11.0.20-linux_x64"
"zulu8.72.0.17-ca-jdk8.0.382-linux_x64"
)

install_jdks() {
  cd "$ROOT"
  for jdk in "${JDKS[@]}"; do
    wget -q https://cdn.azul.com/zulu/bin/"$jdk".tar.gz -O "$jdk".tar.gz
    tar zxf "$jdk".tar.gz
  done
}

graalvm_test() {
  cd "$ROOT"/java
  mvn -T10 -B --no-transfer-progress clean install -DskipTests -pl '!:fory-testsuite'
  echo "Start to build graalvm native image"
  cd "$ROOT"/integration_tests/graalvm_tests
  mvn -DskipTests=true --no-transfer-progress -Pnative package
  echo "Built graalvm native image"
  echo "Start to run graalvm native image"
  ./target/main
  echo "Execute graalvm tests succeed!"
}

integration_tests() {
  cd "$ROOT"/java
  mvn -T10 -B --no-transfer-progress clean install -DskipTests
  echo "benchmark tests"
  cd "$ROOT"/benchmarks/java
  mvn -T10 -B --no-transfer-progress clean test install -Pjmh
  echo "Start JPMS tests"
  cd "$ROOT"/integration_tests/jpms_tests
  mvn -T10 -B --no-transfer-progress clean compile
  echo "Start jdk compatibility tests"
  cd "$ROOT"/integration_tests/jdk_compatibility_tests
  mvn -T10 -B --no-transfer-progress clean test
  for jdk in "${JDKS[@]}"; do
     export JAVA_HOME="$ROOT/$jdk"
     export PATH=$JAVA_HOME/bin:$PATH
     echo "First round for generate data: ${jdk}"
     mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fory.integration_tests.JDKCompatibilityTest
  done
  for jdk in "${JDKS[@]}"; do
     export JAVA_HOME="$ROOT/$jdk"
     export PATH=$JAVA_HOME/bin:$PATH
     echo "Second round for compatibility: ${jdk}"
     mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fory.integration_tests.JDKCompatibilityTest
  done
}

jdk17_plus_tests() {
  java -version
  export JDK_JAVA_OPTIONS="--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
  echo "Executing fory java tests"
  cd "$ROOT/java"
  set +e
  mvn -T10 --batch-mode --no-transfer-progress install
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    exit $testcode
  fi
  echo "Executing fory java tests succeeds"
}

kotlin_tests() {
  echo "Executing fory kotlin tests"
  cd "$ROOT/kotlin"
  set +e
  mvn -T16 --batch-mode --no-transfer-progress test -DfailIfNoTests=false
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    exit $testcode
  fi
  echo "Executing fory kotlin tests succeeds"
}

windows_java21_test() {
  java -version
  echo "Executing fory java tests"
  cd "$ROOT/java"
  set +e
  mvn -T10 --batch-mode --no-transfer-progress test install -pl '!fory-testsuite'
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    exit $testcode
  fi
  echo "Executing fory java tests succeeds"
}

case $1 in
    java8)
      echo "Executing fory java tests"
      cd "$ROOT/java"
      set +e
      mvn -T16 --batch-mode --no-transfer-progress test
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fory java tests succeeds"
    ;;
    java11)
      java -version
      echo "Executing fory java tests"
      cd "$ROOT/java"
      set +e
      mvn -T16 --batch-mode --no-transfer-progress test
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fory java tests succeeds"
    ;;
    java17)
      jdk17_plus_tests
    ;;
    java21)
      jdk17_plus_tests
    ;;
    java25)
      jdk17_plus_tests
    ;;
    kotlin)
      kotlin_tests
    ;;
    windows_java21)
      windows_java21_test
    ;;
    integration_tests)
      echo "Install jdk"
      install_jdks
      echo "Executing fory integration tests"
      integration_tests
      echo "Executing fory integration tests succeeds"
     ;;
    javascript)
      set +e
      echo "Executing fory javascript tests"
      cd "$ROOT/javascript"
      npm install
      node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        echo "Executing fory javascript tests failed"
        exit $testcode
      fi
      echo "Executing fory javascript tests succeeds"
    ;;
    rust)
      set -e
      rustup component add clippy-preview
      rustup component add rustfmt
      echo "Installing protoc for protobuf compilation"
      if command -v apt-get >/dev/null; then
        sudo apt-get update
        sudo apt-get install -y protobuf-compiler
      elif command -v brew >/dev/null; then
        brew install protobuf
      elif command -v yum >/dev/null; then
        sudo yum install -y protobuf-compiler
      else
        echo "Package manager not found, downloading protoc binary"
        curl -LO https://github.com/protocolbuffers/protobuf/releases/download/v21.12/protoc-21.12-linux-x86_64.zip
        unzip protoc-21.12-linux-x86_64.zip -d protoc
        sudo mv protoc/bin/* /usr/local/bin/
        sudo mv protoc/include/* /usr/local/include/
      fi
      echo "Executing fory rust tests"
      cd "$ROOT/rust"
      cargo doc --no-deps --document-private-items --all-features
      cargo fmt --all -- --check
      cargo fmt --all
      cargo clippy --workspace --all-features --all-targets
      cargo doc
      cargo build --all-features --all-targets
      cargo test
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        echo "Executing fory rust tests failed"
        exit $testcode
      fi
      cargo clean
      echo "Executing fory rust tests succeeds"
    ;;
    cpp)
      echo "Install pyarrow"
      "$ROOT"/ci/deploy.sh install_pyarrow
      export PATH=~/bin:$PATH
      echo "bazel version: $(bazel version)"
      set +e
      echo "Executing fory c++ tests"
      bazel test $(bazel query //...)
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        echo "Executing fory c++ tests failed"
        exit $testcode
      fi
      echo "Executing fory c++ tests succeeds"
    ;;
    python)
      install_pyfory
      pip install pandas
      cd "$ROOT/python"
      echo "Executing fory python tests"
      pytest -v -s --durations=60 pyfory/tests
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fory python tests succeeds"
      ENABLE_FORY_CYTHON_SERIALIZATION=0 pytest -v -s --durations=60 pyfory/tests
      testcode=$?
      if [[ $testcode -ne 0 ]]; then
        exit $testcode
      fi
      echo "Executing fory python tests succeeds"
    ;;
    go)
      echo "Executing fory go tests for go"
      cd "$ROOT/go/fory"
      go install ./cmd/fory
      cd "$ROOT/go/fory/tests"
      go generate
      go test -v ./...
      cd "$ROOT/go/fory"
      go test -v ./...
      echo "Executing fory go tests succeeds"
    ;;
    format)
      echo "Install format tools"
      pip install ruff
      echo "Executing format check"
      bash ci/format.sh
      cd "$ROOT/java"
      mvn -T10 -B --no-transfer-progress spotless:check
      mvn -T10 -B --no-transfer-progress checkstyle:check
      echo "Executing format check succeeds"
    ;;
    install_pyfory)
      install_pyfory
    ;;
    *)
      echo "Execute command $*"
      "$@"
      ;;
esac
