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

import os
import re
import platform
import psutil
import argparse
import matplotlib.pyplot as plt
import numpy as np
from collections import defaultdict
from datetime import datetime

# === Colors ===
FORY_COLOR = "#FF6f01"  # Orange for Fory
JSON_COLOR = "#55BCC2"  # Teal for JSON
PROTOBUF_COLOR = (1, 0.84, 0.66)  # Light gold for Protobuf

# === Regex to extract benchmark results ===
bench_re = re.compile(
    r"Benchmarking\s+([\w_]+)/([\w]+)_(serialize|deserialize)/(\w+).*?time:\s+\[([\d\.]+)\s*([µ\w]+)",
    re.DOTALL,
)
UNIT_TO_SEC = {"ns": 1e-9, "µs": 1e-6, "us": 1e-6, "ms": 1e-3, "s": 1.0}

# === Parse arguments ===
parser = argparse.ArgumentParser(
    description="Plot Cargo Bench stats and generate Markdown report"
)
parser.add_argument("--log-file", default="cargo_bench.log", help="Benchmark log file")
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

# === Data storage ===
data = defaultdict(lambda: defaultdict(lambda: defaultdict(dict)))

# === Read and parse benchmark log ===
with open(args.log_file, "r", encoding="utf-8") as f:
    content = f.read()

for match in bench_re.finditer(content):
    struct, lib, op, size, time_val, unit = match.groups()
    try:
        time_val = float(time_val)
    except ValueError:
        continue
    unit = unit.replace("μ", "µ")
    if unit not in UNIT_TO_SEC:
        continue
    sec = time_val * UNIT_TO_SEC[unit]
    tps = 1.0 / sec
    data[struct][size][op][lib] = tps

# === Sizes and operations ===
sizes = ["small", "medium", "large"]
ops = ["serialize", "deserialize"]


# === Get system info ===
def get_system_info():
    try:
        info = {
            "OS": f"{platform.system()} {platform.release()}",
            "Machine": platform.machine(),
            "Processor": platform.processor() or "Unknown",
            "CPU Cores (Physical)": psutil.cpu_count(logical=False),
            "CPU Cores (Logical)": psutil.cpu_count(logical=True),
            "Total RAM (GB)": round(psutil.virtual_memory().total / (1024**3), 2),
        }
    except Exception as e:
        info = {"Error gathering system info": str(e)}
    return info


system_info = get_system_info()

serialize_rows = []
deserialize_rows = []
plot_images = []


# === Plot a datatype's sizes ===
def plot_datatype(ax, struct, size):
    if size not in data[struct]:
        ax.set_title(f"{size} {struct} - No Data")
        ax.axis("off")
        return
    ops_present = [op for op in ops if op in data[struct][size]]
    libs = sorted({lib for op in ops_present for lib in data[struct][size][op].keys()})
    lib_order = [lib for lib in ["fory", "json", "protobuf"] if lib in libs] + [
        lib for lib in libs if lib not in ["fory", "json", "protobuf"]
    ]
    x = np.arange(len(ops_present))
    width = 0.8 / len(lib_order)
    for i, lib in enumerate(lib_order):
        color = None
        if lib == "fory":
            color = FORY_COLOR
        elif lib == "json":
            color = JSON_COLOR
        elif lib == "protobuf":
            color = PROTOBUF_COLOR
        tps_vals = [data[struct][size][op].get(lib, 0) for op in ops_present]
        ax.bar(x + i * width, tps_vals, width, label=lib, color=color)
    ax.set_title(f"{size} {struct}")
    ax.set_xticks(x + width * (len(lib_order) - 1) / 2)
    ax.set_xticklabels(ops_present)
    ax.grid(True, axis="y", linestyle="--", alpha=0.5)
    ax.legend()
    if size == "small":
        ax.set_ylabel("TPS (ops/sec)")

    # Add to markdown table data
    for op in ops_present:
        f_tps = data[struct][size][op].get("fory", 0)
        j_tps = data[struct][size][op].get("json", 0)
        p_tps = data[struct][size][op].get("protobuf", 0)
        fastest_lib, fastest_val = max(
            [("fory", f_tps), ("json", j_tps), ("protobuf", p_tps)],
            key=lambda kv: kv[1],
        )
        row = (struct, size, op, f_tps, j_tps, p_tps, fastest_lib)
        if op == "serialize":
            serialize_rows.append(row)
        else:
            deserialize_rows.append(row)


# === Create plots ===
for struct in sorted(data.keys()):
    fig, axes = plt.subplots(1, 3, figsize=(18, 5), sharey=False)
    for si, size in enumerate(sizes):
        plot_datatype(axes[si], struct, size)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    plot_path = os.path.join(output_dir, f"{struct}.png")
    plt.savefig(plot_path)
    plot_images.append((struct, plot_path))
    plt.close()

# === Markdown report ===
md_report = [
    "# Performance Comparison Report\n",
    f"_Generated on {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}_\n",
    "\n### Hardware & OS Info\n",
    "| Key | Value |\n",
    "|-----|-------|\n",
]
for k, v in system_info.items():
    md_report.append(f"| {k} | {v} |\n")

# Plots section
md_report.append("\n### Benchmark Plots\n")
for struct, img in plot_images:
    img_filename = os.path.basename(img)
    img_path_report = args.plot_prefix + img_filename
    md_report.append(f"\n**{struct}**\n\n")
    md_report.append(
        f'<p align="center">\n<img src="{img_path_report}" width="90%">\n</p>\n'
    )


# Tables section
def table_section(title, rows):
    md = [f"\n### {title}\n"]
    md.append(
        "| Datatype | Size | Operation | Fory TPS | JSON TPS | Protobuf TPS | Fastest |\n"
    )
    md.append(
        "|----------|------|-----------|----------|----------|--------------|---------|\n"
    )
    for struct, size, op, f_tps, j_tps, p_tps, fastest_lib in rows:
        md.append(
            f"| {struct} | {size} | {op} | {f_tps:,.0f} | {j_tps:,.0f} | {p_tps:,.0f} | {fastest_lib} |\n"
        )
    return md


md_report.extend(table_section("Serialize Results", serialize_rows))
md_report.extend(table_section("Deserialize Results", deserialize_rows))

# Save Markdown
report_path = os.path.join(output_dir, "README.md")
with open(report_path, "w", encoding="utf-8") as f:
    f.writelines(md_report)

print(f"✅ Plots saved in: {output_dir}")
print(f"📄 Markdown report generated at: {report_path}")
