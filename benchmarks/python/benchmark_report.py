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

"""Generate plots and Markdown report from Python benchmark JSON results."""

from __future__ import annotations

import argparse
import json
import os
import platform
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Dict

import matplotlib.pyplot as plt
import numpy as np

try:
    import psutil

    HAS_PSUTIL = True
except ImportError:
    HAS_PSUTIL = False


COLORS = {
    "fory": "#FF6F01",
    "pickle": "#4C78A8",
    "protobuf": "#55BCC2",
}
SERIALIZER_ORDER = ["fory", "pickle", "protobuf"]
SERIALIZER_LABELS = {
    "fory": "fory",
    "pickle": "pickle",
    "protobuf": "protobuf",
}
DATATYPE_ORDER = [
    "struct",
    "sample",
    "mediacontent",
    "structlist",
    "samplelist",
    "mediacontentlist",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate markdown report and plots for Python benchmark suite"
    )
    parser.add_argument(
        "--json-file",
        default="results/benchmark_results.json",
        help="Benchmark JSON file produced by benchmark.py",
    )
    parser.add_argument(
        "--output-dir",
        default="results/report",
        help="Output directory for report and plots",
    )
    parser.add_argument(
        "--plot-prefix",
        default="",
        help="Optional image path prefix used in markdown",
    )
    return parser.parse_args()


def load_json(path: Path) -> Dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def get_system_info() -> Dict[str, str]:
    info = {
        "OS": f"{platform.system()} {platform.release()}",
        "Machine": platform.machine(),
        "Processor": platform.processor() or "Unknown",
        "Python": platform.python_version(),
    }
    if HAS_PSUTIL:
        info["CPU Cores (Physical)"] = str(psutil.cpu_count(logical=False))
        info["CPU Cores (Logical)"] = str(psutil.cpu_count(logical=True))
        info["Total RAM (GB)"] = str(
            round(psutil.virtual_memory().total / (1024**3), 2)
        )
    return info


def format_datatype_label(datatype: str) -> str:
    mapping = {
        "struct": "Struct",
        "sample": "Sample",
        "mediacontent": "MediaContent",
        "structlist": "Struct\nList",
        "samplelist": "Sample\nList",
        "mediacontentlist": "MediaContent\nList",
    }
    return mapping.get(datatype, datatype)


def format_datatype_table_label(datatype: str) -> str:
    mapping = {
        "struct": "Struct",
        "sample": "Sample",
        "mediacontent": "MediaContent",
        "structlist": "StructList",
        "samplelist": "SampleList",
        "mediacontentlist": "MediaContentList",
    }
    return mapping.get(datatype, datatype)


def format_tps_label(tps: float) -> str:
    if tps >= 1e9:
        return f"{tps / 1e9:.2f}G"
    if tps >= 1e6:
        return f"{tps / 1e6:.2f}M"
    if tps >= 1e3:
        return f"{tps / 1e3:.2f}K"
    return f"{tps:.0f}"


def build_benchmark_matrix(benchmarks):
    data = defaultdict(lambda: defaultdict(dict))
    for bench in benchmarks:
        datatype = bench["datatype"]
        operation = bench["operation"]
        serializer = bench["serializer"]
        data[datatype][operation][serializer] = bench["mean_ns"]
    return data


