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

import json
import os
import platform
import argparse
import matplotlib.pyplot as plt
import numpy as np
from collections import defaultdict
from datetime import datetime

try:
    import psutil

    HAS_PSUTIL = True
except ImportError:
    HAS_PSUTIL = False

# === Colors ===
FORY_COLOR = "#FF6f01"  # Orange for Fory
PROTOBUF_COLOR = "#55BCC2"  # Teal for Protobuf

# === Parse arguments ===
parser = argparse.ArgumentParser(
    description="Plot Google Benchmark stats and generate Markdown report for C++ benchmarks"
)
parser.add_argument(
    "--json-file", default="benchmark_results.json", help="Benchmark JSON output file"
)
parser.add_argument(
    "--output-dir", default="", help="Output directory for plots and report"
)
parser.add_argument(
    "--plot-prefix", default="", help="Image path prefix in Markdown report"
)
args = parser.parse_args()

# === Determine output directory ===
if args.output_dir.strip():
    output_dir = args.output_dir
else:
    output_dir = datetime.now().strftime("%Y_%m_%d_%H_%M_%S")

os.makedirs(output_dir, exist_ok=True)


# === Get system info ===
def get_system_info():
    try:
        info = {
            "OS": f"{platform.system()} {platform.release()}",
            "Machine": platform.machine(),
            "Processor": platform.processor() or "Unknown",
        }
        if HAS_PSUTIL:
            info["CPU Cores (Physical)"] = psutil.cpu_count(logical=False)
            info["CPU Cores (Logical)"] = psutil.cpu_count(logical=True)
            info["Total RAM (GB)"] = round(psutil.virtual_memory().total / (1024**3), 2)
    except Exception as e:
        info = {"Error gathering system info": str(e)}
    return info


# === Parse benchmark name ===
def parse_benchmark_name(name):
    """
    Parse benchmark names like:
    - BM_Fory_Struct_Serialize
    - BM_Protobuf_Sample_Deserialize
    Returns: (library, datatype, operation)
    """
    # Remove BM_ prefix
    if name.startswith("BM_"):
        name = name[3:]

    parts = name.split("_")
    if len(parts) >= 3:
        library = parts[0].lower()  # fory or protobuf
        datatype = parts[1].lower()  # struct or sample
        operation = parts[2].lower()  # serialize or deserialize
        return library, datatype, operation
    return None, None, None


def format_datatype_label(datatype):
    if not datatype:
        return ""
    if datatype.endswith("list"):
        base = datatype[: -len("list")]
        if base == "mediacontent":
            return "MediaContent\nList"
        return f"{base.capitalize()}\nList"
    if datatype == "mediacontent":
        return "MediaContent"
    return datatype.capitalize()


