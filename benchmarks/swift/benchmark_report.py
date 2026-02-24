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
from collections import defaultdict
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

SERIALIZER_ORDER = ["fory", "protobuf", "msgpack"]
COLORS = {
    "fory": "#FF6f01",
    "protobuf": "#55BCC2",
    "msgpack": "#8C6F6D",
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


def datatype_title(datatype: str) -> str:
    if datatype == "mediacontent":
        return "MediaContent"
    if datatype == "mediacontentlist":
        return "MediaContentList"
    if datatype.endswith("list"):
        return f"{datatype[:-4].capitalize()}List"
    return datatype.capitalize()


def datatype_plot_label(datatype: str) -> str:
    if datatype == "mediacontent":
        return "MediaContent"
    if datatype == "mediacontentlist":
        return "MediaContent\nList"
    if datatype.endswith("list"):
        return f"{datatype[:-4].capitalize()}\nList"
    return datatype.capitalize()


def format_tps(value: float) -> str:
    return f"{value:,.0f}"


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


def plot_group(
    ax, results: dict, datatypes: list[str], operation: str, title: str
) -> None:
    if not datatypes:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    available_serializers = [
        serializer
        for serializer in SERIALIZER_ORDER
        if any(
            results.get(dt, {}).get(operation, {}).get(serializer, 0.0) > 0
            for dt in datatypes
        )
    ]
    if not available_serializers:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    x = np.arange(len(datatypes))
    width = 0.8 / len(available_serializers)
    for index, serializer in enumerate(available_serializers):
        values = [
            results.get(dt, {}).get(operation, {}).get(serializer, 0.0)
            for dt in datatypes
        ]
        offset = (index - (len(available_serializers) - 1) / 2) * width
        ax.bar(
            x + offset,
            values,
            width=width,
            label=serializer,
            color=COLORS.get(serializer, "#888888"),
        )

    ax.set_title(title)
    ax.set_xticks(x)
    ax.set_xticklabels([datatype_plot_label(dt) for dt in datatypes])
    ax.set_ylabel("Throughput (ops/sec)")
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.legend()
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))


def render_plot(results: dict, output_dir: str) -> str:
    non_list = [
        dt for dt in DATATYPE_ORDER if dt in results and not dt.endswith("list")
    ]
    list_only = [dt for dt in DATATYPE_ORDER if dt in results and dt.endswith("list")]

    fig, axes = plt.subplots(1, 4, figsize=(28, 6))
    fig.suptitle("Swift Serialization Throughput", fontsize=14)

    plot_group(
        axes[0],
        results,
        non_list,
        "serialize",
        "Serialize Throughput (higher is better)",
    )
    plot_group(
        axes[1],
        results,
        non_list,
        "deserialize",
        "Deserialize Throughput (higher is better)",
    )
    plot_group(
        axes[2],
        results,
        list_only,
        "serialize",
        "Serialize Throughput (*List)",
    )
    plot_group(
        axes[3],
        results,
        list_only,
        "deserialize",
        "Deserialize Throughput (*List)",
    )

    fig.tight_layout()
    output_path = os.path.join(output_dir, "throughput.png")
    plt.savefig(output_path, dpi=150)
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
        "Apache Fory, Protocol Buffers, and MessagePack in Swift."
    )
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
    plot_name = os.path.basename(throughput_plot)
    if plot_prefix:
        image_path = f"{plot_prefix.rstrip('/')}/{plot_name}"
    else:
        image_path = plot_name
    lines.append('<p align="center">')
    lines.append(f'<img src="{image_path}" width="95%">')
    lines.append("</p>")
    lines.append("")
    lines.append(
        "| Datatype | Operation | Fory TPS | Protobuf TPS | Msgpack TPS | Fastest |"
    )
    lines.append("| --- | --- | ---: | ---: | ---: | --- |")

    for datatype in DATATYPE_ORDER:
        if datatype not in results:
            continue
        for operation in OPERATIONS:
            throughputs = results.get(datatype, {}).get(operation, {})
            fory = throughputs.get("fory", 0.0)
            protobuf = throughputs.get("protobuf", 0.0)
            msgpack = throughputs.get("msgpack", 0.0)
            lines.append(
                "| "
                + f"{datatype_title(datatype)} | {operation.capitalize()} | "
                + f"{format_tps(fory)} | {format_tps(protobuf)} | {format_tps(msgpack)} | "
                + f"{winner_cell(throughputs)} |"
            )

    lines.append("")
    lines.append("## Serialized Size (bytes)")
    lines.append("")
    lines.append("| Datatype | Fory | Protobuf | Msgpack |")
    lines.append("| --- | ---: | ---: | ---: |")
    for entry in sorted(sizes, key=lambda item: item.get("dataType", "")):
        lines.append(
            "| "
            + f"{entry.get('dataType', '-')} | "
            + f"{entry.get('fory', '-')} | "
            + f"{entry.get('protobuf', '-')} | "
            + f"{entry.get('msgpack', '-')} |"
        )

    report_path = os.path.join(output_dir, "REPORT.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
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
