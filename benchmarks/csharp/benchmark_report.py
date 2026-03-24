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
import platform
from collections import defaultdict
from datetime import datetime

import matplotlib.pyplot as plt
import numpy as np

try:
    import psutil

    HAS_PSUTIL = True
except ImportError:
    HAS_PSUTIL = False

COLORS = {
    "fory": "#FF6f01",
    "protobuf": "#55BCC2",
    "msgpack": (0.55, 0.40, 0.45),
}
SERIALIZER_ORDER = ["fory", "protobuf", "msgpack"]
SERIALIZER_LABELS = {
    "fory": "fory",
    "protobuf": "protobuf",
    "msgpack": "msgpack",
}
PREFERRED_DATATYPE_ORDER = [
    "struct",
    "sample",
    "mediacontent",
    "structlist",
    "samplelist",
    "mediacontentlist",
]
PREFERRED_OPERATION_ORDER = ["serialize", "deserialize"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plot C# benchmark stats and generate Markdown report"
    )
    parser.add_argument(
        "--json-file",
        default="benchmark_results.json",
        help="Benchmark JSON output file",
    )
    parser.add_argument(
        "--output-dir",
        default="",
        help="Output directory for plots and report",
    )
    parser.add_argument(
        "--plot-prefix",
        default="",
        help="Image path prefix in Markdown report",
    )
    return parser.parse_args()