# === Read and parse benchmark JSON ===
def load_benchmark_data(json_file):
    with open(json_file, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data


# === Data storage ===
# Structure: data[datatype][operation][library] = time_ns
data = defaultdict(lambda: defaultdict(dict))
sizes = {}  # Store serialized sizes

# === Load and process data ===
benchmark_data = load_benchmark_data(args.json_file)

# Extract context info
context = benchmark_data.get("context", {})

# Process benchmarks
for bench in benchmark_data.get("benchmarks", []):
    name = bench.get("name", "")
    # Skip aggregate results and size benchmarks
    if "/iterations:" in name or "PrintSerializedSizes" in name:
        # Extract sizes from PrintSerializedSizes
        if "PrintSerializedSizes" in name:
            for key in [
                "fory_struct_size",
                "proto_struct_size",
                "fory_sample_size",
                "proto_sample_size",
                "fory_media_size",
                "proto_media_size",
                "fory_struct_list_size",
                "proto_struct_list_size",
                "fory_sample_list_size",
                "proto_sample_list_size",
                "fory_media_list_size",
                "proto_media_list_size",
            ]:
                if key in bench:
                    sizes[key] = int(bench[key])
        continue

    library, datatype, operation = parse_benchmark_name(name)
    if library and datatype and operation:
        # Get time in nanoseconds
        time_ns = bench.get("real_time", bench.get("cpu_time", 0))
        time_unit = bench.get("time_unit", "ns")

        # Convert to nanoseconds if needed
        if time_unit == "us":
            time_ns *= 1000
        elif time_unit == "ms":
            time_ns *= 1000000
        elif time_unit == "s":
            time_ns *= 1000000000

        data[datatype][operation][library] = time_ns

# === System info ===
system_info = get_system_info()

# Add context info from benchmark
if context:
    if "date" in context:
        system_info["Benchmark Date"] = context["date"]
    if "num_cpus" in context:
        system_info["CPU Cores (from benchmark)"] = context["num_cpus"]


# === Plotting ===
def plot_datatype(ax, datatype, operation):
    """Plot a single datatype/operation comparison."""
    if datatype not in data or operation not in data[datatype]:
        ax.set_title(f"{datatype} {operation} - No Data")
        ax.axis("off")
        return

    libs = sorted(data[datatype][operation].keys())
    lib_order = [lib for lib in ["fory", "protobuf"] if lib in libs]

    times = [data[datatype][operation].get(lib, 0) for lib in lib_order]
    colors = [FORY_COLOR if lib == "fory" else PROTOBUF_COLOR for lib in lib_order]

    x = np.arange(len(lib_order))
    bars = ax.bar(x, times, color=colors, width=0.6)

    ax.set_title(f"{datatype.capitalize()} {operation.capitalize()}")
    ax.set_xticks(x)
    ax.set_xticklabels([lib.capitalize() for lib in lib_order])
    ax.set_ylabel("Time (ns)")
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)

    # Add value labels on bars
    for bar, time_val in zip(bars, times):
        height = bar.get_height()
        ax.annotate(
            f"{time_val:.1f}",
            xy=(bar.get_x() + bar.get_width() / 2, height),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=9,
        )


# === Create plots ===
plot_images = []
datatypes = sorted(data.keys())
operations = ["serialize", "deserialize"]

for datatype in datatypes:
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    for i, op in enumerate(operations):
        plot_datatype(axes[i], datatype, op)
    fig.suptitle(f"{datatype.capitalize()} Benchmark Results", fontsize=14)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    plot_path = os.path.join(output_dir, f"{datatype}.png")
    plt.savefig(plot_path, dpi=150)
    plot_images.append((datatype, plot_path))
    plt.close()

# === Create combined TPS comparison plot ===
fig, axes = plt.subplots(1, 2, figsize=(14, 6))

for idx, op in enumerate(operations):
    ax = axes[idx]
    x = np.arange(len(datatypes))
    width = 0.35

    fory_times = [data[dt][op].get("fory", 0) for dt in datatypes]
    proto_times = [data[dt][op].get("protobuf", 0) for dt in datatypes]

    # Convert to TPS (operations per second)
    fory_tps = [1e9 / t if t > 0 else 0 for t in fory_times]
    proto_tps = [1e9 / t if t > 0 else 0 for t in proto_times]

    bars1 = ax.bar(x - width / 2, fory_tps, width, label="Fory", color=FORY_COLOR)
    bars2 = ax.bar(
        x + width / 2, proto_tps, width, label="Protobuf", color=PROTOBUF_COLOR
    )

    ax.set_ylabel("Throughput (ops/sec)")
    ax.set_title(f"{op.capitalize()} Throughput (higher is better)")
    ax.set_xticks(x)
    ax.set_xticklabels([format_datatype_label(dt) for dt in datatypes])
    ax.legend()
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)

    # Format y-axis with K/M suffixes
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))

fig.tight_layout()
combined_plot_path = os.path.join(output_dir, "throughput.png")
plt.savefig(combined_plot_path, dpi=150)
plot_images.append(("throughput", combined_plot_path))
plt.close()

# === Markdown report ===
md_report = [
    "# C++ Benchmark Performance Report\n\n",
    f"_Generated on {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}_\n\n",
    "## How to Generate This Report\n\n",
    "```bash\n",
    "cd benchmarks/cpp_benchmark/build\n",
    "./fory_benchmark --benchmark_format=json --benchmark_out=benchmark_results.json\n",
    "cd ..\n",
    "python benchmark_report.py --json-file build/benchmark_results.json --output-dir report\n",
    "```\n\n",
    "## Hardware & OS Info\n\n",
    "| Key | Value |\n",
    "|-----|-------|\n",
]
for k, v in system_info.items():
    md_report.append(f"| {k} | {v} |\n")

