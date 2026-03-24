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

"""
Benchmark report generator for Go Fory benchmarks.
Generates plots and markdown reports from benchmark results.
"""

import json
import os
import platform
import re
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

try:
    import matplotlib.pyplot as plt

    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False
    print("Warning: matplotlib not installed. Skipping plot generation.")


# Color scheme (matching C++ benchmark)
COLORS = {
    "fory": "#FF6f01",  # Orange
    "protobuf": "#55BCC2",  # Teal
    "msgpack": "#9B59B6",  # Purple
}
DATATYPES = [
    "struct",
    "structlist",
    "sample",
    "samplelist",
    "mediacontent",
    "mediacontentlist",
]
OPERATIONS = ["serialize", "deserialize"]
SERIALIZERS = ["fory", "protobuf", "msgpack"]


def parse_benchmark_txt(filepath):
    """Parse Go benchmark text output format."""
    results = defaultdict(lambda: defaultdict(dict))

    with open(filepath, "r") as f:
        for line in f:
            # Match lines like: BenchmarkFory_Struct_Serialize-10    1234567    789.0 ns/op
            match = re.match(
                r"Benchmark(\w+)_(\w+)_(Serialize|Deserialize)-\d+\s+\d+\s+([\d.]+)\s+ns/op",
                line,
            )
            if match:
                serializer = match.group(1).lower()
                datatype = match.group(2).lower()
                operation = match.group(3).lower()
                ns_per_op = float(match.group(4))

                results[datatype][operation][serializer] = ns_per_op

    return results


def parse_benchmark_json(filepath):
    """Parse Go benchmark JSON output format."""
    results = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))

    with open(filepath, "r") as f:
        for line in f:
            try:
                data = json.loads(line)
                if data.get("Action") == "output" and "Benchmark" in data.get(
                    "Output", ""
                ):
                    output = data["Output"]
                    # Match benchmark result lines
                    match = re.match(
                        r"Benchmark(\w+)_(\w+)_(Serialize|Deserialize)-\d+\s+\d+\s+([\d.]+)\s+ns/op",
                        output,
                    )
                    if match:
                        serializer = match.group(1).lower()
                        datatype = match.group(2).lower()
                        operation = match.group(3).lower()
                        ns_per_op = float(match.group(4))

                        results[datatype][operation][serializer].append(ns_per_op)
            except json.JSONDecodeError:
                continue

    # Average multiple runs
    final_results = defaultdict(lambda: defaultdict(dict))
    for datatype, ops in results.items():
        for op, serializers in ops.items():
            for serializer, times in serializers.items():
                if times:
                    final_results[datatype][op][serializer] = sum(times) / len(times)

    return final_results


def parse_serialized_sizes(text):
    sizes = {}
    current = None
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("="):
            continue
        if line.endswith(":") and not line.startswith(
            ("Fory:", "Protobuf:", "Msgpack:")
        ):
            current = line.rstrip(":")
            sizes[current] = {}
            continue
        if current is None:
            continue
        match = re.match(r"^(Fory|Protobuf|Msgpack):\s+(\d+)\s+bytes$", line)
        if match:
            serializer = match.group(1).lower()
            size = int(match.group(2))
            sizes[current][serializer] = size
    return sizes


def load_serialized_sizes(output_dir):
    size_files = [
        Path(output_dir) / "serialized_sizes.txt",
        Path(output_dir) / "benchmark_results.txt",
    ]
    for path in size_files:
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if "Serialized Sizes (bytes):" in text:
            return parse_serialized_sizes(text)
    return {}


