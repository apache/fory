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

"""Generate Scala JSON benchmark charts and a Markdown report from JMH JSON."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from datetime import date
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

SERIALIZERS = ("fory", "jsoniter", "jackson")
LABELS = {
    "fory": "fory-json-scala",
    "jsoniter": "jsoniter-scala",
    "jackson": "Jackson Scala",
}
COLORS = {
    "fory": "#FF6F01",
    "jsoniter": "#4C78A8",
    "jackson": "#55BCC2",
}
OPERATIONS = ("to", "from")
REPRESENTATIONS = ("string", "bytes")
BENCHMARK_PATTERN = re.compile(
    r"(?:^|[.])(?P<serializer>fory|jsoniter|jackson)"
    r"(?P<operation>To|From)Json(?P<representation>Bytes|String)$"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json-file", default="reports/json/benchmark_results.json")
    parser.add_argument("--output-dir", default="reports/json")
    parser.add_argument("--source-commit", default="unknown")
    parser.add_argument("--platform", default="unknown")
    parser.add_argument("--benchmark-date", default=date.today().isoformat())
    return parser.parse_args()


def load_json(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as source:
        value = json.load(source)
    benchmarks = value if isinstance(value, list) else value.get("benchmarks", [])
    if not isinstance(benchmarks, list):
        raise TypeError(f"Expected a JMH benchmark list in {path}")
    return benchmarks


def ops_per_second(value: float, unit: str) -> float:
    multiplier = {
        "ops/s": 1,
        "ops/ms": 1_000,
        "ops/us": 1_000_000,
        "ops/ns": 1_000_000_000,
    }.get(unit)
    if multiplier is None:
        raise ValueError(f"Unsupported JMH throughput unit: {unit}")
    return value * multiplier


def collect_results(
    benchmarks: list[dict[str, Any]],
) -> dict[tuple[str, str], dict[str, tuple[float, float]]]:
    results = {
        (operation, representation): {}
        for representation in REPRESENTATIONS
        for operation in OPERATIONS
    }
    for benchmark in benchmarks:
        match = BENCHMARK_PATTERN.search(benchmark.get("benchmark", ""))
        if match is None:
            continue
        key = (match.group("operation").lower(), match.group("representation").lower())
        metric = benchmark["primaryMetric"]
        unit = metric.get("scoreUnit", "ops/s")
        score = ops_per_second(float(metric["score"]), unit)
        error = ops_per_second(float(metric.get("scoreError", 0)), unit)
        results[key][match.group("serializer")] = (
            score,
            error if math.isfinite(error) else 0,
        )
    missing = [
        f"{serializer}{operation.title()}Json{representation.title()}"
        for representation in REPRESENTATIONS
        for operation in OPERATIONS
        for serializer in SERIALIZERS
        if serializer not in results[(operation, representation)]
    ]
    if missing:
        raise ValueError("Missing JMH benchmark results: " + ", ".join(missing))
    return results


def render_plot(
    results: dict[tuple[str, str], dict[str, tuple[float, float]]],
    representation: str,
    output: Path,
) -> None:
    figure, axes = plt.subplots(1, 2, figsize=(11.5, 5.2))
    x = np.arange(len(SERIALIZERS), dtype=float)
    for axis, operation in zip(axes, OPERATIONS):
        values = [results[(operation, representation)][name][0] for name in SERIALIZERS]
        errors = [results[(operation, representation)][name][1] for name in SERIALIZERS]
        bars = axis.bar(
            x,
            values,
            width=0.62,
            yerr=errors,
            capsize=2.5,
            color=[COLORS[name] for name in SERIALIZERS],
            edgecolor=BAR_EDGE_COLOR,
            linewidth=0.8,
        )
        axis.bar_label(
            bars,
            labels=[format_throughput_label(value) for value in values],
            padding=3,
            fontsize=8,
        )
        axis.set_ylim(
            0, max(value + error for value, error in zip(values, errors)) * 1.18
        )
        axis.set_xticks(x)
        axis.set_xticklabels([LABELS[name] for name in SERIALIZERS])
        axis.set_title("Serialize" if operation == "to" else "Deserialize", pad=10)
        axis.yaxis.set_major_formatter(FuncFormatter(format_throughput_tick))
        style_throughput_axis(axis)
    axes[0].set_ylabel("Throughput (ops/sec)")
    shape = "String" if representation == "string" else "UTF-8 Bytes"
    figure.suptitle(f"Scala JSON {shape} Throughput", y=0.98)
    figure.tight_layout(rect=[0, 0, 1, 0.95], w_pad=2.4)
    save_benchmark_figure(figure, output)
    plt.close(figure)


def metadata(benchmarks: list[dict[str, Any]]) -> list[str]:
    if not benchmarks:
        return []
    first = benchmarks[0]
    values = []
    for label, key in (("JDK", "jdkVersion"), ("VM", "vmName"), ("JMH", "jmhVersion")):
        if first.get(key):
            values.append(f"{label}: `{first[key]}`")
    if first.get("warmupIterations") is not None and first.get("warmupTime"):
        values.append(
            f"Warmup: {first['warmupIterations']} iterations × `{first['warmupTime']}`"
        )
    if first.get("measurementIterations") is not None and first.get("measurementTime"):
        values.append(
            "Measurement: "
            f"{first['measurementIterations']} iterations × `{first['measurementTime']}`"
        )
    values.append(f"Forks: {first.get('forks', 1)}; threads: {first.get('threads', 1)}")
    if first.get("alternatingRounds"):
        values.append(
            "Aggregation: median of "
            f"{first['alternatingRounds']} alternating short runs; error bars show the "
            "maximum cross-run deviation"
        )
    values.append("Mode: throughput; higher is better")
    return values


def improvement(fory: float, other: float) -> str:
    return f"{(fory / other - 1) * 100:.1f}%"


def render_report(
    benchmarks: list[dict[str, Any]],
    results: dict[tuple[str, str], dict[str, tuple[float, float]]],
    output: Path,
    source_commit: str,
    platform: str,
    benchmark_date: str,
) -> None:
    lines = [
        "# Scala JSON Benchmark Report\n\n",
        "The benchmark compares fory-json-scala, jsoniter-scala, and Jackson Scala on the "
        "same immutable Scala MediaContent model and Eishay JSON document. The String group "
        "excludes UTF-8 conversion; every library in the UTF-8 group uses its direct byte-array "
        "API.\n\n",
        f"- Benchmark date: `{benchmark_date}`\n",
        f"- Source commit: `{source_commit}`\n",
        f"- Platform: {platform}\n",
    ]
    lines.extend(f"- {item}\n" for item in metadata(benchmarks))
    lines.extend(
        [
            "\n## String\n\n",
            "![Scala JSON String benchmark throughput](string_throughput.png)\n\n",
            "## UTF-8 Bytes\n\n",
            "![Scala JSON UTF-8 bytes benchmark throughput](utf8_bytes_throughput.png)\n\n",
            "## Results\n\n",
            "| Representation | Operation | fory-json-scala ops/sec | jsoniter-scala ops/sec | Jackson Scala ops/sec | Fastest |\n",
            "| --- | --- | ---: | ---: | ---: | --- |\n",
        ]
    )
    for representation in REPRESENTATIONS:
        for operation in OPERATIONS:
            values = results[(operation, representation)]
            fastest = max(SERIALIZERS, key=lambda name: values[name][0])
            lines.append(
                f"| {'String' if representation == 'string' else 'UTF-8 bytes'} | "
                f"{'Serialize' if operation == 'to' else 'Deserialize'} | "
                f"{values['fory'][0]:,.0f} | {values['jsoniter'][0]:,.0f} | "
                f"{values['jackson'][0]:,.0f} | {LABELS[fastest]} |\n"
            )
    lines.extend(
        [
            "\n## Fory performance advantage\n\n",
            "| Representation | Operation | vs jsoniter-scala | vs Jackson Scala |\n",
            "| --- | --- | ---: | ---: |\n",
        ]
    )
    for representation in REPRESENTATIONS:
        for operation in OPERATIONS:
            values = results[(operation, representation)]
            lines.append(
                f"| {'String' if representation == 'string' else 'UTF-8 bytes'} | "
                f"{'Serialize' if operation == 'to' else 'Deserialize'} | "
                f"{improvement(values['fory'][0], values['jsoniter'][0])} | "
                f"{improvement(values['fory'][0], values['jackson'][0])} |\n"
            )
    output.write_text("".join(lines), encoding="utf-8")
    format_markdown_with_prettier(output)


def main() -> None:
    args = parse_args()
    benchmarks = load_json(Path(args.json_file))
    results = collect_results(benchmarks)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    render_plot(results, "string", output_dir / "string_throughput.png")
    render_plot(results, "bytes", output_dir / "utf8_bytes_throughput.png")
    render_report(
        benchmarks,
        results,
        output_dir / "README.md",
        args.source_commit,
        args.platform,
        args.benchmark_date,
    )


if __name__ == "__main__":
    main()
