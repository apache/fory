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

"""Generate plots and a markdown report from Swift benchmark JSON output."""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from plot_style import (  # noqa: E402
    BAR_EDGE_COLOR,
    GROUP_BAR_WIDTH,
    GROUP_X,
    add_compact_legend,
    apply_benchmark_style,
    format_markdown_with_prettier,
    format_throughput_label,
    format_throughput_tick,
    save_benchmark_figure,
    serializer_offset,
    set_grouped_operation_axis,
    style_throughput_axis,
)

apply_benchmark_style(plt)

SERIALIZER_ORDER = ["fory", "protobuf", "json"]
COLORS = {
    "fory": "#FF6f01",
    "protobuf": "#55BCC2",
    "json": "#8C6F6D",
}
DATATYPE_ORDER = [
    "struct",
    "sample",
    "mediacontent",
    "structlist",
    "samplelist",
    "mediacontentlist",
]
OPERATIONS = ["serialize", "deserialize"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate report for Swift benchmark results"
    )
    parser.add_argument(
        "--json-file",
        default="results/benchmark_results.json",
        help="Benchmark JSON output file",
    )
    parser.add_argument(
        "--output-dir",
        default="results",
        help="Directory for report output",
    )
    parser.add_argument(
        "--plot-prefix",
        default="",
        help="Prefix for image paths in markdown report",
    )
    return parser.parse_args()


