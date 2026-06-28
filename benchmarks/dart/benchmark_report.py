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
import math
import os
import socket
import subprocess
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
    apply_benchmark_style,
    add_compact_legend,
    format_markdown_with_prettier,
    format_throughput_tick,
    save_benchmark_figure,
    serializer_offset,
    set_grouped_operation_axis,
    style_throughput_axis,
)

apply_benchmark_style(plt)

COLORS = {
    "fory": "#FF6F01",
    "protobuf": "#55BCC2",
    "json": (0.55, 0.40, 0.45),
}
SERIALIZERS = ["fory", "protobuf", "json"]

DATA_TYPES = [
    "struct",
    "sample",
    "mediacontent",
    "structlist",
    "samplelist",
    "mediacontentlist",
]

DISPLAY_NAMES = {
    "struct": "NumericStruct",
    "sample": "Sample",
    "mediacontent": "MediaContent",
    "structlist": "NumericStructList",
    "samplelist": "SampleList",
    "mediacontentlist": "MediaContentList",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate Dart benchmark report")
    parser.add_argument("--json-file", required=True)
    parser.add_argument("--output-dir", required=True)
    return parser.parse_args()


def load_payload(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def collect_results(payload: dict) -> dict:
    results = defaultdict(lambda: defaultdict(dict))
    for record in payload["results"]:
        results[record["data_type"]][record["operation"]][record["serializer"]] = (
            record["median_ops_per_sec"]
        )
    return results


def format_tick(value: float, _position) -> str:
    return format_throughput_tick(value, _position)


def format_int(value) -> str:
    return f"{int(round(value)):,}" if value is not None and value > 0 else "N/A"


def fastest_entry(values: dict) -> str:
    positive = {
        serializer: value
        for serializer, value in values.items()
        if value is not None and value > 0
    }
    if not positive:
        return "N/A"
    ranked = sorted(positive.items(), key=lambda entry: entry[1], reverse=True)
    best_serializer, best_value = ranked[0]
    if len(ranked) == 1:
        return best_serializer
    second_value = ranked[1][1]
    if math.isclose(best_value, second_value):
        return "tie (1.00x)"
    return f"{best_serializer} ({best_value / second_value:.2f}x)"


def detect_memory_gb() -> str:
    try:
        output = subprocess.check_output(
            ["sysctl", "-n", "hw.memsize"],
            text=True,
        ).strip()
        return f"{int(output) / (1024**3):.2f}"
    except Exception:
        return "Unknown"


def system_info(metadata: dict) -> list[tuple[str, str]]:
    return [
        ("Timestamp", metadata.get("generated_at", "Unknown")),
        ("OS", metadata.get("os_version", metadata.get("os", "Unknown"))),
        ("Host", socket.gethostname() or "Unknown"),
        ("CPU Cores (Logical)", str(metadata.get("cpus", "Unknown"))),
        ("Memory (GB)", detect_memory_gb()),
        ("Dart", metadata.get("dart_version", "Unknown")),
        ("Samples per case", str(metadata.get("samples", "Unknown"))),
        ("Warmup per case (s)", str(metadata.get("warmup_seconds", "Unknown"))),
        ("Duration per case (s)", str(metadata.get("duration_seconds", "Unknown"))),
    ]


def plot_summary_group(ax, results: dict, data_type: str) -> None:
    if data_type not in results:
        ax.set_title(f"{DISPLAY_NAMES[data_type]}\nNo Data")
        ax.axis("off")
        return

    serializers = [
        serializer
        for serializer in SERIALIZERS
        if any(
            results.get(data_type, {}).get(operation, {}).get(serializer, 0.0) > 0
            for operation in ["serialize", "deserialize"]
        )
    ]
    if not serializers:
        ax.set_title(f"{DISPLAY_NAMES[data_type]}\nNo Data")
        ax.axis("off")
        return

    operations = ["serialize", "deserialize"]
    x_positions = GROUP_X
    for index, serializer in enumerate(serializers):
        values = [
            results.get(data_type, {}).get(operation, {}).get(serializer, 0.0)
            for operation in operations
        ]
        offset = serializer_offset(index, len(serializers))
        ax.bar(
            x_positions + offset,
            values,
            width=GROUP_BAR_WIDTH,
            label=serializer,
            color=COLORS[serializer],
            edgecolor=BAR_EDGE_COLOR,
            linewidth=0.8,
        )

    max_value = max(
        results.get(data_type, {}).get(operation, {}).get(serializer, 0.0)
        for operation in operations
        for serializer in serializers
    )
    ax.set_ylim(0, max_value * 1.12)
    ax.set_title(DISPLAY_NAMES[data_type], pad=8)
    set_grouped_operation_axis(ax)
    style_throughput_axis(ax)
    ax.yaxis.set_major_formatter(FuncFormatter(format_tick))
    add_compact_legend(ax)


def save_summary_plot(results: dict, output_dir: str) -> str:
    figure, axes = plt.subplots(2, 3, figsize=(16.5, 9.0))
    figure.suptitle(
        "Dart Serialization Throughput", fontsize=15, fontweight="normal", y=0.955
    )

    for index, (ax, data_type) in enumerate(zip(axes.flat, DATA_TYPES)):
        plot_summary_group(ax, results, data_type)
        if index % 3 == 0:
            ax.set_ylabel("Throughput (ops/sec)", labelpad=10)

    figure.tight_layout(rect=[0.02, 0.02, 0.995, 0.965], w_pad=1.2, h_pad=1.25)

    path = os.path.join(output_dir, "throughput.png")
    save_benchmark_figure(figure, path)
    plt.close(figure)
    return path


def write_report(payload: dict, results: dict, output_dir: str):
    metadata = payload["metadata"]
    report_path = os.path.join(output_dir, "README.md")
    with open(report_path, "w", encoding="utf-8") as handle:
        handle.write("# Fory Dart Benchmark\n\n")
        handle.write(
            "This benchmark compares serialization and deserialization throughput for "
            "Apache Fory, Protocol Buffers, and JSON in Dart.\n\n"
        )
        handle.write("## Throughput Plot\n\n")
        handle.write("![Throughput](throughput.png)\n\n")
        handle.write("## Hardware and Runtime Info\n\n")
        handle.write("| Key | Value |\n")
        handle.write("| --- | --- |\n")
        for key, value in system_info(metadata):
            handle.write(f"| {key} | {value} |\n")

        handle.write("\n## Throughput Results\n\n")
        handle.write(
            "| Datatype | Operation | Fory TPS | Protobuf TPS | JSON TPS | Fastest |\n"
        )
        handle.write("| --- | --- | ---: | ---: | ---: | --- |\n")
        for data_type in DATA_TYPES:
            operations = results.get(data_type, {})
            if not operations:
                continue
            for operation in ["serialize", "deserialize"]:
                values = {
                    serializer: operations.get(operation, {}).get(serializer)
                    for serializer in SERIALIZERS
                }
                handle.write(
                    f"| {DISPLAY_NAMES[data_type]} | {operation.capitalize()} | "
                    + " | ".join(
                        format_int(values[serializer]) for serializer in SERIALIZERS
                    )
                    + f" | {fastest_entry(values)} |\n"
                )

        handle.write("\n## Serialized Size (bytes)\n\n")
        handle.write("| Datatype | Fory | Protobuf | JSON |\n")
        handle.write("| --- | ---: | ---: | ---: |\n")
        for data_type in DATA_TYPES:
            sizes = payload["sizes"].get(data_type)
            if sizes is None:
                continue
            fory_size = sizes.get("fory", "N/A")
            protobuf_size = sizes.get("protobuf", "N/A")
            json_size = sizes.get("json", "N/A")
            handle.write(
                f"| {DISPLAY_NAMES[data_type]} | {fory_size} | {protobuf_size} | {json_size} |\n"
            )

    format_markdown_with_prettier(report_path)


def main() -> None:
    args = parse_args()
    os.makedirs(args.output_dir, exist_ok=True)
    payload = load_payload(args.json_file)
    results = collect_results(payload)
    save_summary_plot(results, args.output_dir)
    write_report(payload, results, args.output_dir)


if __name__ == "__main__":
    main()
