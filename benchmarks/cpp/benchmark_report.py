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

# === Colors and serializer order ===
COLORS = {
    "fory": "#FF6f01",  # Orange
    "protobuf": "#55BCC2",  # Teal
    "msgpack": (0.55, 0.40, 0.45),
}
SERIALIZER_ORDER = ["fory", "protobuf", "msgpack"]
SERIALIZER_LABELS = {
    "fory": "fory",
    "protobuf": "protobuf",
    "msgpack": "msgpack",
}

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
    - BM_Msgpack_MediaContent_Deserialize
    Returns: (library, datatype, operation)
    """
    # Remove BM_ prefix
    if name.startswith("BM_"):
        name = name[3:]

    parts = name.split("_")
    if len(parts) >= 3:
        library = parts[0].lower()
        datatype = parts[1].lower()
        operation = parts[2].lower()
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


def format_datatype_table_label(datatype):
    if not datatype:
        return ""
    if datatype.endswith("list"):
        base = datatype[: -len("list")]
        if base == "mediacontent":
            return "MediaContentList"
        return f"{base.capitalize()}List"
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
            for key, value in bench.items():
                if key.endswith("_size"):
                    sizes[key] = int(value)
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
def format_tps_label(tps):
    if tps >= 1e9:
        return f"{tps / 1e9:.2f}G"
    if tps >= 1e6:
        return f"{tps / 1e6:.2f}M"
    if tps >= 1e3:
        return f"{tps / 1e3:.2f}K"
    return f"{tps:.0f}"


def plot_datatype(ax, datatype, operation):
    """Plot a single datatype/operation throughput comparison."""
    if datatype not in data or operation not in data[datatype]:
        ax.set_title(f"{datatype} {operation} - No Data")
        ax.axis("off")
        return

    libs = set(data[datatype][operation].keys())
    lib_order = [lib for lib in SERIALIZER_ORDER if lib in libs]

    times = [data[datatype][operation].get(lib, 0) for lib in lib_order]
    throughput = [1e9 / t if t > 0 else 0 for t in times]
    colors = [COLORS.get(lib, "#888888") for lib in lib_order]

    x = np.arange(len(lib_order))
    bars = ax.bar(x, throughput, color=colors, width=0.6)

    ax.set_title(f"{operation.capitalize()} Throughput (higher is better)")
    ax.set_xticks(x)
    ax.set_xticklabels([SERIALIZER_LABELS.get(lib, lib) for lib in lib_order])
    ax.set_ylabel("Throughput (ops/sec)")
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))

    # Add value labels on bars
    for bar, tps_val in zip(bars, throughput):
        height = bar.get_height()
        ax.annotate(
            format_tps_label(tps_val),
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
    fig.suptitle(f"{datatype.capitalize()} Throughput", fontsize=14)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    plot_path = os.path.join(output_dir, f"{datatype}.png")
    plt.savefig(plot_path, dpi=150)
    plot_images.append((datatype, plot_path))
    plt.close()

# === Create combined TPS comparison plot ===
non_list_datatypes = [dt for dt in datatypes if not dt.endswith("list")]
list_datatypes = [dt for dt in datatypes if dt.endswith("list")]


def plot_combined_tps_subplot(ax, grouped_datatypes, operation, title):
    if not grouped_datatypes:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    x = np.arange(len(grouped_datatypes))
    available_libs = [
        lib
        for lib in SERIALIZER_ORDER
        if any(data[dt][operation].get(lib, 0) > 0 for dt in grouped_datatypes)
    ]
    if not available_libs:
        ax.set_title(f"{title}\nNo Data")
        ax.axis("off")
        return

    width = 0.8 / len(available_libs)
    for idx, lib in enumerate(available_libs):
        times = [data[dt][operation].get(lib, 0) for dt in grouped_datatypes]
        tps = [1e9 / t if t > 0 else 0 for t in times]
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

    # Use a dedicated y-scale per subplot so list benchmarks are not compressed.
    ax.ticklabel_format(style="scientific", axis="y", scilimits=(0, 0))


fig, axes = plt.subplots(1, 4, figsize=(28, 6))
fig.supylabel("Throughput (ops/sec)")

combined_subplots = [
    (axes[0], non_list_datatypes, "serialize", "Serialize Throughput"),
    (axes[1], non_list_datatypes, "deserialize", "Deserialize Throughput"),
    (axes[2], list_datatypes, "serialize", "Serialize Throughput (*List)"),
    (axes[3], list_datatypes, "deserialize", "Deserialize Throughput (*List)"),
]

for ax, grouped_datatypes, op, title in combined_subplots:
    plot_combined_tps_subplot(ax, grouped_datatypes, op, f"{title} (higher is better)")

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
    "cd benchmarks/cpp/build\n",
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
md_report.append("\nAll class-level plots below show throughput (ops/sec).\n")
plot_images_sorted = sorted(
    plot_images, key=lambda item: (0 if item[0] == "throughput" else 1, item[0])
)
for datatype, img in plot_images_sorted:
    img_filename = os.path.basename(img)
    img_path_report = args.plot_prefix + img_filename
    md_report.append(f"\n### {datatype.replace('_', ' ').title()}\n\n")
    md_report.append(
        f'<p align="center">\n<img src="{img_path_report}" width="90%" />\n</p>\n'
    )

# Results table
md_report.append("\n## Benchmark Results\n\n")
md_report.append("### Timing Results (nanoseconds)\n\n")
md_report.append(
    "| Datatype | Operation | fory (ns) | protobuf (ns) | msgpack (ns) | Fastest |\n"
)
md_report.append(
    "|----------|-----------|-----------|---------------|--------------|---------|\n"
)

for datatype in datatypes:
    for op in operations:
        times = {lib: data[datatype][op].get(lib, 0) for lib in SERIALIZER_ORDER}
        positive_times = {lib: t for lib, t in times.items() if t > 0}
        fastest_str = "N/A"
        if positive_times:
            fastest_lib = min(positive_times, key=positive_times.get)
            fastest_str = SERIALIZER_LABELS.get(fastest_lib, fastest_lib)
        md_report.append(
            "| "
            + f"{format_datatype_table_label(datatype)} | {op.capitalize()} | "
            + " | ".join(
                f"{times[lib]:.1f}" if times[lib] > 0 else "N/A"
                for lib in SERIALIZER_ORDER
            )
            + f" | {fastest_str} |\n"
        )

# Throughput table
md_report.append("\n### Throughput Results (ops/sec)\n\n")
md_report.append(
    "| Datatype | Operation | fory TPS | protobuf TPS | msgpack TPS | Fastest |\n"
)
md_report.append(
    "|----------|-----------|----------|--------------|-------------|---------|\n"
)

for datatype in datatypes:
    for op in operations:
        times = {lib: data[datatype][op].get(lib, 0) for lib in SERIALIZER_ORDER}
        tps = {lib: (1e9 / t if t > 0 else 0) for lib, t in times.items()}
        positive_tps = {lib: v for lib, v in tps.items() if v > 0}
        fastest_str = "N/A"
        if positive_tps:
            fastest_lib = max(positive_tps, key=positive_tps.get)
            fastest_str = SERIALIZER_LABELS.get(fastest_lib, fastest_lib)
        md_report.append(
            "| "
            + f"{format_datatype_table_label(datatype)} | {op.capitalize()} | "
            + " | ".join(
                f"{tps[lib]:,.0f}" if tps[lib] > 0 else "N/A"
                for lib in SERIALIZER_ORDER
            )
            + f" | {fastest_str} |\n"
        )

# Serialized sizes
if sizes:
    md_report.append("\n### Serialized Data Sizes (bytes)\n\n")
    md_report.append("| Datatype | fory | protobuf | msgpack |\n")
    md_report.append("|----------|------|----------|---------|\n")
    size_prefix = {
        "fory": "fory",
        "protobuf": "protobuf",
        "msgpack": "msgpack",
    }
    size_datatypes = [
        ("struct", "Struct"),
        ("sample", "Sample"),
        ("media", "MediaContent"),
        ("struct_list", "StructList"),
        ("sample_list", "SampleList"),
        ("media_list", "MediaContentList"),
    ]
    for datatype_key, datatype_label in size_datatypes:
        row_values = []
        has_value = False
        for lib in SERIALIZER_ORDER:
            key = f"{size_prefix[lib]}_{datatype_key}_size"
            value = sizes.get(key)
            if value is None and lib == "protobuf":
                value = sizes.get(f"proto_{datatype_key}_size")
            if value is None:
                row_values.append("N/A")
            else:
                row_values.append(str(value))
                has_value = True
        if has_value:
            md_report.append(f"| {datatype_label} | " + " | ".join(row_values) + " |\n")

# Save Markdown
report_path = os.path.join(output_dir, "README.md")
with open(report_path, "w", encoding="utf-8") as f:
    f.writelines(md_report)

print(f"✅ Plots saved in: {output_dir}")
print(f"📄 Markdown report generated at: {report_path}")
