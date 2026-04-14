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
import shutil
import subprocess
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
    "fory": "#FF6F01",
    "protobuf": "#55BCC2",
    "json": "#7A7A7A",
}
SERIALIZER_ORDER = ["fory", "protobuf", "json"]
SERIALIZER_LABELS = {
    "fory": "fory",
    "protobuf": "protobuf",
    "json": "json",
}
DATATYPE_ORDER = [
    "struct",
    "sample",
    "mediacontent",
    "structlist",
    "samplelist",
    "mediacontentlist",
]

parser = argparse.ArgumentParser(
    description="Generate plots and Markdown report for JavaScript benchmark results"
)
parser.add_argument(
    "--json-file", default="benchmark_results.json", help="Benchmark JSON output file"
)
parser.add_argument(
    "--output-dir",
    default="",
    help="Output directory for plots and report",
)
parser.add_argument(
    "--plot-prefix", default="", help="Image path prefix in Markdown report"
)
args = parser.parse_args()

output_dir = args.output_dir.strip() or datetime.now().strftime("%Y_%m_%d_%H_%M_%S")
os.makedirs(output_dir, exist_ok=True)


def get_system_info():
    info = {
        "OS": f"{platform.system()} {platform.release()}",
        "Machine": platform.machine(),
        "Processor": platform.processor() or "Unknown",
    }
    if HAS_PSUTIL:
        info["CPU Cores (Physical)"] = psutil.cpu_count(logical=False)
        info["CPU Cores (Logical)"] = psutil.cpu_count(logical=True)
        info["Total RAM (GB)"] = round(psutil.virtual_memory().total / (1024**3), 2)
    return info


def parse_benchmark_name(name):
    if name.startswith("BM_"):
        name = name[3:]
    parts = name.split("_")
    if len(parts) >= 3:
        return parts[0].lower(), parts[1].lower(), parts[2].lower()
    return None, None, None


def format_datatype_label(datatype):
    if datatype.endswith("list"):
        base = datatype[: -len("list")]
        if base == "mediacontent":
            return "MediaContent\nList"
        return f"{base.capitalize()}\nList"
    if datatype == "mediacontent":
        return "MediaContent"
    return datatype.capitalize()


def format_datatype_table_label(datatype):
    if datatype.endswith("list"):
        base = datatype[: -len("list")]
        if base == "mediacontent":
            return "MediaContentList"
        return f"{base.capitalize()}List"
    if datatype == "mediacontent":
        return "MediaContent"
    return datatype.capitalize()


with open(args.json_file, "r", encoding="utf-8") as handle:
    benchmark_data = json.load(handle)

data = defaultdict(lambda: defaultdict(dict))
sizes = {}

for bench in benchmark_data.get("benchmarks", []):
    name = bench.get("name", "")
    if "PrintSerializedSizes" in name:
        for key, value in bench.items():
            if key.endswith("_size"):
                sizes[key] = int(value)
        continue
    serializer, datatype, operation = parse_benchmark_name(name)
    if serializer and datatype and operation:
        time_ns = bench.get("real_time", bench.get("cpu_time", 0))
        data[datatype][operation][serializer] = time_ns

system_info = get_system_info()
context = benchmark_data.get("context", {})
if context.get("date"):
    system_info["Benchmark Date"] = context["date"]
if context.get("num_cpus"):
    system_info["CPU Cores (from benchmark)"] = context["num_cpus"]
if context.get("node_version"):
    system_info["Node.js"] = context["node_version"]
if context.get("v8_version"):
    system_info["V8"] = context["v8_version"]


def format_tps_label(tps):
    if tps >= 1e9:
        return f"{tps / 1e9:.2f}G"
    if tps >= 1e6:
        return f"{tps / 1e6:.2f}M"
    if tps >= 1e3:
        return f"{tps / 1e3:.2f}K"
    return f"{tps:.0f}"