def plot_datatype(ax, data, datatype: str, operation: str):
    if datatype not in data or operation not in data[datatype]:
        ax.set_title(f"{format_datatype_table_label(datatype)} {operation}: no data")
        ax.axis("off")
        return

    libs = [
        lib
        for lib in SERIALIZER_ORDER
        if data[datatype][operation].get(lib, 0) and data[datatype][operation][lib] > 0
    ]
    if not libs:
        ax.set_title(f"{format_datatype_table_label(datatype)} {operation}: no data")
        ax.axis("off")
        return

    times = [data[datatype][operation][lib] for lib in libs]
    throughput = [1e9 / t if t > 0 else 0 for t in times]

    x = np.arange(len(libs))
    bars = ax.bar(
        x,
        throughput,
        color=[COLORS.get(lib, "#999999") for lib in libs],
        width=0.6,
    )

    ax.set_xticks(x)
    ax.set_xticklabels([SERIALIZER_LABELS.get(lib, lib) for lib in libs])
    ax.set_ylabel("Throughput (ops/sec)")
    ax.set_title(f"{operation.capitalize()} Throughput (higher is better)")
    ax.grid(True, axis="y", linestyle="--", alpha=0.45)
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))

    for bar, val in zip(bars, throughput):
        ax.annotate(
            format_tps_label(val),
            xy=(bar.get_x() + bar.get_width() / 2, bar.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=9,
        )


def plot_combined_subplot(ax, data, datatypes, operation: str, title: str):
    available_dts = [dt for dt in datatypes if operation in data.get(dt, {})]
    if not available_dts:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    x = np.arange(len(available_dts))
    available_libs = [
        lib
        for lib in SERIALIZER_ORDER
        if any(
            data.get(dt, {}).get(operation, {}).get(lib, 0) > 0 for dt in available_dts
        )
    ]
    if not available_libs:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    width = 0.8 / len(available_libs)
    for idx, lib in enumerate(available_libs):
        times = [
            data.get(dt, {}).get(operation, {}).get(lib, 0) for dt in available_dts
        ]
        tps = [1e9 / val if val > 0 else 0 for val in times]
        offset = (idx - (len(available_libs) - 1) / 2) * width
        ax.bar(
            x + offset,
            tps,
            width,
            label=SERIALIZER_LABELS.get(lib, lib),
            color=COLORS.get(lib, "#999999"),
        )

    ax.set_title(title)
    ax.set_xticks(x)
    ax.set_xticklabels([format_datatype_label(dt) for dt in available_dts])
    ax.grid(True, axis="y", linestyle="--", alpha=0.45)
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))
    ax.legend()


def generate_plots(data, output_dir: Path):
    plot_images = []
    operations = ["serialize", "deserialize"]

    datatypes = [dt for dt in DATATYPE_ORDER if dt in data]
    for datatype in datatypes:
        fig, axes = plt.subplots(1, 2, figsize=(12, 5))
        for idx, operation in enumerate(operations):
            plot_datatype(axes[idx], data, datatype, operation)
        fig.suptitle(f"{format_datatype_table_label(datatype)} Throughput", fontsize=14)
        fig.tight_layout(rect=[0, 0, 1, 0.95])

        path = output_dir / f"{datatype}.png"
        plt.savefig(path, dpi=150)
        plt.close()
        plot_images.append((datatype, path))

    non_list_datatypes = [dt for dt in datatypes if not dt.endswith("list")]
    list_datatypes = [dt for dt in datatypes if dt.endswith("list")]

    fig, axes = plt.subplots(1, 4, figsize=(28, 6))
    fig.supylabel("Throughput (ops/sec)")

    plot_combined_subplot(
        axes[0], data, non_list_datatypes, "serialize", "Serialize Throughput"
    )
    plot_combined_subplot(
        axes[1], data, non_list_datatypes, "deserialize", "Deserialize Throughput"
    )
    plot_combined_subplot(
        axes[2], data, list_datatypes, "serialize", "Serialize Throughput (*List)"
    )
    plot_combined_subplot(
        axes[3], data, list_datatypes, "deserialize", "Deserialize Throughput (*List)"
    )

    fig.tight_layout()
    throughput_path = output_dir / "throughput.png"
    plt.savefig(throughput_path, dpi=150)
    plt.close()
    plot_images.append(("throughput", throughput_path))

    return plot_images


