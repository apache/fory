#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

python "${SCRIPT_DIR}/generate_idl.py" --lang dart

cd "${SCRIPT_DIR}/dart"
dart pub get
dart run build_runner build --delete-conflicting-outputs
dart test

IDL_PEER_LANG=dart "${SCRIPT_DIR}/run_java_tests.sh"