def load_results(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def get_system_info(benchmark_data: dict) -> dict:
    info = {
        "OS": benchmark_data.get(
            "OsDescription", f"{platform.system()} {platform.release()}"
        ),
        "OS Architecture": benchmark_data.get("OsArchitecture", "Unknown"),
        "Machine": benchmark_data.get("ProcessArchitecture", platform.machine()),
        "Runtime Version": benchmark_data.get("RuntimeVersion", "Unknown"),
        "Benchmark Date (UTC)": benchmark_data.get("GeneratedAtUtc", "Unknown"),
        "Warmup Seconds": benchmark_data.get("WarmupSeconds", "Unknown"),
        "Duration Seconds": benchmark_data.get("DurationSeconds", "Unknown"),
    }
    processor_count = benchmark_data.get("ProcessorCount")
    if processor_count is not None:
        info["CPU Logical Cores (from benchmark)"] = processor_count

    if HAS_PSUTIL:
        info["CPU Cores (Physical)"] = psutil.cpu_count(logical=False)
        info["CPU Cores (Logical)"] = psutil.cpu_count(logical=True)
        info["Total RAM (GB)"] = round(psutil.virtual_memory().total / (1024**3), 2)
    return info


def format_datatype_label(datatype: str) -> str:
    if datatype.endswith("list"):
        base = datatype[: -len("list")]
        if base == "mediacontent":
            return "MediaContent\nList"
        return f"{base.capitalize()}\nList"
    if datatype == "mediacontent":
        return "MediaContent"
    return datatype.capitalize()


def format_datatype_table_label(datatype: str) -> str:
    if datatype.endswith("list"):
        base = datatype[: -len("list")]
        if base == "mediacontent":
            return "MediaContentList"
        return f"{base.capitalize()}List"
    if datatype == "mediacontent":
        return "MediaContent"
    return datatype.capitalize()


def format_tps_label(tps: float) -> str:
    if tps >= 1e9:
        return f"{tps / 1e9:.2f}G"
    if tps >= 1e6:
        return f"{tps / 1e6:.2f}M"
    if tps >= 1e3:
        return f"{tps / 1e3:.2f}K"
    return f"{tps:.0f}"


def preferred_ordered_values(values, preferred):
    return [item for item in preferred if item in values] + sorted(
        item for item in values if item not in preferred
    )


def process_benchmark_rows(rows):
    raw_timings = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    raw_throughputs = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
    raw_sizes = defaultdict(lambda: defaultdict(list))

    for row in rows:
        serializer = str(row.get("Serializer", "")).lower()
        data_type = str(row.get("DataType", "")).lower()
        operation = str(row.get("Operation", "")).lower()
        if not serializer or not data_type or not operation:
            continue

        avg_ns_raw = row.get("AverageNanoseconds")
        ops_raw = row.get("OperationsPerSecond")
        avg_ns = float(avg_ns_raw) if avg_ns_raw is not None else 0.0
        ops = float(ops_raw) if ops_raw is not None else 0.0

        if avg_ns <= 0.0 and ops > 0.0:
            avg_ns = 1e9 / ops
        if ops <= 0.0 and avg_ns > 0.0:
            ops = 1e9 / avg_ns

        if avg_ns > 0.0:
            raw_timings[data_type][operation][serializer].append(avg_ns)
        if ops > 0.0:
            raw_throughputs[data_type][operation][serializer].append(ops)

        serialized_size = row.get("SerializedSize")
        if serialized_size is not None:
            raw_sizes[data_type][serializer].append(int(round(serialized_size)))

    timings = defaultdict(lambda: defaultdict(dict))
    throughputs = defaultdict(lambda: defaultdict(dict))
    sizes = defaultdict(dict)

    for data_type, op_values in raw_timings.items():
        for operation, serializer_values in op_values.items():
            for serializer, values in serializer_values.items():
                timings[data_type][operation][serializer] = sum(values) / len(values)

    for data_type, op_values in raw_throughputs.items():
        for operation, serializer_values in op_values.items():
            for serializer, values in serializer_values.items():
                throughputs[data_type][operation][serializer] = sum(values) / len(
                    values
                )

    for data_type, serializer_values in raw_sizes.items():
        for serializer, values in serializer_values.items():
            sizes[data_type][serializer] = sum(values) / len(values)

    return timings, throughputs, sizes


def build_coverage(rows):
    cases = set()
    serializers = set()
    datatypes = set()
    operations = set()
    for row in rows:
        serializer = str(row.get("Serializer", "")).lower()
        data_type = str(row.get("DataType", "")).lower()
        operation = str(row.get("Operation", "")).lower()
        if not serializer or not data_type or not operation:
            continue
        cases.add((serializer, data_type, operation))
        serializers.add(serializer)
        datatypes.add(data_type)
        operations.add(operation)

    expected_cases = (
        len(SERIALIZER_ORDER)
        * len(PREFERRED_DATATYPE_ORDER)
        * len(PREFERRED_OPERATION_ORDER)
    )
    return {
        "case_count": len(cases),
        "expected_case_count": expected_cases,
        "serializers": sorted(serializers),
        "datatypes": preferred_ordered_values(
            list(datatypes), PREFERRED_DATATYPE_ORDER
        ),
        "operations": preferred_ordered_values(
            list(operations), PREFERRED_OPERATION_ORDER
        ),
        "is_partial": len(cases) < expected_cases,
    }


def plot_datatype(ax, throughputs: dict, datatype: str, operation: str) -> None:
    if datatype not in throughputs or operation not in throughputs[datatype]:
        ax.set_title(f"{datatype} {operation} - No Data")
        ax.axis("off")
        return

    libs = set(throughputs[datatype][operation].keys())
    lib_order = [lib for lib in SERIALIZER_ORDER if lib in libs]
    if not lib_order:
        ax.set_title(f"{datatype} {operation} - No Supported Serializer Data")
        ax.axis("off")
        return
    throughput = [throughputs[datatype][operation].get(lib, 0) for lib in lib_order]
    colors = [COLORS.get(lib, "#888888") for lib in lib_order]

    x = np.arange(len(lib_order))
    bars = ax.bar(x, throughput, color=colors, width=0.6)

    ax.set_title(f"{operation.capitalize()} Throughput (higher is better)")
    ax.set_xticks(x)
    ax.set_xticklabels([SERIALIZER_LABELS.get(lib, lib) for lib in lib_order])
    ax.set_ylabel("Throughput (ops/sec)")
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))

    for bar, tps_value in zip(bars, throughput):
        height = bar.get_height()
        ax.annotate(
            format_tps_label(tps_value),
            xy=(bar.get_x() + bar.get_width() / 2, height),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=9,
        )


def plot_combined_tps_subplot(ax, throughputs, grouped_datatypes, operation, title):
    if not grouped_datatypes:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    x = np.arange(len(grouped_datatypes))
    available_libs = [
        lib
        for lib in SERIALIZER_ORDER
        if any(throughputs[dt][operation].get(lib, 0) > 0 for dt in grouped_datatypes)
    ]
    if not available_libs:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    width = 0.8 / len(available_libs)
    for idx, lib in enumerate(available_libs):
        tps = [throughputs[dt][operation].get(lib, 0) for dt in grouped_datatypes]
        offset = (idx - (len(available_libs) - 1) / 2) * width
        ax.bar(
            x + offset,
            tps,
            width,
            label=SERIALIZER_LABELS.get(lib, lib),
            color=COLORS.get(lib, "#888888"),
        )

    ax.set_title(title)
    ax.set_xticks(x)
    ax.set_xticklabels([format_datatype_label(dt) for dt in grouped_datatypes])
    ax.legend()
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))