# Plots section
md_report.append("\n## Benchmark Plots\n")
for datatype, img in plot_images:
    img_filename = os.path.basename(img)
    img_path_report = args.plot_prefix + img_filename
    md_report.append(f"\n### {datatype.replace('_', ' ').title()}\n\n")
    md_report.append(
        f'<p align="center">\n<img src="{img_path_report}" width="90%">\n</p>\n'
    )

# Results table
md_report.append("\n## Benchmark Results\n\n")
md_report.append("### Timing Results (nanoseconds)\n\n")
md_report.append("| Datatype | Operation | Fory (ns) | Protobuf (ns) | Faster |\n")
md_report.append("|----------|-----------|-----------|---------------|--------|\n")

for datatype in datatypes:
    for op in operations:
        f_time = data[datatype][op].get("fory", 0)
        p_time = data[datatype][op].get("protobuf", 0)
        if f_time > 0 and p_time > 0:
            faster = "Fory" if f_time < p_time else "Protobuf"
            ratio = max(f_time, p_time) / min(f_time, p_time)
            faster_str = f"{faster} ({ratio:.1f}x)"
        else:
            faster_str = "N/A"
        md_report.append(
            f"| {datatype.capitalize()} | {op.capitalize()} | {f_time:.1f} | {p_time:.1f} | {faster_str} |\n"
        )

# Throughput table
md_report.append("\n### Throughput Results (ops/sec)\n\n")
md_report.append("| Datatype | Operation | Fory TPS | Protobuf TPS | Faster |\n")
md_report.append("|----------|-----------|----------|--------------|--------|\n")

for datatype in datatypes:
    for op in operations:
        f_time = data[datatype][op].get("fory", 0)
        p_time = data[datatype][op].get("protobuf", 0)
        f_tps = 1e9 / f_time if f_time > 0 else 0
        p_tps = 1e9 / p_time if p_time > 0 else 0
        if f_tps > 0 and p_tps > 0:
            faster = "Fory" if f_tps > p_tps else "Protobuf"
            ratio = max(f_tps, p_tps) / min(f_tps, p_tps)
            faster_str = f"{faster} ({ratio:.1f}x)"
        else:
            faster_str = "N/A"
        md_report.append(
            f"| {datatype.capitalize()} | {op.capitalize()} | {f_tps:,.0f} | {p_tps:,.0f} | {faster_str} |\n"
        )

# Serialized sizes
if sizes:
    md_report.append("\n### Serialized Data Sizes (bytes)\n\n")
    md_report.append("| Datatype | Fory | Protobuf |\n")
    md_report.append("|----------|------|----------|\n")
    if "fory_struct_size" in sizes and "proto_struct_size" in sizes:
        md_report.append(
            f"| Struct | {sizes['fory_struct_size']} | {sizes['proto_struct_size']} |\n"
        )
    if "fory_sample_size" in sizes and "proto_sample_size" in sizes:
        md_report.append(
            f"| Sample | {sizes['fory_sample_size']} | {sizes['proto_sample_size']} |\n"
        )
    if "fory_media_size" in sizes and "proto_media_size" in sizes:
        md_report.append(
            f"| MediaContent | {sizes['fory_media_size']} | {sizes['proto_media_size']} |\n"
        )
    if "fory_struct_list_size" in sizes and "proto_struct_list_size" in sizes:
        md_report.append(
            f"| StructList | {sizes['fory_struct_list_size']} | {sizes['proto_struct_list_size']} |\n"
        )
    if "fory_sample_list_size" in sizes and "proto_sample_list_size" in sizes:
        md_report.append(
            f"| SampleList | {sizes['fory_sample_list_size']} | {sizes['proto_sample_list_size']} |\n"
        )
    if "fory_media_list_size" in sizes and "proto_media_list_size" in sizes:
        md_report.append(
            f"| MediaContentList | {sizes['fory_media_list_size']} | {sizes['proto_media_list_size']} |\n"
        )

# Save Markdown
report_path = os.path.join(output_dir, "REPORT.md")
with open(report_path, "w", encoding="utf-8") as f:
    f.writelines(md_report)

print(f"✅ Plots saved in: {output_dir}")
print(f"📄 Markdown report generated at: {report_path}")
