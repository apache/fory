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

"""Build and run the Kotlin JSON JMH benchmark."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

BENCHMARK = "org.apache.fory.benchmark.json.MediaContentBenchmark.*"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", default="reports/json")
    parser.add_argument("--warmup-iterations", type=int, default=3)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--duration", default="2s")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    args = parser.parse_args()
    if args.warmup_iterations <= 0 or args.iterations <= 0:
        parser.error("iteration counts must be positive")
    return args


def run(command: list[str], cwd: Path, log: Path) -> None:
    print(f"Running in {cwd}: {' '.join(command)}", flush=True)
    with log.open("w", encoding="utf-8") as output:
        subprocess.run(
            command,
            cwd=cwd,
            stdout=output,
            stderr=subprocess.STDOUT,
            text=True,
            check=True,
        )


def main() -> None:
    args = parse_args()
    benchmark_dir = Path(__file__).resolve().parent
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = benchmark_dir / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    if not args.skip_build:
        run(
            [
                "gradle",
                "--no-daemon",
                "clean",
                "test",
                "verifyGeneratedJsonArtifacts",
                "jmhJar",
            ],
            benchmark_dir,
            output_dir / "build.log",
        )

    jars = sorted((benchmark_dir / "build" / "libs").glob("*-jmh.jar"))
    if len(jars) != 1:
        raise RuntimeError(f"Expected one JMH JAR, found {len(jars)}")
    if args.prepare_only:
        print(f"Kotlin JSON benchmark JAR: {jars[0]}")
        return

    result = output_dir / "benchmark_results.json"
    run(
        [
            "java",
            "-jar",
            str(jars[0]),
            BENCHMARK,
            "-f",
            "1",
            "-wi",
            str(args.warmup_iterations),
            "-i",
            str(args.iterations),
            "-t",
            "1",
            "-w",
            args.duration,
            "-r",
            args.duration,
            "-bm",
            "thrpt",
            "-tu",
            "s",
            "-rf",
            "json",
            "-rff",
            str(result),
        ],
        benchmark_dir,
        output_dir / "benchmark.log",
    )
    results = json.loads(result.read_text(encoding="utf-8"))
    if not isinstance(results, list) or len(results) != 16:
        raise RuntimeError(
            f"Expected 16 JMH results, found {len(results)}; see benchmark.log"
        )
    print(f"Kotlin JSON benchmark results: {result}")


if __name__ == "__main__":
    main()
