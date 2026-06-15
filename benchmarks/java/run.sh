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
DATA_FILTER=""
SERIALIZER_FILTER=""
DURATION_SECONDS="3"
REPORT_DIR="${SCRIPT_DIR}/reports"
DOCS_OUTPUT_DIR="${SCRIPT_DIR}/../../docs/benchmarks/java"
SKIP_BUILD="false"
GENERATE_REPORT="true"

usage() {
  cat <<'EOF'
Usage: ./run.sh [OPTIONS]

Options:
  --data <struct|sample|mediacontent|structlist|samplelist|mediacontentlist>
                               Filter benchmark by data type
  --serializer <fory|protobuf|flatbuffer>
                               Filter benchmark by serializer
  --duration <seconds>         JMH warmup and measurement duration per iteration
  --reports-dir <dir>          Local directory for benchmark_results.json and throughput.png
                               (default: reports)
  --output-dir <dir>           Docs directory for copied throughput.png and README xlang section
  --no-copy-docs               Skip copying xlang report artifacts into docs/benchmarks/java
  --skip-build                 Reuse an existing target/benchmarks.jar
  --no-report                  Do not generate throughput.png
  --help                       Show this help
EOF
}

lower() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

serializer_regex() {
  case "$(lower "$1")" in
    "")
      printf '%s' "Fory|Protobuf|Flatbuffer"
      ;;
    fory)
      printf '%s' "Fory"
      ;;
    protobuf)
      printf '%s' "Protobuf"
      ;;
    flatbuffer)
      printf '%s' "Flatbuffer"
      ;;
    *)
      echo "Unknown serializer: $1" >&2
      echo "Expected one of: fory, protobuf, flatbuffer" >&2
      exit 1
      ;;
  esac
}

data_regex() {
  case "$(lower "$1")" in
    "")
      printf '%s' "NumericStruct|Sample|MediaContent|NumericStructList|SampleList|MediaContentList"
      ;;
    struct)
      printf '%s' "NumericStruct"
      ;;
    sample)
      printf '%s' "Sample"
      ;;
    mediacontent)
      printf '%s' "MediaContent"
      ;;
    structlist)
      printf '%s' "NumericStructList"
      ;;
    samplelist)
      printf '%s' "SampleList"
      ;;
    mediacontentlist)
      printf '%s' "MediaContentList"
      ;;
    *)
      echo "Unknown data type: $1" >&2
      echo "Expected one of: struct, sample, mediacontent, structlist, samplelist, mediacontentlist" >&2
      exit 1
      ;;
  esac
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
    --data)
      DATA_FILTER="${2:-}"
      shift 2
      ;;
    --serializer)
      SERIALIZER_FILTER="${2:-}"
      shift 2
      ;;
    --duration)
      DURATION_SECONDS="${2:-}"
      shift 2
      ;;
    --output-dir)
      DOCS_OUTPUT_DIR="${2:-}"
      shift 2
      ;;
    --no-copy-docs)
      DOCS_OUTPUT_DIR=""
      shift
      ;;
    --reports-dir)
      REPORT_DIR="${2:-}"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    --no-report)
      GENERATE_REPORT="false"
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

if [[ "${FORY_BENCH_SCHEMA_MISMATCH:-0}" == "1" && "$(lower "${SERIALIZER_FILTER}")" != "fory" ]]; then
  echo "FORY_BENCH_SCHEMA_MISMATCH=1 supports only Fory benchmarks; rerun with --serializer fory." >&2
  exit 1
fi

SERIALIZERS="$(serializer_regex "${SERIALIZER_FILTER}")"
DATA_TYPES="$(data_regex "${DATA_FILTER}")"
JMH_DURATION="$(jmh_time "${DURATION_SECONDS}")"
RESULT_JSON="${REPORT_DIR}/benchmark_results.json"
BENCHMARK_REGEX="org.apache.fory.benchmark.XlangBenchmark.BM_(${SERIALIZERS})_(${DATA_TYPES})_(Serialize|Deserialize)"

mkdir -p "${REPORT_DIR}"

cd "${SCRIPT_DIR}"

if [[ "${SKIP_BUILD}" != "true" ]]; then
  mvn -Pjmh -DskipTests package
fi

JMH_CMD=(
  java --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  -jar target/benchmarks.jar "${BENCHMARK_REGEX}"
  -f 1
  -wi 3
  -i 3
  -t 1
)
JMH_CMD+=(
  -w "${JMH_DURATION}"
  -r "${JMH_DURATION}"
  -bm thrpt
  -tu s
  -rf json
  -rff "${RESULT_JSON}"
)

ENABLE_FORY_DEBUG_OUTPUT=0 "${JMH_CMD[@]}"

if [[ "${GENERATE_REPORT}" == "true" ]]; then
  REPORT_ARGS=(--json-file "${RESULT_JSON}" --output-dir "${REPORT_DIR}")
  if [[ -n "${DOCS_OUTPUT_DIR}" ]]; then
    REPORT_ARGS+=(--docs-output-dir "${DOCS_OUTPUT_DIR}")
  fi
  python3 benchmark_report.py "${REPORT_ARGS[@]}"
fi
