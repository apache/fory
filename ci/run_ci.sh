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

JDKS=(
"zulu26.30.11-ca-crac-jdk26.0.1-linux_x64"
"zulu25.30.17-ca-jdk25.0.1-linux_x64"
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
  java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')
  if [[ "$java_version" == 1.* ]]; then
    java_major=$(echo "$java_version" | cut -d. -f2)
  else
    java_major=$(echo "$java_version" | cut -d. -f1)
  fi
  if [[ "$java_major" -ge 25 ]]; then
    export JDK_JAVA_OPTIONS="$(jdk25_runtime_options "ALL-UNNAMED") $(jdk25_javac_options)"
  else
    unset JDK_JAVA_OPTIONS
  fi
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

jdk25_access_options() {
  local fory_open_targets="${1:-org.apache.fory.core}"
  printf "%s" "--sun-misc-unsafe-memory-access=deny"
  printf " %s" "--add-opens=java.base/java.lang.invoke=${fory_open_targets}"
}

jdk25_runtime_options() {
  local fory_targets="${1:-org.apache.fory.core}"
  printf "%s" "$(jdk25_access_options "$fory_targets")"
}

jdk25_javac_options() {
  printf "%s" "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED"
  printf " %s" "--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED"
}

use_jdk() {
  local jdk="$1"
  export JAVA_HOME="$ROOT/$jdk"
  export PATH=$JAVA_HOME/bin:$PATH
  if [[ "$jdk" =~ zulu([0-9]+) ]]; then
    local java_major="${BASH_REMATCH[1]}"
    if [[ "$java_major" -ge 25 ]]; then
      export JDK_JAVA_OPTIONS="$(jdk25_runtime_options) $(jdk25_javac_options)"
    else
      unset JDK_JAVA_OPTIONS
    fi
  else
    unset JDK_JAVA_OPTIONS
  fi
}

install_jdk25_fory_artifacts() {
  local old_java_home="${JAVA_HOME:-}"
  local old_path="$PATH"
  local old_jdk_java_options="${JDK_JAVA_OPTIONS:-}"
  local had_java_home=0
  local had_jdk_java_options=0
  [[ -n "${JAVA_HOME+x}" ]] && had_java_home=1
  [[ -n "${JDK_JAVA_OPTIONS+x}" ]] && had_jdk_java_options=1

  use_jdk "zulu25.30.17-ca-jdk25.0.1-linux_x64"
  export JDK_JAVA_OPTIONS="$(jdk25_javac_options)"
  cd "$ROOT"/java
  mvn -T10 -B --no-transfer-progress clean install -DskipTests
  echo "Verify JDK25 benchmark multi-release jar"
  cd "$ROOT"/benchmarks/java
  mvn -T10 -B --no-transfer-progress -Pjmh -DskipTests install
  unset JDK_JAVA_OPTIONS
  echo "Verify JPMS tests on JDK25"
  cd "$ROOT"/integration_tests/jpms_tests
  mvn -T10 -B --no-transfer-progress clean test

  if [[ "$had_java_home" -eq 1 ]]; then
    export JAVA_HOME="$old_java_home"
  else
    unset JAVA_HOME
  fi
  export PATH="$old_path"
  if [[ "$had_jdk_java_options" -eq 1 ]]; then
    export JDK_JAVA_OPTIONS="$old_jdk_java_options"
  else
    unset JDK_JAVA_OPTIONS
  fi
}

integration_tests() {
  install_jdk25_fory_artifacts
  echo "benchmark tests"
  cd "$ROOT"/benchmarks/java
  mvn -T10 -B --no-transfer-progress clean test install -Pjmh
  echo "Start JPMS tests"
  cd "$ROOT"/integration_tests/jpms_tests
  mvn -T10 -B --no-transfer-progress clean test
  ./run_jlink_smoke.sh
  echo "Start jdk compatibility tests"
  cd "$ROOT"/integration_tests/jdk_compatibility_tests
  mvn -T10 -B --no-transfer-progress clean test
  for jdk in "${JDKS[@]}"; do
     if [[ "$jdk" =~ zulu([0-9]+) && "${BASH_REMATCH[1]}" -ge 25 ]]; then
       echo "Skipping classpath JDK compatibility data generation for ${jdk}; JDK25+ zero-Unsafe coverage runs on JPMS"
       continue
     fi
     use_jdk "$jdk"
     echo "First round for generate data: ${jdk}"
     mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fory.integration_tests.JDKCompatibilityTest
  done
  for jdk in "${JDKS[@]}"; do
     if [[ "$jdk" =~ zulu([0-9]+) && "${BASH_REMATCH[1]}" -ge 25 ]]; then
       echo "Skipping classpath JDK compatibility verification for ${jdk}; JDK25+ zero-Unsafe coverage runs on JPMS"
       continue
     fi
     use_jdk "$jdk"
     echo "Second round for compatibility: ${jdk}"
     mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fory.integration_tests.JDKCompatibilityTest
  done
}

jdk17_plus_tests() {
  java -version
  java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')
  if [[ "$java_version" == 1.* ]]; then
    java_major=$(echo "$java_version" | cut -d. -f2)
  else
    java_major=$(echo "$java_version" | cut -d. -f1)
  fi
  JDK_JAVA_OPTIONS="--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
  if [[ "$java_major" -ge 25 ]]; then
    JDK_JAVA_OPTIONS="$JDK_JAVA_OPTIONS $(jdk25_runtime_options "ALL-UNNAMED") $(jdk25_javac_options)"
  fi
  export JDK_JAVA_OPTIONS
  echo "Executing fory java tests"
  cd "$ROOT/java"
  set +e
  if [[ "$java_major" -ge 25 ]]; then
    # The JDK25+ profile overlays Surefire's classpath with Java25 replacement
    # classes while keeping the test run unnamed. Keep JPMS coverage below as a
    # separate named-module check.
    mvn -T10 --batch-mode --no-transfer-progress clean install
  else
    mvn -T10 --batch-mode --no-transfer-progress install
  fi
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    exit $testcode
  fi
  if [[ "$java_major" -ge 25 ]]; then
    unset JDK_JAVA_OPTIONS
    echo "Executing JDK${java_major} JPMS tests"
    cd "$ROOT/integration_tests/jpms_tests"
    mvn -T10 --batch-mode --no-transfer-progress clean test
    testcode=$?
    if [[ $testcode -ne 0 ]]; then
      exit $testcode
    fi
  fi
  echo "Executing fory java tests succeeds"
}

kotlin_tests() {
  echo "Executing fory kotlin tests"
  cd "$ROOT/kotlin"
  set +e
  # The KSP Maven plugin discovers processors from JAR artifacts. Build and install the
  # processor first so the generated-test module does not see the reactor classes directory as
  # its processor artifact.
  mvn -T16 --batch-mode --no-transfer-progress -pl fory-kotlin,fory-kotlin-ksp -am -DskipTests install
  testcode=$?
  if [[ $testcode -ne 0 ]]; then
    exit $testcode
  fi
  java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')
  if [[ "$java_version" == 1.* ]]; then
    java_major=$(echo "$java_version" | cut -d. -f2)
  else
    java_major=$(echo "$java_version" | cut -d. -f1)
  fi
  if [[ "$java_major" -ge 17 ]]; then
    mvn -T16 --batch-mode --no-transfer-progress test -DfailIfNoTests=false
  else
    echo "Skipping fory-kotlin-tests on JDK < 17 because ksp-maven-plugin requires Java 17+"
    mvn -T16 --batch-mode --no-transfer-progress -pl fory-kotlin,fory-kotlin-ksp -am test -DfailIfNoTests=false
  fi
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
      mvn -T16 --batch-mode --no-transfer-progress clean test
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
      mvn -T16 --batch-mode --no-transfer-progress clean install
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
    java26)
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
      ARCH="$(uname -m)"
      BAZEL_TEST_CONFIG="--config=fory_cpp_werror"
      case "${ARCH}" in
        x86_64|amd64)
          BAZEL_TEST_CONFIG="--config=x86_64 ${BAZEL_TEST_CONFIG}"
          ;;
      esac
      set +e
      echo "Executing fory c++ tests"
      bazel test ${BAZEL_TEST_CONFIG} $(bazel query //...)
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
