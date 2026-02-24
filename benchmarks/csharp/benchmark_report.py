#!/usr/bin/env python3
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

import argparse
import json
import os
from collections import defaultdict


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate markdown report from C# benchmark JSON output")
    parser.add_argument("--json-file", default="benchmark_results.json", help="Benchmark JSON output file")
    parser.add_argument("--output-dir", default="report", help="Output directory")
    return parser.parse_args()


def load_results(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def format_ops(value: float) -> str:
    return f"{value:,.0f}"


def format_ns(value: float) -> str:
    return f"{value:,.1f}"


def format_size(value: float) -> str:
    return str(int(round(value)))


def format_datatype_name(value: str) -> str:
    mapping = {
        "struct": "Struct",
        "sample": "Sample",
        "mediacontent": "MediaContent",
        "structlist": "StructList",
        "samplelist": "SampleList",
        "mediacontentlist": "MediaContentList",
    }
    return mapping.get(value, value)


def build_report(data: dict) -> str:
    lines: list[str] = []
    lines.append("# Fory C# Benchmark Report")
    lines.append("")
    lines.append("## Environment")
    lines.append("")
    lines.append(f"- Generated at (UTC): `{data['GeneratedAtUtc']}`")
    lines.append(f"- Runtime version: `{data['RuntimeVersion']}`")
    lines.append(f"- OS: `{data['OsDescription']}`")
    lines.append(f"- OS architecture: `{data['OsArchitecture']}`")
    lines.append(f"- Process architecture: `{data['ProcessArchitecture']}`")
    lines.append(f"- CPU logical cores: `{data['ProcessorCount']}`")
    lines.append(f"- Warmup seconds: `{data['WarmupSeconds']}`")
    lines.append(f"- Duration seconds: `{data['DurationSeconds']}`")
    lines.append("")

    grouped: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for row in data["Results"]:
        grouped[(row["DataType"], row["Operation"])].append(row)

    lines.append("## Throughput Results")
    lines.append("")

    for key in sorted(grouped.keys()):
        data_type, operation = key
        rows = sorted(grouped[key], key=lambda item: item["OperationsPerSecond"], reverse=True)
        best = rows[0]

        lines.append(f"### `{data_type}` / `{operation}`")
        lines.append("")
        lines.append("| Serializer | Ops/sec | ns/op | Relative to best |")
        lines.append("| ---------- | ------: | ----: | ---------------: |")

        for row in rows:
            relative = best["OperationsPerSecond"] / row["OperationsPerSecond"]
            lines.append(
                "| "
                f"{row['Serializer']}"
                " | "
                f"{format_ops(row['OperationsPerSecond'])}"
                " | "
                f"{format_ns(row['AverageNanoseconds'])}"
                " | "
                f"{relative:.2f}x"
                " |"
            )

        lines.append("")

    size_totals: dict[tuple[str, str], list[int]] = defaultdict(list)
    for row in data["Results"]:
        size_totals[(row["DataType"], row["Serializer"])].append(row["SerializedSize"])

    lines.append("## Size Comparison")
    lines.append("")
    lines.append("### Serialized Data Sizes (bytes)")
    lines.append("")
    lines.append("| Datatype | fory | protobuf | msgpack |")
    lines.append("| -------- | ---- | -------- | ------- |")

    size_by_data_type: dict[str, dict[str, float]] = defaultdict(dict)
    for (data_type, serializer), values in size_totals.items():
        size_by_data_type[data_type][serializer] = sum(values) / len(values)

    preferred_order = [
        "struct",
        "sample",
        "mediacontent",
        "structlist",
        "samplelist",
        "mediacontentlist",
    ]
    remaining = sorted(key for key in size_by_data_type.keys() if key not in preferred_order)
    data_type_order = [key for key in preferred_order if key in size_by_data_type] + remaining
    serializers = ["fory", "protobuf", "msgpack"]
    for data_type in data_type_order:
        cells = [format_datatype_name(data_type)]
        for serializer in serializers:
            size = size_by_data_type[data_type].get(serializer)
            cells.append("-" if size is None else format_size(size))
        lines.append(f"| {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} |")

    lines.append("")

    serializer_totals: dict[str, list[float]] = defaultdict(list)
    for row in data["Results"]:
        serializer_totals[row["Serializer"]].append(row["OperationsPerSecond"])

    lines.append("## Aggregate Throughput")
    lines.append("")
    lines.append("| Serializer | Mean ops/sec across all cases |")
    lines.append("| ---------- | ----------------------------: |")
    for serializer in sorted(serializer_totals.keys()):
        values = serializer_totals[serializer]
        mean_value = sum(values) / len(values)
        lines.append(f"| {serializer} | {format_ops(mean_value)} |")

    lines.append("")
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    result = load_results(args.json_file)

    os.makedirs(args.output_dir, exist_ok=True)
    report_path = os.path.join(args.output_dir, "REPORT.md")

    report = build_report(result)
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report)

    print(f"Report written to {report_path}")


if __name__ == "__main__":
    main()