def plot_datatype(ax, datatype, operation):
    if datatype not in data or operation not in data[datatype]:
        ax.set_title(f"{datatype} {operation} - No Data")
        ax.axis("off")
        return

    libs = [lib for lib in SERIALIZER_ORDER if lib in data[datatype][operation]]
    if not libs:
        ax.set_title(f"{datatype} {operation} - No Data")
        ax.axis("off")
        return

    times = [data[datatype][operation].get(lib, 0) for lib in libs]
    throughput = [1e9 / value if value > 0 else 0 for value in times]
    colors = [COLORS[lib] for lib in libs]

    x = np.arange(len(libs))
    bars = ax.bar(x, throughput, color=colors, width=0.6)
    ax.set_title(f"{operation.capitalize()} Throughput (higher is better)")
    ax.set_xticks(x)
    ax.set_xticklabels([SERIALIZER_LABELS[lib] for lib in libs])
    ax.set_ylabel("Throughput (ops/sec)")
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))

    for bar, tps in zip(bars, throughput):
        ax.annotate(
            format_tps_label(tps),
            xy=(bar.get_x() + bar.get_width() / 2, bar.get_height()),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=9,
        )


plot_images = []
datatypes = [datatype for datatype in DATATYPE_ORDER if datatype in data]
operations = ["serialize", "deserialize"]

for datatype in datatypes:
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    for i, operation in enumerate(operations):
        plot_datatype(axes[i], datatype, operation)
    fig.suptitle(f"{format_datatype_table_label(datatype)} Throughput", fontsize=14)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    plot_path = os.path.join(output_dir, f"{datatype}.png")
    plt.savefig(plot_path, dpi=150)
    plot_images.append((datatype, plot_path))
    plt.close()


def plot_combined_tps_subplot(ax, grouped_datatypes, operation, title):
    if not grouped_datatypes:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    available_libs = [
        lib
        for lib in SERIALIZER_ORDER
        if any(
            data[datatype][operation].get(lib, 0) > 0 for datatype in grouped_datatypes
        )
    ]
    if not available_libs:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    x = np.arange(len(grouped_datatypes))
    width = 0.8 / len(available_libs)
    for idx, lib in enumerate(available_libs):
        times = [
            data[datatype][operation].get(lib, 0) for datatype in grouped_datatypes
        ]
        throughput = [1e9 / value if value > 0 else 0 for value in times]
        offset = (idx - (len(available_libs) - 1) / 2) * width
        ax.bar(
            x + offset,
            throughput,
            width,
            label=SERIALIZER_LABELS[lib],
            color=COLORS[lib],
        )

    ax.set_title(title)
    ax.set_xticks(x)
    ax.set_xticklabels(
        [format_datatype_label(datatype) for datatype in grouped_datatypes]
    )
    ax.legend()
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))


non_list_datatypes = [
    datatype for datatype in datatypes if not datatype.endswith("list")
]
list_datatypes = [datatype for datatype in datatypes if datatype.endswith("list")]

fig, axes = plt.subplots(1, 4, figsize=(28, 6))
fig.supylabel("Throughput (ops/sec)")

combined_subplots = [
    (axes[0], non_list_datatypes, "serialize", "Serialize Throughput"),
    (axes[1], non_list_datatypes, "deserialize", "Deserialize Throughput"),
    (axes[2], list_datatypes, "serialize", "Serialize Throughput (*List)"),
    (axes[3], list_datatypes, "deserialize", "Deserialize Throughput (*List)"),
]

for ax, grouped_datatypes, operation, title in combined_subplots:
    plot_combined_tps_subplot(
        ax, grouped_datatypes, operation, f"{title} (higher is better)"
    )

fig.tight_layout()
combined_plot_path = os.path.join(output_dir, "throughput.png")
plt.savefig(combined_plot_path, dpi=150)
plot_images.append(("throughput", combined_plot_path))
plt.close()

md_report = [
    "# JavaScript Benchmark Performance Report\n\n",
    f"_Generated on {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}_\n\n",
    "## How to Generate This Report\n\n",
    "```bash\n",
    "cd benchmarks/javascript\n",
    "./run.sh\n",
    "```\n\n",
    "## Hardware & OS Info\n\n",
    "| Key | Value |\n",
    "|-----|-------|\n",
]

