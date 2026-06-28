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

"""Generate a Java xlang throughput plot from JMH JSON output."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

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
    format_throughput_tick,
    save_benchmark_figure,
    serializer_offset,
    set_grouped_operation_axis,
    style_throughput_axis,
)

apply_benchmark_style(plt)

SERIALIZER_ORDER = ["fory-codegen=true", "fory-codegen=false", "protobuf", "flatbuffer"]
COLORS = {
    "fory-codegen=true": "#FF6f01",
    "fory-codegen=false": "#C94700",
    "protobuf": "#55BCC2",
    "flatbuffer": (0.55, 0.40, 0.45),
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
BENCHMARK_PATTERN = re.compile(
    r"(?:^|[.])BM_(?P<serializer>Fory|Protobuf|Flatbuffer)_"
    r"(?P<datatype>NumericStruct|Sample|MediaContent|NumericStructList|SampleList|MediaContentList)_"
    r"(?P<operation>Serialize|Deserialize)$"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate throughput.png for Java xlang benchmark results"
    )
    parser.add_argument(
        "--json-file",
        default="reports/benchmark_results.json",
        help="JMH JSON output file",
    )
    parser.add_argument(
        "--output-dir",
        default="reports",
        help="Local directory for throughput.png",
    )
    parser.add_argument(
        "--docs-output-dir",
        default=None,
        help="Optional docs directory to receive only a copied throughput.png",
    )
    return parser.parse_args()


def load_json(path: str) -> Any:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def datatype_key(name: str) -> str:
    key = name.lower()
    if key == "numericstruct":
        return "struct"
    if key == "numericstructlist":
        return "structlist"
    return key


def datatype_title(datatype: str) -> str:
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


def score_to_ops_per_sec(score: float, unit: str) -> float:
    if unit == "ops/s":
        return score
    if unit == "ops/ms":
        return score * 1_000
    if unit == "ops/us":
        return score * 1_000_000
    if unit == "ops/ns":
        return score * 1_000_000_000
    return score


def collect_results(payload: Any) -> dict:
    results: dict = defaultdict(lambda: defaultdict(dict))
    benchmarks = payload if isinstance(payload, list) else payload.get("benchmarks", [])
    for bench in benchmarks:
        benchmark_name = bench.get("benchmark") or bench.get("name", "")
        match = BENCHMARK_PATTERN.search(benchmark_name)
        if match is None:
            continue
        metric = bench.get("primaryMetric", {})
        score = float(metric.get("score", bench.get("opsPerSec", 0.0)))
        unit = metric.get("scoreUnit", "ops/s")
        serializer = match.group("serializer").lower()
        if serializer == "fory":
            codegen = str(bench.get("params", {}).get("codegen", "unknown")).lower()
            serializer = f"fory-codegen={codegen}"
        datatype = datatype_key(match.group("datatype"))
        operation = match.group("operation").lower()
        results[datatype][operation][serializer] = score_to_ops_per_sec(score, unit)
    return results


def format_tps(value: float, _position) -> str:
    return format_throughput_tick(value, _position)


def plot_group(ax, results: dict, datatype: str) -> None:
    if datatype not in results:
        ax.set_title(f"{datatype_title(datatype)}\nNo Data")
        ax.axis("off")
        return

    serializers = [
        serializer
        for serializer in SERIALIZER_ORDER
        if any(
            results.get(datatype, {}).get(operation, {}).get(serializer, 0.0) > 0
            for operation in OPERATIONS
        )
    ]
    if not serializers:
        ax.set_title(f"{datatype_title(datatype)}\nNo Data")
        ax.axis("off")
        return

    x = GROUP_X
    for index, serializer in enumerate(serializers):
        values = [
            results.get(datatype, {}).get(operation, {}).get(serializer, 0.0)
            for operation in OPERATIONS
        ]
        offset = serializer_offset(index, len(serializers))
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
        for serializer in serializers
    )
    ax.set_ylim(0, max_value * 1.12)
    ax.set_title(datatype_title(datatype), fontweight="normal", pad=8)
    set_grouped_operation_axis(ax)
    style_throughput_axis(ax)
    ax.yaxis.set_major_formatter(FuncFormatter(format_tps))
    add_compact_legend(ax)


def render_plot(results: dict, output_dir: str) -> str:
    fig, axes = plt.subplots(2, 3, figsize=(16.5, 9.0))
    fig.suptitle(
        "Java Xlang Serialization Throughput",
        fontsize=15,
        fontweight="normal",
        y=0.955,
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


def format_table_value(value: float) -> str:
    return f"{value:,.0f}" if value > 0 else "N/A"


def serializer_title(serializer: str) -> str:
    if serializer.startswith("fory-codegen="):
        return "Fory " + serializer[len("fory-") :]
    return serializer.capitalize()


def winner_cell(values: dict) -> str:
    positive = {name: value for name, value in values.items() if value > 0}
    if not positive:
        return "N/A"
    winner = max(positive, key=positive.get)
    return serializer_title(winner)


def build_xlang_section(results: dict, image_name: str) -> str:
    lines = [
        "## Xlang Benchmark\n\n",
        "Run from `benchmarks/java/run.sh`. Raw JMH JSON stays under the ignored local "
        "`benchmarks/java/reports/` directory; `throughput.png` and this xlang "
        "section are synced into `docs/benchmarks/java/`.\n\n",
        "```bash\n",
        "cd benchmarks/java\n",
        "./run.sh\n",
        "```\n\n",
        "JMH parameters: `-f 1 -wi 3 -i 3 -t 1 -w 3s -r 3s -bm thrpt -tu s`. "
        "Higher throughput is better.\n\n",
        f"![Java Xlang Serialization Throughput]({image_name})\n\n",
        "| Data type | Operation | "
        + " | ".join(
            f"{serializer_title(serializer)} ops/sec" for serializer in SERIALIZER_ORDER
        )
        + " | Fastest |\n",
        "|-----------|-----------|"
        + "|".join("---:" for _ in SERIALIZER_ORDER)
        + "|---------|\n",
    ]

    for datatype in DATATYPE_ORDER:
        for operation in OPERATIONS:
            values = {
                serializer: results.get(datatype, {})
                .get(operation, {})
                .get(serializer, 0.0)
                for serializer in SERIALIZER_ORDER
            }
            lines.append(
                f"| {datatype_title(datatype)} | {operation.capitalize()} | "
                + " | ".join(
                    format_table_value(values[serializer])
                    for serializer in SERIALIZER_ORDER
                )
                + f" | {winner_cell(values)} |\n"
            )
    return "".join(lines)


def write_local_readme(output_dir: Path, section: str) -> Path:
    report_path = output_dir / "README.md"
    report_path.write_text(
        "# Java Xlang Benchmark Report\n\n" + section, encoding="utf-8"
    )
    run_prettier(report_path)
    return report_path


def update_docs_readme(docs_output_dir: Path, section: str) -> Path:
    docs_readme = docs_output_dir / "README.md"
    if docs_readme.exists():
        content = docs_readme.read_text(encoding="utf-8").rstrip()
        marker = "\n## Xlang Benchmark\n"
        if marker in content:
            prefix = content.split(marker, 1)[0].rstrip()
            content = prefix + "\n\n" + section
        else:
            content = content + "\n\n" + section
    else:
        content = "# Java Benchmarks\n\n" + section
    docs_readme.write_text(content.rstrip() + "\n", encoding="utf-8")
    run_prettier(docs_readme)
    return docs_readme


def run_prettier(path: Path) -> None:
    format_markdown_with_prettier(path)


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    results = collect_results(load_json(args.json_file))
    output_path = render_plot(results, str(output_dir))
    section = build_xlang_section(results, os.path.basename(output_path))
    report_path = write_local_readme(output_dir, section)
    print(f"Generated {report_path}")
    print(f"Generated {output_path}")
    if args.docs_output_dir is not None:
        docs_output_dir = Path(args.docs_output_dir)
        docs_output_dir.mkdir(parents=True, exist_ok=True)
        docs_output_path = docs_output_dir / "throughput.png"
        shutil.copy2(output_path, docs_output_path)
        print(f"Copied {docs_output_path}")
        docs_readme = update_docs_readme(docs_output_dir, section)
        print(f"Updated {docs_readme}")


if __name__ == "__main__":
    main()
