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

"""Run alternating short Scala JSON JMH trials and generate the report."""

from __future__ import annotations

import argparse
import copy
import json
import platform
import statistics
import subprocess
import sys
from datetime import date
from pathlib import Path
from typing import Any

LIBRARIES = ("fory", "jsoniter", "jackson")
BENCHMARK_CLASS = "org.apache.fory.benchmark.json.MediaContentBenchmark"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--warmup-iterations", type=int, default=1)
    parser.add_argument("--iterations", type=int, default=2)
    parser.add_argument("--duration", default="500ms")
    parser.add_argument("--output-dir", default="reports/json")
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    for name in ("rounds", "warmup_iterations", "iterations"):
        if getattr(args, name) <= 0:
            parser.error(f"--{name.replace('_', '-')} must be positive")
    return args


def run(command: list[str], cwd: Path, log: Path) -> None:
    log.parent.mkdir(parents=True, exist_ok=True)
    print(f"Running in {cwd}: {' '.join(command)}", flush=True)
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(
            command,
            cwd=cwd,
            stdout=output,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
    if result.returncode != 0:
        raise RuntimeError(
            f"Command failed with exit code {result.returncode}; see {log}"
        )


def alternating_order(round_index: int) -> tuple[str, ...]:
    offset = round_index % len(LIBRARIES)
    return LIBRARIES[offset:] + LIBRARIES[:offset]


def load_results(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, list):
        raise TypeError(f"Expected a JMH result list in {path}")
    return value


def aggregate_results(
    trials: list[list[dict[str, Any]]], rounds: int
) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for trial in trials:
        for benchmark in trial:
            grouped.setdefault(benchmark["benchmark"], []).append(benchmark)

    expected = rounds
    aggregated = []
    for name in sorted(grouped):
        samples = grouped[name]
        if len(samples) != expected:
            raise ValueError(f"Expected {expected} alternating samples for {name}")
        result = copy.deepcopy(samples[0])
        scores = [float(sample["primaryMetric"]["score"]) for sample in samples]
        median = statistics.median(scores)
        result["primaryMetric"]["score"] = median
        result["primaryMetric"]["scoreError"] = max(
            abs(score - median) for score in scores
        )
        for field in ("rawData", "scoreConfidence", "scorePercentiles"):
            result["primaryMetric"].pop(field, None)
        result["alternatingRounds"] = rounds
        result["aggregation"] = "median"
        aggregated.append(result)
    if len(aggregated) != 12:
        raise ValueError(f"Expected 12 Scala JSON benchmarks, found {len(aggregated)}")
    return aggregated


def source_commit(root: Path) -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def main() -> None:
    args = parse_args()
    benchmark_dir = Path(__file__).resolve().parent
    root = benchmark_dir.parents[1]
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = benchmark_dir / output_dir
    raw_dir = output_dir / "alternating-runs"
    raw_dir.mkdir(parents=True, exist_ok=True)

    if not args.skip_build:
        run(
            [
                "mvn",
                "-B",
                "--no-transfer-progress",
                "-pl",
                "fory-json",
                "-am",
                "install",
                "-DskipTests",
                "-Dmaven.javadoc.skip=true",
            ],
            root / "java",
            raw_dir / "build-java.log",
        )
        run(
            ["sbt", "++3.3.1", "fory-json-scala/publishM2"],
            root / "scala",
            raw_dir / "build-scala.log",
        )
        run(
            ["sbt", "Jmh/compile"],
            benchmark_dir,
            raw_dir / "build-benchmark.log",
        )

    trials: list[list[dict[str, Any]]] = []
    for round_index in range(args.rounds):
        for library in alternating_order(round_index):
            result_file = raw_dir / f"round-{round_index + 1}-{library}.json"
            selector = f"{BENCHMARK_CLASS}.{library}(To|From)Json(Bytes|String)$"
            jmh_args = (
                f"Jmh/run {selector} -f 1 -wi {args.warmup_iterations} "
                f"-i {args.iterations} -t 1 -w {args.duration} -r {args.duration} "
                f"-bm thrpt -tu s -rf json -rff {result_file}"
            )
            log = raw_dir / f"round-{round_index + 1}-{library}.log"
            run(["sbt", jmh_args], benchmark_dir, log)
            trials.append(load_results(result_file))

    aggregate = aggregate_results(trials, args.rounds)
    result_file = output_dir / "benchmark_results.json"
    result_file.write_text(json.dumps(aggregate, indent=2) + "\n", encoding="utf-8")
    run(
        [
            sys.executable,
            str(benchmark_dir / "plot_json_benchmark.py"),
            "--json-file",
            str(result_file),
            "--output-dir",
            str(output_dir),
            "--source-commit",
            source_commit(root),
            "--platform",
            f"{platform.platform()} ({platform.machine()})",
            "--benchmark-date",
            date.today().isoformat(),
        ],
        benchmark_dir,
        raw_dir / "plot.log",
    )
    print(f"Scala JSON benchmark report: {output_dir / 'README.md'}")


if __name__ == "__main__":
    main()
