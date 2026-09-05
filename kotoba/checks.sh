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

# Compile the Kotoba v1 Fory header/record module with CLI 0.7.2 and
# assert the vendored fixture. Do not invent a pass: every gate reads
# a real compiler receipt or runtime value.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MODULE="${ROOT}/fory.kotoba"
FIXTURE="${ROOT}/fixtures/varint64-42.hex"
EXPECTED_HEX="01ff0754"

KOTOBA_VERSION="0.7.2"
KOTOBA_TARBALL="kotoba-linux-amd64.tar.gz"
KOTOBA_URL="https://github.com/kotoba-lang/kotoba/releases/download/v${KOTOBA_VERSION}/${KOTOBA_TARBALL}"
KOTOBA_SHA256="95e225461e1b8a21849b251e8c8b654693d2c8a516b258532771651e978e1977"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

need_cmd python3
need_cmd curl
need_cmd tar
need_cmd sha256sum
need_cmd grep

[[ -f "${MODULE}" ]] || fail "missing module ${MODULE}"
[[ -f "${FIXTURE}" ]] || fail "missing fixture ${FIXTURE}"

FILE_HEX="$(tr -d ' \n\t\r' <"${FIXTURE}" | tr 'A-F' 'a-f')"
[[ "${FILE_HEX}" == "${EXPECTED_HEX}" ]] || fail "fixture hex is ${FILE_HEX}, expected ${EXPECTED_HEX}"

MODULE_HEX="$(sed -n 's/^;; Vendored fixture bytes (hex): //p' "${MODULE}" | tr -d ' \n\t\r' | tr 'A-F' 'a-f')"
[[ "${MODULE_HEX}" == "${EXPECTED_HEX}" ]] || fail "module fixture comment is ${MODULE_HEX}, expected ${EXPECTED_HEX}"

python3 - "${MODULE}" "${EXPECTED_HEX}" <<'PY'
import pathlib
import re
import sys

module = pathlib.Path(sys.argv[1]).read_text()
expected = sys.argv[2]
wanted = {
    "fixture-b0": "0x01",
    "fixture-b1": "0xFF",
    "fixture-b2": "0x07",
    "fixture-b3": "0x54",
}
for name, lit in wanted.items():
    if not re.search(rf"\(defn {name} \[\] {lit}\)", module):
        raise SystemExit(f"module is missing ({name} {lit})")
lits = "".join(wanted[name][2:] for name in ("fixture-b0", "fixture-b1", "fixture-b2", "fixture-b3"))
if lits.lower() != expected:
    raise SystemExit(f"module literals {lits.lower()} != fixture {expected}")
PY

HEADER_BYTE="$(python3 -c "print(int('${EXPECTED_HEX}'[0:2], 16))")"
[[ "${HEADER_BYTE}" == "1" ]] || fail "fixture header byte is ${HEADER_BYTE}, expected 1"

if [[ -n "${KOTOBA_BIN:-}" ]]; then
  KOTOBA="${KOTOBA_BIN}"
  [[ -x "${KOTOBA}" ]] || fail "KOTOBA_BIN is not executable: ${KOTOBA}"
else
  CACHE="${KOTOBA_CACHE:-${ROOT}/.cache/kotoba-${KOTOBA_VERSION}}"
  mkdir -p "${CACHE}"
  TARBALL="${CACHE}/${KOTOBA_TARBALL}"
  if [[ ! -x "${CACHE}/kotoba" ]]; then
    echo "downloading Kotoba CLI ${KOTOBA_VERSION}"
    curl -fsSL -o "${TARBALL}" "${KOTOBA_URL}"
    echo "${KOTOBA_SHA256}  ${TARBALL}" | sha256sum -c -
    tar -xzf "${TARBALL}" -C "${CACHE}" kotoba
  fi
  KOTOBA="${CACHE}/kotoba"
  [[ -x "${KOTOBA}" ]] || fail "downloaded kotoba binary missing at ${KOTOBA}"
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT
WASM="${WORKDIR}/fory.wasm"
COMPILE_JSON="${WORKDIR}/compile.json"
RUN_JSON="${WORKDIR}/run.json"

echo "compile ${MODULE} with ${KOTOBA}"
"${KOTOBA}" compile "${MODULE}" --target wasm -o "${WASM}" --json >"${COMPILE_JSON}"

python3 - "${COMPILE_JSON}" "${WASM}" <<'PY'
import json
import pathlib
import sys

receipt = json.loads(pathlib.Path(sys.argv[1]).read_text())
wasm = pathlib.Path(sys.argv[2]).read_bytes()
if receipt.get("kotoba.cli/ok?") is not True:
    raise SystemExit(f"compile ok? {receipt.get('kotoba.cli/ok?')}: {receipt}")
if receipt.get("kotoba.cli/code") != "emitted":
    raise SystemExit(f"compile code {receipt.get('kotoba.cli/code')}: {receipt}")
data = receipt.get("kotoba.cli/data") or {}
if data.get("value-profile") != "i64-v1":
    raise SystemExit(f"value-profile {data.get('value-profile')!r}, expected i64-v1")
compat = data.get("compatibility") or {}
if compat.get("target") != "wasm32-kotoba-v1":
    raise SystemExit(f"target {compat.get('target')!r}, expected wasm32-kotoba-v1")
if data.get("value-abi") != "direct-v1":
    raise SystemExit(f"value-abi {data.get('value-abi')!r}, expected direct-v1")
features = data.get("wasm-features") or []
if features:
    raise SystemExit(f"wasm-features {features!r}, expected none for i64-v1")
if wasm[:4] != b"\x00asm":
    raise SystemExit("compiled artifact is not a wasm module")
if b"wasm32-kotoba-v1" not in wasm:
    raise SystemExit("compiled artifact is missing wasm32-kotoba-v1 target mark")
print("compile receipt: i64-v1 wasm32-kotoba-v1 direct-v1")
PY

echo "run ${MODULE}"
"${KOTOBA}" run "${MODULE}" --json >"${RUN_JSON}"

python3 - "${RUN_JSON}" <<'PY'
import json
import pathlib
import sys

receipt = json.loads(pathlib.Path(sys.argv[1]).read_text())
if receipt.get("kotoba.cli/ok?") is not True:
    raise SystemExit(f"run ok? {receipt.get('kotoba.cli/ok?')}: {receipt}")
if receipt.get("kotoba.cli/code") != "completed":
    raise SystemExit(f"run code {receipt.get('kotoba.cli/code')}: {receipt}")
data = receipt.get("kotoba.cli/data") or {}
result = data.get("kotoba.runtime/result") or {}
if result.get("kotoba.runtime/ok?") is not True:
    raise SystemExit(f"runtime ok? {result.get('kotoba.runtime/ok?')}: {receipt}")
value = result.get("kotoba.runtime/value")
if value != 1:
    raise SystemExit(f"runtime value {value!r}, expected 1")
print("run result: 1")
PY

echo "PASS: Kotoba v1 Fory header and VARINT64=42 fixture"
