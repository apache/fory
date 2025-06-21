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


set -x

# Cause the script to exit if a single command fails.
set -e

# configure ~/.pypirc before run this script
#if [ ! -f ~/.pypirc ]; then
#  echo  "Please configure .pypirc before run this script"
#  exit 1
#fi

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
WHEEL_DIR="$ROOT/.whl"

PYTHONS=("cp37-cp37m"
         "cp38-cp38"
         "cp39-cp39"
         "cp310-cp310"
         "cp310-cp311"
         "cp312-cp312"
         "cp313-cp313")

VERSIONS=("3.7"
          "3.8"
          "3.9"
          "3.10"
          "3.11"
          "3.12"
          "3.13")

create_py_envs() {
  source $(conda info --base)/etc/profile.d/conda.sh
  for version in "${VERSIONS[@]}"; do
    conda create -y --name "py$version" python="$version"
  done
  conda env list
}

rename_wheels() {
  for path in "$1"/*.whl; do
    if [ -f "${path}" ]; then
      # Rename linux to manylinux1
      new_path="${path//linux/manylinux1}"
      if [ "${path}" != "${new_path}" ]; then
        mv "${path}" "${new_path}"
      fi

      # Copy macosx_14_0_x86_64 to macosx_10_12_x86_64
      if [[ "${path}" == *macosx_14_0_x86_64.whl ]]; then
        copy_path="${path//macosx_14_0_x86_64/macosx_10_12_x86_64}"
        mv "${path}" "${copy_path}"
      fi
    fi
  done
}

rename_mac_wheels() {
  for path in "$WHEEL_DIR"/*.whl; do
    if [ -f "${path}" ]; then
      cp "${path}" "${path//macosx_12_0_x86_64/macosx_10_13_x86_64}"
    fi
  done
}

bump_version() {
  python "$ROOT/ci/release.py" bump_version -l all -version "$1"
}

bump_java_version() {
  python "$ROOT/ci/release.py" bump_version -l java -version "$1"
}

bump_py_version() {
  local version="$1"
  if [ -z "$version" ]; then
    # Get the latest tag from the current Git repository
    version=$(git describe --tags --abbrev=0)
    # Check if the tag starts with 'v' and strip it
    if [[ $version == v* ]]; then
      version="${version:1}"
    fi
  fi
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
  echo "Python version $(python -V), path $(which python)"
  install_pyarrow
  pip install Cython wheel pytest
  pushd "$ROOT/python"
  pip list
  echo "Install pyfory"
  # Fix strange installed deps not found
  pip install setuptools -U

  bazel build //:cp_fory_so

  python setup.py bdist_wheel --dist-dir=../dist
  popd
}

deploy_python() {
  source $(conda info --base)/etc/profile.d/conda.sh
  if command -v pyenv; then
    pyenv local system
  fi
  cd "$ROOT/python"
  rm -rf "$WHEEL_DIR"
  mkdir -p "$WHEEL_DIR"
  for ((i=0; i<${#PYTHONS[@]}; ++i)); do
    PYTHON=${PYTHONS[i]}
    ENV="py${VERSIONS[i]}"
    conda activate "$ENV"
    python -V
    git clean -f -f -x -d -e .whl
    # Ensure bazel select the right version of python
    bazel clean --expunge
    install_pyarrow
    pip install --ignore-installed twine setuptools cython numpy
    pyarrow_dir=$(python -c "import importlib.util; import os; print(os.path.dirname(importlib.util.find_spec('pyarrow').origin))")
    # ensure pyarrow is clean
    rm -rf "$pyarrow_dir"
    pip install --ignore-installed pyarrow==$pyarrow_version
    python setup.py clean
    python setup.py bdist_wheel
    mv dist/pyfory*.whl "$WHEEL_DIR"
  done
  rename_wheels "$WHEEL_DIR"
  twine check "$WHEEL_DIR"/pyfory*.whl
  twine upload -r pypi "$WHEEL_DIR"/pyfory*.whl
}

install_pyarrow() {
  pyversion=$(python -V | cut -d' ' -f2)
  if [[ $pyversion  ==  3.13* ]]; then
    pip install pyarrow==18.0.0
    pip install numpy
  else
    pip install pyarrow==15.0.0
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
python) # Deploy wheel to pypi
  deploy_python
  ;;
*)
  echo "Execute command $*"
  "$@"
  ;;
esac