def build_markdown(
    args,
    system_info,
    coverage,
    timings,
    throughputs,
    sizes,
    datatypes,
    operations,
    plot_images,
):
    report_lines = [
        "# C# Benchmark Performance Report\n\n",
        f"_Generated on {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}_\n\n",
        "## How to Generate This Report\n\n",
        "```bash\n",
        "cd benchmarks/csharp\n",
        "dotnet run -c Release --project ./Fory.CSharpBenchmark.csproj -- --output build/benchmark_results.json\n",
        "python3 benchmark_report.py --json-file build/benchmark_results.json --output-dir report\n",
        "```\n\n",
        "## Hardware & OS Info\n\n",
        "| Key | Value |\n",
        "|-----|-------|\n",
    ]

    for key, value in system_info.items():
        report_lines.append(f"| {key} | {value} |\n")

    report_lines.append("\n## Benchmark Coverage\n\n")
    report_lines.append("| Key | Value |\n")
    report_lines.append("|-----|-------|\n")
    report_lines.append(
        f"| Cases in input JSON | {coverage['case_count']} / {coverage['expected_case_count']} |\n"
    )
    report_lines.append(
        f"| Serializers | {', '.join(coverage['serializers']) or 'N/A'} |\n"
    )
    report_lines.append(
        f"| Datatypes | {', '.join(coverage['datatypes']) or 'N/A'} |\n"
    )
    report_lines.append(
        f"| Operations | {', '.join(coverage['operations']) or 'N/A'} |\n"
    )
    if coverage["is_partial"]:
        report_lines.append(
            "\n> Warning: benchmark input is partial; plots/tables only show available cases.\n"
        )

    report_lines.append("\n## Benchmark Plots\n")
    report_lines.append("\nAll class-level plots below show throughput (ops/sec).\n")
    plot_images_sorted = sorted(
        plot_images, key=lambda item: (0 if item[0] == "throughput" else 1, item[0])
    )
    for datatype, img in plot_images_sorted:
        img_filename = os.path.basename(img)
        img_path_report = args.plot_prefix + img_filename
        plot_title = datatype.replace("_", " ").title()
        report_lines.append(f"\n### {plot_title}\n\n")
        report_lines.append(f"![{plot_title}]({img_path_report})\n")

    report_lines.append("\n## Benchmark Results\n\n")
    report_lines.append("### Timing Results (nanoseconds)\n\n")
    report_lines.append(
        "| Datatype | Operation | fory (ns) | protobuf (ns) | msgpack (ns) | Fastest |\n"
    )
    report_lines.append(
        "|----------|-----------|-----------|---------------|--------------|---------|\n"
    )

    for datatype in datatypes:
        for operation in operations:
            times = {
                lib: timings[datatype][operation].get(lib, 0)
                for lib in SERIALIZER_ORDER
            }
            positive_times = {lib: t for lib, t in times.items() if t > 0}
            fastest = "N/A"
            if positive_times:
                fastest_lib = min(positive_times, key=positive_times.get)
                fastest = SERIALIZER_LABELS.get(fastest_lib, fastest_lib)
            report_lines.append(
                "| "
                + f"{format_datatype_table_label(datatype)} | {operation.capitalize()} | "
                + " | ".join(
                    f"{times[lib]:.1f}" if times[lib] > 0 else "N/A"
                    for lib in SERIALIZER_ORDER
                )
                + f" | {fastest} |\n"
            )

    report_lines.append("\n### Throughput Results (ops/sec)\n\n")
    report_lines.append(
        "| Datatype | Operation | fory TPS | protobuf TPS | msgpack TPS | Fastest |\n"
    )
    report_lines.append(
        "|----------|-----------|----------|--------------|-------------|---------|\n"
    )

    for datatype in datatypes:
        for operation in operations:
            tps_values = {
                lib: throughputs[datatype][operation].get(lib, 0)
                for lib in SERIALIZER_ORDER
            }
            positive_tps = {lib: v for lib, v in tps_values.items() if v > 0}
            fastest = "N/A"
            if positive_tps:
                fastest_lib = max(positive_tps, key=positive_tps.get)
                fastest = SERIALIZER_LABELS.get(fastest_lib, fastest_lib)
            report_lines.append(
                "| "
                + f"{format_datatype_table_label(datatype)} | {operation.capitalize()} | "
                + " | ".join(
                    f"{tps_values[lib]:,.0f}" if tps_values[lib] > 0 else "N/A"
                    for lib in SERIALIZER_ORDER
                )
                + f" | {fastest} |\n"
            )

    if sizes:
        report_lines.append("\n### Serialized Data Sizes (bytes)\n\n")
        report_lines.append("| Datatype | fory | protobuf | msgpack |\n")
        report_lines.append("|----------|------|----------|---------|\n")
        for datatype in datatypes:
            row_values = []
            has_value = False
            for serializer in SERIALIZER_ORDER:
                size = sizes[datatype].get(serializer)
                if size is None:
                    row_values.append("N/A")
                else:
                    row_values.append(str(int(round(size))))
                    has_value = True
            if has_value:
                report_lines.append(
                    f"| {format_datatype_table_label(datatype)} | "
                    + " | ".join(row_values)
                    + " |\n"
                )

    return "".join(report_lines)


