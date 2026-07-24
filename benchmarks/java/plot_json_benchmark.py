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

"""Generate the Java JSON benchmark chart and Markdown report from JMH JSON."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import Any

import matplotlib.pyplot as plt
import numpy as np
from matplotlib.ticker import FuncFormatter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from plot_style import (  # noqa: E402
    BAR_EDGE_COLOR,
    apply_benchmark_style,
    format_markdown_with_prettier,
    format_throughput_label,
    format_throughput_tick,
    save_benchmark_figure,
    style_throughput_axis,
)

apply_benchmark_style(plt)

DEFAULT_LIBS = "fory-json,jackson,gson"
LIB_ALIASES = {
    "fory": "fory",
    "fory-json": "fory",
    "jackson": "jackson",
    "gson": "gson",
    "fastjson2": "fastjson2",
}
SERIALIZER_LABELS = {
    "fory": "fory-json",
    "jackson": "jackson",
    "gson": "gson",
    "fastjson2": "fastjson2",
}
COLORS = {
    "fory": "#FF6F01",
    "jackson": "#55BCC2",
    "gson": "#8C6D8A",
    "fastjson2": (0.90, 0.43, 0.5),
}
OPERATIONS = ("to", "from")
REPRESENTATIONS = ("string", "bytes")
CASE_LABELS = {
    "to": "Serialize",
    "from": "Deserialize",
}
BENCHMARK_PATTERN = re.compile(
    r"(?:^|[.])(?P<serializer>fory|jackson|gson|fastjson2)"
    r"(?P<operation>To|From)Json(?P<representation>Bytes|String)$"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a Java JSON benchmark chart and Markdown report"
    )
    parser.add_argument(
        "--json-file",
        default="reports/json/benchmark_results.json",
        help="Raw JMH JSON result file",
    )
    parser.add_argument(
        "--output-dir",
        default="reports/json",
        help="Directory for throughput.png and README.md",
    )
    parser.add_argument(
        "--libs",
        default=DEFAULT_LIBS,
        help="Comma-separated libraries in bar and table order",
    )
    parser.add_argument(
        "--print-lib-config",
        action="store_true",
        help=argparse.SUPPRESS,
    )
    return parser.parse_args()


def parse_libs(value: str) -> tuple[str, ...]:
    requested = [name.strip().lower() for name in value.split(",")]
    if not requested or any(not name for name in requested):
        raise ValueError("--libs requires one or more comma-separated library names")
    serializers = []
    for name in requested:
        serializer = LIB_ALIASES.get(name)
        if serializer is None:
            choices = ", ".join(("fory-json", "jackson", "gson", "fastjson2"))
            raise ValueError(f"Unknown JSON library: {name}. Available: {choices}")
        if serializer in serializers:
            raise ValueError(f"Duplicate JSON library: {SERIALIZER_LABELS[serializer]}")
        serializers.append(serializer)
    return tuple(serializers)


def load_json(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as source:
        payload = json.load(source)
    benchmarks = payload if isinstance(payload, list) else payload.get("benchmarks", [])
    if not isinstance(benchmarks, list):
        raise ValueError(f"Expected a JMH benchmark list in {path}")
    return benchmarks


def ops_per_second(value: float, unit: str) -> float:
    multipliers = {
        "ops/s": 1,
        "ops/ms": 1_000,
        "ops/us": 1_000_000,
        "ops/ns": 1_000_000_000,
    }
    if unit not in multipliers:
        raise ValueError(f"Unsupported JMH throughput unit: {unit}")
    return value * multipliers[unit]


def collect_results(
    benchmarks: list[dict[str, Any]],
    serializers: tuple[str, ...],
) -> dict[tuple[str, str], dict[str, tuple[float, float]]]:
    results: dict[tuple[str, str], dict[str, tuple[float, float]]] = {
        (operation, representation): {}
        for representation in REPRESENTATIONS
        for operation in OPERATIONS
    }
    for benchmark in benchmarks:
        name = benchmark.get("benchmark", "")
        match = BENCHMARK_PATTERN.search(name)
        if match is None:
            continue
        serializer = match.group("serializer")
        case = (
            match.group("operation").lower(),
            match.group("representation").lower(),
        )
        metric = benchmark.get("primaryMetric", {})
        unit = metric.get("scoreUnit", "ops/s")
        score = ops_per_second(float(metric["score"]), unit)
        error = ops_per_second(float(metric.get("scoreError", 0.0)), unit)
        if not math.isfinite(error):
            error = 0.0
        results[case][serializer] = (score, error)

    missing = [
        f"{serializer}{operation.title()}Json{representation.title()}"
        for representation in REPRESENTATIONS
        for operation in OPERATIONS
        for serializer in serializers
        if serializer not in results[(operation, representation)]
    ]
    if missing:
        raise ValueError("Missing JMH benchmark results: " + ", ".join(missing))
    return results


def render_plot(
    results: dict[tuple[str, str], dict[str, tuple[float, float]]],
    serializers: tuple[str, ...],
    representation: str,
    output: Path,
) -> None:
    figure, axes = plt.subplots(1, 2, figsize=(11.5, 5.2))
    x = np.arange(len(serializers), dtype=float)

    for axis, operation in zip(axes, OPERATIONS):
        case = (operation, representation)
        values = [results[case][serializer][0] for serializer in serializers]
        errors = [results[case][serializer][1] for serializer in serializers]
        bars = axis.bar(
            x,
            values,
            width=0.62,
            yerr=errors,
            capsize=2.5,
            color=[COLORS[serializer] for serializer in serializers],
            edgecolor=BAR_EDGE_COLOR,
            linewidth=0.8,
        )
        axis.bar_label(
            bars,
            labels=[format_throughput_label(value) for value in values],
            padding=3,
            fontsize=8,
        )
        highest = max(value + error for value, error in zip(values, errors))
        axis.set_ylim(0, highest * 1.18)
        axis.set_xticks(x)
        axis.set_xticklabels([SERIALIZER_LABELS[name] for name in serializers])
        axis.set_title(CASE_LABELS[operation], pad=10)
        axis.yaxis.set_major_formatter(FuncFormatter(format_throughput_tick))
        style_throughput_axis(axis)

    axes[0].set_ylabel("Throughput (ops/sec)")
    representation_title = "String" if representation == "string" else "UTF-8 Bytes"
    figure.suptitle(
        f"Java JSON {representation_title} Serialization and Deserialization Throughput",
        y=0.98,
    )
    figure.tight_layout(rect=[0, 0, 1, 0.95], w_pad=2.4)
    save_benchmark_figure(figure, output)
    plt.close(figure)


def format_score(value: float) -> str:
    return f"{value:,.0f}"


def benchmark_metadata(benchmarks: list[dict[str, Any]]) -> list[str]:
    if not benchmarks:
        return []
    first = benchmarks[0]
    items = []
    if first.get("jdkVersion"):
        items.append(f"JDK: `{first['jdkVersion']}`")
    if first.get("vmName"):
        items.append(f"VM: `{first['vmName']}`")
    if first.get("jmhVersion"):
        items.append(f"JMH: `{first['jmhVersion']}`")
    if first.get("warmupIterations") is not None and first.get("warmupTime"):
        items.append(
            f"Warmup: {first['warmupIterations']} iterations × `{first['warmupTime']}`"
        )
    if first.get("measurementIterations") is not None and first.get("measurementTime"):
        items.append(
            "Measurement: "
            f"{first['measurementIterations']} iterations × `{first['measurementTime']}`"
        )
    if first.get("forks") is not None and first.get("threads") is not None:
        items.append(f"Forks: {first['forks']}; threads: {first['threads']}")
    items.append("Mode: throughput; higher is better")
    return items


def render_report(
    benchmarks: list[dict[str, Any]],
    results: dict[tuple[str, str], dict[str, tuple[float, float]]],
    serializers: tuple[str, ...],
    output: Path,
) -> None:
    library_names = ", ".join(SERIALIZER_LABELS[name] for name in serializers)
    lines = [
        "# Java JSON Benchmark Report\n\n",
        f"The benchmark compares {library_names} using the same MediaContent data. "
        "The String group excludes UTF-8 conversion. The UTF-8 bytes group uses "
        "direct byte-array APIs when available. "
        + (
            "Gson includes String-to-UTF-8 encoding and UTF-8-to-String decoding.\n\n"
            if "gson" in serializers
            else "\n\n"
        ),
        "```bash\n",
        "cd benchmarks/java\n",
        "./run_json.sh --libs "
        + ",".join(SERIALIZER_LABELS[name] for name in serializers)
        + "\n",
        "```\n\n",
    ]
    metadata = benchmark_metadata(benchmarks)
    if metadata:
        lines.extend(f"- {item}\n" for item in metadata)
        lines.append("\n")
    lines.extend(
        [
            "## String\n\n",
            "![Java JSON String benchmark throughput](string_throughput.png)\n\n",
            "## UTF-8 Bytes\n\n",
            "![Java JSON UTF-8 bytes benchmark throughput](utf8_bytes_throughput.png)\n\n",
            "## Results\n\n",
            "| Representation | Operation | "
            + " | ".join(f"{SERIALIZER_LABELS[name]} ops/sec" for name in serializers)
            + " | Fastest |\n",
            "| --- | --- | " + " | ".join("---:" for _ in serializers) + " | --- |\n",
        ]
    )
    for representation in REPRESENTATIONS:
        for operation in OPERATIONS:
            values = results[(operation, representation)]
            fastest = max(serializers, key=lambda serializer: values[serializer][0])
            lines.append(
                f"| {'String' if representation == 'string' else 'UTF-8 bytes'} | "
                f"{'Serialize' if operation == 'to' else 'Deserialize'} | "
                + " | ".join(
                    format_score(values[serializer][0]) for serializer in serializers
                )
                + f" | {SERIALIZER_LABELS[fastest]} |\n"
            )
    output.write_text("".join(lines), encoding="utf-8")
    format_markdown_with_prettier(output)


def main() -> None:
    args = parse_args()
    try:
        serializers = parse_libs(args.libs)
    except ValueError as error:
        raise SystemExit(str(error)) from error
    if args.print_lib_config:
        labels = ",".join(SERIALIZER_LABELS[name] for name in serializers)
        print(labels + "\t" + "|".join(serializers))
        return
    json_path = Path(args.json_file)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    benchmarks = load_json(json_path)
    results = collect_results(benchmarks, serializers)
    string_chart_path = output_dir / "string_throughput.png"
    bytes_chart_path = output_dir / "utf8_bytes_throughput.png"
    report_path = output_dir / "README.md"
    render_plot(results, serializers, "string", string_chart_path)
    render_plot(results, serializers, "bytes", bytes_chart_path)
    render_report(benchmarks, results, serializers, report_path)
    print(f"Generated {string_chart_path}")
    print(f"Generated {bytes_chart_path}")
    print(f"Generated {report_path}")


if __name__ == "__main__":
    main()