def generate_plots(results, output_dir):
    """Generate comparison plots for each data type."""
    if not HAS_MATPLOTLIB:
        return

    for datatype in DATATYPES:
        if datatype not in results:
            continue

        fig, axes = plt.subplots(1, 2, figsize=(14, 6))
        fig.suptitle(
            f"{datatype.title()} Serialization Benchmark",
            fontsize=14,
            fontweight="bold",
        )

        for idx, op in enumerate(OPERATIONS):
            ax = axes[idx]

            if op not in results[datatype]:
                continue

            data = results[datatype][op]
            available_serializers = [s for s in SERIALIZERS if s in data]

            if not available_serializers:
                continue

            # Convert ns to ops/sec
            ops_per_sec = [
                1e9 / data[s] if s in data else 0 for s in available_serializers
            ]
            colors = [COLORS.get(s, "#888888") for s in available_serializers]

            bars = ax.bar(available_serializers, ops_per_sec, color=colors)
            ax.set_ylabel("Operations/sec")
            ax.set_title(f"{op.title()}")

            # Add value labels on bars
            for bar, val in zip(bars, ops_per_sec):
                height = bar.get_height()
                ax.annotate(
                    f"{val / 1e6:.2f}M" if val >= 1e6 else f"{val / 1e3:.0f}K",
                    xy=(bar.get_x() + bar.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha="center",
                    va="bottom",
                    fontsize=9,
                )

            # Add speedup annotations
            if "fory" in data:
                fory_val = 1e9 / data["fory"]
                speedup_lines = []
                for s in available_serializers:
                    if s != "fory" and s in data:
                        other_val = 1e9 / data[s]
                        speedup = fory_val / other_val
                        if speedup > 1:
                            speedup_lines.append(
                                f"Fory {speedup:.1f}x faster than {s.title()}"
                            )

                if speedup_lines:
                    ax.text(
                        0.98,
                        0.98,
                        "\n".join(speedup_lines),
                        transform=ax.transAxes,
                        ha="right",
                        va="top",
                        fontsize=9,
                        color="green",
                        fontweight="bold",
                        bbox=dict(
                            boxstyle="round,pad=0.25",
                            facecolor="white",
                            edgecolor="none",
                            alpha=0.85,
                        ),
                    )

        plt.tight_layout()
        plt.savefig(
            os.path.join(output_dir, f"benchmark_{datatype}.png"),
            dpi=150,
            bbox_inches="tight",
        )
        plt.close()


def generate_combined_plot(results, output_dir):
    """Generate a combined plot showing all benchmarks."""
    if not HAS_MATPLOTLIB:
        return

    datatypes = DATATYPES
    operations = OPERATIONS
    serializers = SERIALIZERS

    cols = len(datatypes)
    fig_width = max(12, 3.5 * cols)
    fig, axes = plt.subplots(len(operations), cols, figsize=(fig_width, 10))
    if cols == 1:
        axes = [[axes[row]] for row in range(len(operations))]
    fig.suptitle(
        "Go Serialization Benchmark: Fory vs Protobuf vs Msgpack",
        fontsize=14,
        fontweight="bold",
    )

    for row, op in enumerate(operations):
        for col, datatype in enumerate(datatypes):
            ax = axes[row][col]

            if datatype not in results or op not in results[datatype]:
                ax.text(
                    0.5,
                    0.5,
                    "No data",
                    ha="center",
                    va="center",
                    transform=ax.transAxes,
                )
                continue

            data = results[datatype][op]
            available_serializers = [s for s in serializers if s in data]

            if not available_serializers:
                continue

            ops_per_sec = [
                1e9 / data[s] if s in data else 0 for s in available_serializers
            ]
            colors = [COLORS.get(s, "#888888") for s in available_serializers]

            bars = ax.bar(available_serializers, ops_per_sec, color=colors)
            ax.set_title(f"{datatype.title()} - {op.title()}")
            ax.set_ylabel("ops/sec")

            # Add value labels
            for bar, val in zip(bars, ops_per_sec):
                height = bar.get_height()
                label = f"{val / 1e6:.2f}M" if val >= 1e6 else f"{val / 1e3:.0f}K"
                ax.annotate(
                    label,
                    xy=(bar.get_x() + bar.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha="center",
                    va="bottom",
                    fontsize=8,
                )

    plt.tight_layout()
    plt.savefig(
        os.path.join(output_dir, "benchmark_combined.png"), dpi=150, bbox_inches="tight"
    )
    plt.close()


def generate_markdown_report(results, output_dir):
    """Generate markdown report."""
    datatypes = DATATYPES
    operations = OPERATIONS

    report = []
    report.append("# Go Serialization Benchmark Report\n")
    report.append(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

    # System info
    report.append("## System Information\n")
    report.append(f"- **OS**: {platform.system()} {platform.release()}")
    report.append(f"- **Architecture**: {platform.machine()}")
    report.append(f"- **Python**: {platform.python_version()}")
    report.append("")

    # Summary table
    report.append("## Performance Summary\n")
    report.append(
        "| Data Type | Operation | Fory (ops/s) | Protobuf (ops/s) | Msgpack (ops/s) | Fory vs PB | Fory vs MP |"
    )
    report.append(
        "|-----------|-----------|--------------|------------------|-----------------|------------|------------|"
    )

    for datatype in datatypes:
        if datatype not in results:
            continue
        for op in operations:
            if op not in results[datatype]:
                continue

            data = results[datatype][op]
            fory_ops = 1e9 / data.get("fory", float("inf")) if "fory" in data else 0
            pb_ops = (
                1e9 / data.get("protobuf", float("inf")) if "protobuf" in data else 0
            )
            mp_ops = 1e9 / data.get("msgpack", float("inf")) if "msgpack" in data else 0

            fory_str = (
                f"{fory_ops / 1e6:.2f}M"
                if fory_ops >= 1e6
                else f"{fory_ops / 1e3:.0f}K"
            )
            pb_str = f"{pb_ops / 1e6:.2f}M" if pb_ops >= 1e6 else f"{pb_ops / 1e3:.0f}K"
            mp_str = f"{mp_ops / 1e6:.2f}M" if mp_ops >= 1e6 else f"{mp_ops / 1e3:.0f}K"

            fory_vs_pb = f"{fory_ops / pb_ops:.2f}x" if pb_ops > 0 else "N/A"
            fory_vs_mp = f"{fory_ops / mp_ops:.2f}x" if mp_ops > 0 else "N/A"

            report.append(
                f"| {datatype.title()} | {op.title()} | {fory_str} | {pb_str} | {mp_str} | {fory_vs_pb} | {fory_vs_mp} |"
            )

    report.append("")

    # Timing details
    report.append("## Detailed Timing (ns/op)\n")
    report.append("| Data Type | Operation | Fory | Protobuf | Msgpack |")
    report.append("|-----------|-----------|------|----------|---------|")

    for datatype in datatypes:
        if datatype not in results:
            continue
        for op in operations:
            if op not in results[datatype]:
                continue

            data = results[datatype][op]
            fory_ns = f"{data.get('fory', 0):.1f}"
            pb_ns = f"{data.get('protobuf', 0):.1f}"
            mp_ns = f"{data.get('msgpack', 0):.1f}"

            report.append(
                f"| {datatype.title()} | {op.title()} | {fory_ns} | {pb_ns} | {mp_ns} |"
            )

    report.append("")

    # Serialized size section
    sizes = load_serialized_sizes(output_dir)
    report.append("### Serialized Data Sizes (bytes)\n")
    if sizes:
        report.append("| Data Type | Fory | Protobuf | Msgpack |")
        report.append("|-----------|------|----------|---------|")
        name_map = {
            "NumericStruct": "Struct",
            "Sample": "Sample",
            "MediaContent": "MediaContent",
            "StructList": "StructList",
            "SampleList": "SampleList",
            "MediaContentList": "MediaContentList",
        }
        ordered = [
            "NumericStruct",
            "Sample",
            "MediaContent",
            "StructList",
            "SampleList",
            "MediaContentList",
        ]
        for key in ordered:
            if key not in sizes:
                continue
            entry = sizes[key]
            report.append(
                f"| {name_map.get(key, key)} | {entry.get('fory', 'N/A')} | {entry.get('protobuf', 'N/A')} | {entry.get('msgpack', 'N/A')} |"
            )
    else:
        report.append("No serialized size data found.\n")

    # Plots section
    if HAS_MATPLOTLIB:
        report.append("## Performance Charts\n")
        report.append("### Combined Overview")
        report.append("![Combined Benchmark](benchmark_combined.png)\n")
        for datatype in datatypes:
            if datatype in results:
                report.append(f"### {datatype.title()}")
                report.append(
                    f"![{datatype.title()} Benchmark](benchmark_{datatype}.png)\n"
                )

    # Write report
    report_path = os.path.join(output_dir, "benchmark_report.md")
    with open(report_path, "w") as f:
        f.write("\n".join(report))

    print(f"Report generated: {report_path}")


def main():
    # Accept output directory as argument, default to ./results
    if len(sys.argv) > 1:
        output_dir = Path(sys.argv[1])
    else:
        output_dir = Path(__file__).parent / "results"

    # Try to parse results
    txt_path = output_dir / "benchmark_results.txt"
    json_path = output_dir / "benchmark_results.json"

    results = None

    if txt_path.exists():
        print(f"Parsing {txt_path}...")
        results = parse_benchmark_txt(txt_path)
    elif json_path.exists():
        print(f"Parsing {json_path}...")
        results = parse_benchmark_json(json_path)
    else:
        print("Error: No benchmark results found.")
        print("Run ./run.sh first to generate benchmark results.")
        sys.exit(1)

    if not results:
        print("Error: Could not parse benchmark results.")
        sys.exit(1)

    print("Parsed results for data types:", list(results.keys()))

    # Generate outputs
    generate_plots(results, output_dir)
    generate_combined_plot(results, output_dir)
    generate_markdown_report(results, output_dir)

    print("Done!")


if __name__ == "__main__":
    main()