def main() -> None:
    args = parse_args()
    benchmark_data = load_results(args.json_file)

    if args.output_dir.strip():
        output_dir = args.output_dir
    else:
        output_dir = datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
    os.makedirs(output_dir, exist_ok=True)

    rows = benchmark_data.get("Results", [])
    timings, throughputs, sizes = process_benchmark_rows(rows)
    coverage = build_coverage(rows)

    datatype_candidates = (
        set(timings.keys()) | set(throughputs.keys()) | set(sizes.keys())
    )
    datatypes = preferred_ordered_values(
        list(datatype_candidates), PREFERRED_DATATYPE_ORDER
    )
    operations_present = set()
    for datatype in datatypes:
        operations_present.update(timings[datatype].keys())
        operations_present.update(throughputs[datatype].keys())
    operations = preferred_ordered_values(
        list(operations_present), PREFERRED_OPERATION_ORDER
    )

    plot_images = []
    for datatype in datatypes:
        fig, axes = plt.subplots(1, 2, figsize=(12, 5))
        for index, operation in enumerate(PREFERRED_OPERATION_ORDER):
            plot_datatype(axes[index], throughputs, datatype, operation)
        fig.suptitle(f"{format_datatype_table_label(datatype)} Throughput", fontsize=14)
        fig.tight_layout(rect=[0, 0, 1, 0.95])
        plot_path = os.path.join(output_dir, f"{datatype}.png")
        plt.savefig(plot_path, dpi=150)
        plot_images.append((datatype, plot_path))
        plt.close()

    non_list_datatypes = [dt for dt in datatypes if not dt.endswith("list")]
    list_datatypes = [dt for dt in datatypes if dt.endswith("list")]
    fig, axes = plt.subplots(1, 4, figsize=(28, 6))
    combined_subplots = [
        (
            axes[0],
            non_list_datatypes,
            "serialize",
            "Serialize Throughput (higher is better)",
        ),
        (
            axes[1],
            non_list_datatypes,
            "deserialize",
            "Deserialize Throughput (higher is better)",
        ),
        (
            axes[2],
            list_datatypes,
            "serialize",
            "Serialize Throughput (*List, higher is better)",
        ),
        (
            axes[3],
            list_datatypes,
            "deserialize",
            "Deserialize Throughput (*List, higher is better)",
        ),
    ]
    for ax, grouped_datatypes, operation, title in combined_subplots:
        plot_combined_tps_subplot(ax, throughputs, grouped_datatypes, operation, title)
    # Keep the y-label style consistent with single-datatype plots (axis label,
    # not figure-level label) and avoid overlap with y-ticks.
    axes[0].set_ylabel("Throughput (ops/sec)")
    fig.tight_layout()
    throughput_path = os.path.join(output_dir, "throughput.png")
    plt.savefig(throughput_path, dpi=150)
    plot_images.append(("throughput", throughput_path))
    plt.close()

    report = build_markdown(
        args=args,
        system_info=get_system_info(benchmark_data),
        coverage=coverage,
        timings=timings,
        throughputs=throughputs,
        sizes=sizes,
        datatypes=datatypes,
        operations=operations,
        plot_images=plot_images,
    )
    legacy_report_path = os.path.join(output_dir, "REPORT.md")
    if os.path.exists(legacy_report_path):
        os.remove(legacy_report_path)
    report_path = os.path.join(output_dir, "README.md")
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(report)

    if coverage["is_partial"]:
        print(
            "Warning: partial benchmark input detected "
            f"({coverage['case_count']}/{coverage['expected_case_count']} cases)."
        )
    print(f"Plots saved in: {output_dir}")
    print(f"Markdown report generated at: {report_path}")


if __name__ == "__main__":
    main()
