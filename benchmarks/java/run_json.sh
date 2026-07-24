#!/usr/bin/env bash
#
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

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT_DIR="${SCRIPT_DIR}/reports/json"
DURATION_SECONDS="2"
WARMUP_ITERATIONS="3"
MEASUREMENT_ITERATIONS="5"
LIBS="fory-json,jackson,gson"
SKIP_BUILD="false"

usage() {
  cat <<'EOF'
Usage: ./run_json.sh [OPTIONS]

Run selected Java JSON serialization and deserialization benchmarks, then
generate charts and a Markdown report.

Options:
  --duration <seconds>       Warmup and measurement time per iteration (default: 2)
  --warmup-iterations <n>    Number of JMH warmup iterations (default: 3)
  --iterations <n>           Number of JMH measurement iterations (default: 5)
  --libs <names>             Comma-separated libraries in chart order
                             (default: fory-json,jackson,gson)
                             Available: fory-json, jackson, gson, fastjson2
  --reports-dir <dir>        Output directory (default: reports/json)
  --skip-build               Reuse target/benchmarks.jar
  --help                     Show this help
EOF
}

require_value() {
  if [[ $# -lt 2 || -z "$2" ]]; then
    echo "Missing value for $1" >&2
    exit 1
  fi
}

jmh_time() {
  case "$1" in
    *[!0-9.]*)
      printf '%s' "$1"
      ;;
    *.*)
      python3 - "$1" <<'PY'
import sys

millis = max(1, round(float(sys.argv[1]) * 1000))
print(f"{millis}ms")
PY
      ;;
    *)
      printf '%ss' "$1"
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)
      require_value "$@"
      DURATION_SECONDS="$2"
      shift 2
      ;;
    --warmup-iterations)
      require_value "$@"
      WARMUP_ITERATIONS="$2"
      shift 2
      ;;
    --iterations)
      require_value "$@"
      MEASUREMENT_ITERATIONS="$2"
      shift 2
      ;;
    --libs)
      require_value "$@"
      LIBS="$2"
      shift 2
      ;;
    --reports-dir)
      require_value "$@"
      REPORT_DIR="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

case "${REPORT_DIR}" in
  /*) ;;
  *) REPORT_DIR="${PWD}/${REPORT_DIR}" ;;
esac

if ! python3 -c "import matplotlib, numpy" >/dev/null 2>&1; then
  echo "Missing plotting dependencies. Install them with: pip install matplotlib numpy" >&2
  exit 1
fi

LIB_CONFIG="$(python3 "${SCRIPT_DIR}/plot_json_benchmark.py" \
  --libs "${LIBS}" --print-lib-config 2>&1)" || {
  echo "${LIB_CONFIG}" >&2
  exit 1
}
IFS=$'\t' read -r NORMALIZED_LIBS SERIALIZER_REGEX <<<"${LIB_CONFIG}"

JMH_DURATION="$(jmh_time "${DURATION_SECONDS}")"
RESULT_JSON="${REPORT_DIR}/benchmark_results.json"
BENCHMARK_REGEX="org\\.apache\\.fory\\.benchmark\\.JsonSerializationSuite\\.(${SERIALIZER_REGEX})(To|From)Json(Bytes|String)$"

mkdir -p "${REPORT_DIR}"
cd "${SCRIPT_DIR}"

if [[ "${SKIP_BUILD}" != "true" ]]; then
  mvn -Pjmh -DskipTests package
fi

if [[ ! -f target/benchmarks.jar ]]; then
  echo "Missing target/benchmarks.jar; rerun without --skip-build." >&2
  exit 1
fi

ENABLE_FORY_DEBUG_OUTPUT=0 java \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  -jar target/benchmarks.jar "${BENCHMARK_REGEX}" \
  -f 1 \
  -wi "${WARMUP_ITERATIONS}" \
  -i "${MEASUREMENT_ITERATIONS}" \
  -t 1 \
  -w "${JMH_DURATION}" \
  -r "${JMH_DURATION}" \
  -bm thrpt \
  -tu s \
  -rf json \
  -rff "${RESULT_JSON}"

python3 plot_json_benchmark.py \
  --json-file "${RESULT_JSON}" \
  --output-dir "${REPORT_DIR}" \
  --libs "${NORMALIZED_LIBS}"

echo "JSON benchmark report: ${REPORT_DIR}/README.md"
echo "JSON String benchmark chart: ${REPORT_DIR}/string_throughput.png"
echo "JSON UTF-8 bytes benchmark chart: ${REPORT_DIR}/utf8_bytes_throughput.png"
echo "Raw JMH results: ${RESULT_JSON}"