for key, value in system_info.items():
    md_report.append(f"| {key} | {value} |\n")

md_report.append("\n## Benchmark Plots\n")
md_report.append("\nAll class-level plots below show throughput (ops/sec).\n")
for datatype, image in sorted(
    plot_images, key=lambda item: (0 if item[0] == "throughput" else 1, item[0])
):
    image_name = os.path.basename(image)
    image_path = args.plot_prefix + image_name
    title = (
        "Throughput"
        if datatype == "throughput"
        else format_datatype_table_label(datatype)
    )
    md_report.append(f"\n### {title}\n\n")
    md_report.append(f"![{title}]({image_path})\n")

md_report.append("\n## Benchmark Results\n\n")
md_report.append("### Timing Results (nanoseconds)\n\n")
md_report.append(
    "| Datatype | Operation | fory (ns) | protobuf (ns) | json (ns) | Fastest |\n"
)
md_report.append(
    "|----------|-----------|-----------|---------------|-----------|---------|\n"
)

for datatype in datatypes:
    for operation in operations:
        times = {lib: data[datatype][operation].get(lib, 0) for lib in SERIALIZER_ORDER}
        valid = {lib: value for lib, value in times.items() if value > 0}
        fastest = min(valid, key=valid.get) if valid else None
        md_report.append(
            "| "
            + f"{format_datatype_table_label(datatype)} | {operation.capitalize()} | "
            + " | ".join(
                f"{times[lib]:.1f}" if times[lib] > 0 else "N/A"
                for lib in SERIALIZER_ORDER
            )
            + f" | {SERIALIZER_LABELS[fastest] if fastest else 'N/A'} |\n"
        )

md_report.append("\n### Throughput Results (ops/sec)\n\n")
md_report.append(
    "| Datatype | Operation | fory TPS | protobuf TPS | json TPS | Fastest |\n"
)
md_report.append(
    "|----------|-----------|----------|--------------|----------|---------|\n"
)

for datatype in datatypes:
    for operation in operations:
        times = {lib: data[datatype][operation].get(lib, 0) for lib in SERIALIZER_ORDER}
        tps = {lib: (1e9 / value if value > 0 else 0) for lib, value in times.items()}
        valid = {lib: value for lib, value in tps.items() if value > 0}
        fastest = max(valid, key=valid.get) if valid else None
        md_report.append(
            "| "
            + f"{format_datatype_table_label(datatype)} | {operation.capitalize()} | "
            + " | ".join(
                f"{tps[lib]:,.0f}" if tps[lib] > 0 else "N/A"
                for lib in SERIALIZER_ORDER
            )
            + f" | {SERIALIZER_LABELS[fastest] if fastest else 'N/A'} |\n"
        )

if sizes:
    md_report.append("\n### Serialized Data Sizes (bytes)\n\n")
    md_report.append("| Datatype | fory | protobuf | json |\n")
    md_report.append("|----------|------|----------|------|\n")
    size_datatypes = [
        ("struct", "Struct"),
        ("sample", "Sample"),
        ("media", "MediaContent"),
        ("struct_list", "StructList"),
        ("sample_list", "SampleList"),
        ("media_list", "MediaContentList"),
    ]
    for datatype_key, datatype_label in size_datatypes:
        row = []
        has_value = False
        for serializer in SERIALIZER_ORDER:
            value = sizes.get(f"{serializer}_{datatype_key}_size")
            if value is None:
                row.append("N/A")
            else:
                row.append(str(value))
                has_value = True
        if has_value:
            md_report.append(f"| {datatype_label} | " + " | ".join(row) + " |\n")

report_path = os.path.join(output_dir, "README.md")
with open(report_path, "w", encoding="utf-8") as handle:
    handle.writelines(md_report)

prettier = shutil.which("prettier")
if prettier is not None:
    subprocess.run([prettier, "--write", report_path], check=True)

print(f"Plots saved in: {output_dir}")
print(f"Markdown report generated at: {report_path}")