def generate_markdown_report(
    raw, data, sizes, plot_images, output_dir: Path, plot_prefix: str
):
    context = raw.get("context", {})
    system_info = get_system_info()

    if context.get("python_implementation"):
        system_info["Python Implementation"] = context["python_implementation"]
    if context.get("platform"):
        system_info["Benchmark Platform"] = context["platform"]

    datatypes = [dt for dt in DATATYPE_ORDER if dt in data]
    operations = ["serialize", "deserialize"]

    md = [
        "# Python Benchmark Performance Report\n\n",
        f"_Generated on {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}_\n\n",
        "## How to Generate This Report\n\n",
        "```bash\n",
        "cd benchmarks/python\n",
        "./run.sh\n",
        "```\n\n",
        "## Hardware & OS Info\n\n",
        "| Key | Value |\n",
        "|-----|-------|\n",
    ]

    for key, value in system_info.items():
        md.append(f"| {key} | {value} |\n")

    md.append("\n## Benchmark Configuration\n\n")
    md.append("| Key | Value |\n")
    md.append("|-----|-------|\n")
    for key in ["warmup", "iterations", "repeat", "number", "list_size"]:
        if key in context:
            md.append(f"| {key} | {context[key]} |\n")

    md.append("\n## Benchmark Plots\n")
    md.append("\nAll plots show throughput (ops/sec); higher is better.\n")

    plot_images_sorted = sorted(
        plot_images, key=lambda item: (0 if item[0] == "throughput" else 1, item[0])
    )
    for datatype, image_path in plot_images_sorted:
        image_name = os.path.basename(image_path)
        image_ref = f"{plot_prefix}{image_name}"
        plot_title = datatype.replace("_", " ").title()
        md.append(f"\n### {plot_title}\n\n")
        md.append(f"![{plot_title}]({image_ref})\n")

    md.append("\n## Benchmark Results\n\n")
    md.append("### Timing Results (nanoseconds)\n\n")
    md.append(
        "| Datatype | Operation | fory (ns) | pickle (ns) | protobuf (ns) | Fastest |\n"
    )
    md.append(
        "|----------|-----------|-----------|-------------|---------------|---------|\n"
    )

    for datatype in datatypes:
        for operation in operations:
            times = {
                lib: data.get(datatype, {}).get(operation, {}).get(lib, 0)
                for lib in SERIALIZER_ORDER
            }
            valid = {lib: val for lib, val in times.items() if val > 0}
            fastest = min(valid, key=valid.get) if valid else "N/A"
            md.append(
                "| "
                + f"{format_datatype_table_label(datatype)} | {operation.capitalize()} | "
                + " | ".join(
                    f"{times[lib]:.1f}" if times[lib] > 0 else "N/A"
                    for lib in SERIALIZER_ORDER
                )
                + f" | {SERIALIZER_LABELS.get(fastest, fastest)} |\n"
            )

    md.append("\n### Throughput Results (ops/sec)\n\n")
    md.append(
        "| Datatype | Operation | fory TPS | pickle TPS | protobuf TPS | Fastest |\n"
    )
    md.append(
        "|----------|-----------|----------|------------|--------------|---------|\n"
    )

    for datatype in datatypes:
        for operation in operations:
            times = {
                lib: data.get(datatype, {}).get(operation, {}).get(lib, 0)
                for lib in SERIALIZER_ORDER
            }
            tps = {lib: (1e9 / val if val > 0 else 0) for lib, val in times.items()}
            valid_tps = {lib: val for lib, val in tps.items() if val > 0}
            fastest = max(valid_tps, key=valid_tps.get) if valid_tps else "N/A"
            md.append(
                "| "
                + f"{format_datatype_table_label(datatype)} | {operation.capitalize()} | "
                + " | ".join(
                    f"{tps[lib]:,.0f}" if tps[lib] > 0 else "N/A"
                    for lib in SERIALIZER_ORDER
                )
                + f" | {SERIALIZER_LABELS.get(fastest, fastest)} |\n"
            )

    if sizes:
        md.append("\n### Serialized Data Sizes (bytes)\n\n")
        md.append("| Datatype | fory | pickle | protobuf |\n")
        md.append("|----------|------|--------|----------|\n")

        for datatype in datatypes:
            datatype_sizes = sizes.get(datatype, {})
            row = []
            for lib in SERIALIZER_ORDER:
                value = datatype_sizes.get(lib, -1)
                row.append(str(value) if value is not None and value >= 0 else "N/A")
            md.append(
                f"| {format_datatype_table_label(datatype)} | "
                + " | ".join(row)
                + " |\n"
            )

    report_path = output_dir / "README.md"
    report_path.write_text("".join(md), encoding="utf-8")
    return report_path


def main() -> int:
    args = parse_args()

    json_file = Path(args.json_file)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    raw = load_json(json_file)
    benchmarks = raw.get("benchmarks", [])
    sizes = raw.get("sizes", {})

    data = build_benchmark_matrix(benchmarks)
    plot_images = generate_plots(data, output_dir)

    report_path = generate_markdown_report(
        raw,
        data,
        sizes,
        plot_images,
        output_dir,
        args.plot_prefix,
    )

    print(f"Plots saved in: {output_dir}")
    print(f"Markdown report generated at: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