def load_json(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def normalize_datatype(datatype: str) -> str:
    key = datatype.lower()
    if key == "numericstruct":
        return "struct"
    if key == "numericstructlist":
        return "structlist"
    return key


def datatype_title(datatype: str) -> str:
    datatype = normalize_datatype(datatype)
    if datatype == "struct":
        return "NumericStruct"
    if datatype == "structlist":
        return "NumericStructList"
    if datatype == "mediacontent":
        return "MediaContent"
    if datatype == "mediacontentlist":
        return "MediaContentList"
    if datatype.endswith("list"):
        return f"{datatype[:-4].capitalize()}List"
    return datatype.capitalize()


def datatype_plot_label(datatype: str) -> str:
    datatype = normalize_datatype(datatype)
    if datatype == "struct":
        return "NumericStruct"
    if datatype == "structlist":
        return "NumericStruct\nList"
    if datatype == "mediacontent":
        return "MediaContent"
    if datatype == "mediacontentlist":
        return "MediaContent\nList"
    if datatype.endswith("list"):
        return f"{datatype[:-4].capitalize()}\nList"
    return datatype.capitalize()


def format_tps(value) -> str:
    return f"{value:,.0f}" if value is not None and value > 0 else "N/A"


def format_size(value) -> str:
    return str(value) if isinstance(value, int) and value >= 0 else "N/A"


def format_tps_label(value: float) -> str:
    return format_throughput_label(value)


def format_tps_tick(value: float, _position) -> str:
    return format_throughput_tick(value, _position)


def collect_results(payload: dict) -> dict:
    results: dict = defaultdict(lambda: defaultdict(dict))
    for bench in payload.get("benchmarks", []):
        serializer = bench.get("serializer", "")
        datatype = bench.get("dataType", "")
        operation = bench.get("operation", "")
        ops = float(bench.get("opsPerSec", 0.0))
        if serializer and datatype and operation:
            results[datatype][operation][serializer] = ops
    return results


def plot_group(ax, results: dict, datatype: str) -> None:
    if datatype not in results:
        ax.set_title(f"{datatype_title(datatype)}\nNo Data")
        ax.axis("off")
        return

    available_serializers = [
        serializer
        for serializer in SERIALIZER_ORDER
        if any(
            results.get(datatype, {}).get(operation, {}).get(serializer, 0.0) > 0
            for operation in OPERATIONS
        )
    ]
    if not available_serializers:
        ax.set_title(f"{datatype_title(datatype)}\nNo Data")
        ax.axis("off")
        return

    x = GROUP_X
    for index, serializer in enumerate(available_serializers):
        values = [
            results.get(datatype, {}).get(operation, {}).get(serializer, 0.0)
            for operation in OPERATIONS
        ]
        offset = serializer_offset(index, len(available_serializers))
        ax.bar(
            x + offset,
            values,
            width=GROUP_BAR_WIDTH,
            label=serializer,
            color=COLORS.get(serializer, "#888888"),
            edgecolor=BAR_EDGE_COLOR,
            linewidth=0.8,
        )

    max_value = max(
        results.get(datatype, {}).get(operation, {}).get(serializer, 0.0)
        for operation in OPERATIONS
        for serializer in available_serializers
    )
    ax.set_ylim(0, max_value * 1.12)
    ax.set_title(datatype_title(datatype), pad=8)
    set_grouped_operation_axis(ax)
    style_throughput_axis(ax)
    ax.yaxis.set_major_formatter(FuncFormatter(format_tps_tick))
    add_compact_legend(ax)


def render_plot(results: dict, output_dir: str) -> str:
    fig, axes = plt.subplots(2, 3, figsize=(16.5, 9.0))
    fig.suptitle(
        "Swift Serialization Throughput", fontsize=15, fontweight="normal", y=0.955
    )

    for index, (ax, datatype) in enumerate(zip(axes.flat, DATATYPE_ORDER)):
        plot_group(ax, results, datatype)
        if index % 3 == 0:
            ax.set_ylabel("Throughput (ops/sec)", labelpad=10)

    fig.tight_layout(rect=[0.02, 0.02, 0.995, 0.965], w_pad=1.2, h_pad=1.25)
    output_path = os.path.join(output_dir, "throughput.png")
    save_benchmark_figure(fig, output_path)
    plt.close(fig)
    return output_path


def winner_cell(throughputs: dict) -> str:
    rows = [
        (serializer, value) for serializer, value in throughputs.items() if value > 0
    ]
    if not rows:
        return "-"
    rows.sort(key=lambda pair: pair[1], reverse=True)
    best_serializer, best_value = rows[0]
    if len(rows) == 1:
        return f"{best_serializer}"
    ratio = best_value / rows[1][1] if rows[1][1] > 0 else 0
    return f"{best_serializer} ({ratio:.2f}x)"


def write_report(
    payload: dict,
    results: dict,
    throughput_plot: str,
    output_dir: str,
    plot_prefix: str,
) -> str:
    context = payload.get("context", {})
    sizes = payload.get("serializedSizes", [])

    lines: list[str] = []
    lines.append("# Fory Swift Benchmark")
    lines.append("")
    lines.append(
        "This benchmark compares serialization and deserialization throughput for "
        "Apache Fory, Protocol Buffers, and JSON in Swift."
    )
    lines.append("")
    lines.append("## Throughput Plot")
    lines.append("")
    plot_name = os.path.basename(throughput_plot)
    if plot_prefix:
        image_path = f"{plot_prefix.rstrip('/')}/{plot_name}"
    else:
        image_path = plot_name
    lines.append(f"![Throughput]({image_path})")
    lines.append("")
    lines.append("## Hardware and Runtime Info")
    lines.append("")
    lines.append("| Key | Value |")
    lines.append("| --- | --- |")
    lines.append(f"| Timestamp | {context.get('timestamp', '-')} |")
    lines.append(f"| OS | {context.get('os', '-')} |")
    lines.append(f"| Host | {context.get('host', '-')} |")
    lines.append(f"| CPU Cores (Logical) | {context.get('cpuCoresLogical', '-')} |")
    memory = context.get("memoryGB")
    memory_str = f"{memory:.2f}" if isinstance(memory, (int, float)) else "-"
    lines.append(f"| Memory (GB) | {memory_str} |")
    lines.append(f"| Duration per case (s) | {context.get('durationSeconds', '-')} |")
    lines.append("")
    lines.append("## Throughput Results")
    lines.append("")
    lines.append(
        "| Datatype | Operation | Fory TPS | Protobuf TPS | JSON TPS | Fastest |"
    )
    lines.append("| --- | --- | ---: | ---: | ---: | --- |")

    for datatype in DATATYPE_ORDER:
        if datatype not in results:
            continue
        for operation in OPERATIONS:
            throughputs = results.get(datatype, {}).get(operation, {})
            fory = throughputs.get("fory")
            protobuf = throughputs.get("protobuf")
            json_tps = throughputs.get("json")
            lines.append(
                "| "
                + f"{datatype_title(datatype)} | {operation.capitalize()} | "
                + f"{format_tps(fory)} | {format_tps(protobuf)} | {format_tps(json_tps)} | "
                + f"{winner_cell(throughputs)} |"
            )

    lines.append("")
    lines.append("## Serialized Size (bytes)")
    lines.append("")
    lines.append("| Datatype | Fory | Protobuf | JSON |")
    lines.append("| --- | ---: | ---: | ---: |")
    sizes_by_datatype = {
        normalize_datatype(str(entry.get("dataType", ""))): entry for entry in sizes
    }
    for datatype in DATATYPE_ORDER:
        entry = sizes_by_datatype.get(datatype)
        if entry is None:
            continue
        datatype_label = datatype_title(datatype)
        lines.append(
            "| "
            + f"{datatype_label} | "
            + f"{format_size(entry.get('fory'))} | "
            + f"{format_size(entry.get('protobuf'))} | "
            + f"{format_size(entry.get('json'))} |"
        )

    report_path = os.path.join(output_dir, "README.md")
    legacy_report_path = os.path.join(output_dir, "REPORT.md")
    report_text = "\n".join(lines) + "\n"
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report_text)
    with open(legacy_report_path, "w", encoding="utf-8") as f:
        f.write(report_text)

    format_markdown_with_prettier(report_path, legacy_report_path)

    return report_path


def main() -> int:
    args = parse_args()
    Path(args.output_dir).mkdir(parents=True, exist_ok=True)

    payload = load_json(args.json_file)
    results = collect_results(payload)
    throughput_plot = render_plot(results, args.output_dir)
    report = write_report(
        payload, results, throughput_plot, args.output_dir, args.plot_prefix
    )

    print(f"Generated report: {report}")
    print(f"Generated plot: {throughput_plot}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
