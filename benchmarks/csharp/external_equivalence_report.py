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
import base64
import binascii
import json
import math
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from statistics import median

LANES = (
    "class-root",
    "struct-root",
    "holder-field",
    "list-field",
    "list-root",
    "map-field",
    "map-root",
)
OPERATIONS = ("serialize", "deserialize")
ROLES = ("apache-main", "ordinary", "external")
WORKER_IMPLEMENTATIONS = {
    "apache-main": "ordinary",
    "ordinary": "ordinary",
    "external": "external",
}
SAMPLE_ORDERS = (
    ("apache-main", "ordinary", "external"),
    ("external", "ordinary", "apache-main"),
)
MAX_SLOWDOWN_PERCENT = 1.0
TEXT_METADATA = (
    "RuntimeVersion",
    "OsDescription",
    "OsArchitecture",
    "ProcessArchitecture",
)
METADATA_FIELDS = TEXT_METADATA + (
    "ProcessorCount",
    "WarmupSeconds",
    "DurationSeconds",
    "AllocationIterations",
)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="Merge isolated C# external-equivalence benchmark results."
    )
    parser.add_argument(
        "--lane",
        action="append",
        choices=LANES,
        help="Expected lane; repeat as needed. Defaults to all lanes.",
    )
    parser.add_argument(
        "--sample",
        action="append",
        nargs=6,
        required=True,
        metavar=(
            "LANE",
            "INDEX",
            "ORDER",
            "FIRST_INPUT",
            "SECOND_INPUT",
            "THIRD_INPUT",
        ),
        help=(
            "Balanced apache-main/current-ordinary/current-external sample metadata "
            "and worker JSON paths; repeat for each lane and sample."
        ),
    )
    parser.add_argument(
        "--apache-main-commit",
        required=True,
        help="Fetched apache/main commit used by baseline workers.",
    )
    parser.add_argument(
        "--current-commit",
        required=True,
        help="Current worktree HEAD commit used by ordinary/external workers.",
    )
    parser.add_argument(
        "--current-dirty",
        required=True,
        choices=("true", "false"),
        help="Whether the current worktree had changes when workers were launched.",
    )
    parser.add_argument("--output", required=True, help="Combined JSON output path.")
    return parser.parse_args(argv)


def require_object(value, owner: str) -> dict:
    if not isinstance(value, dict):
        raise TypeError(f"{owner} must be a JSON object")
    return value


def require_string(value, owner: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{owner} must be a non-empty string")
    return value


def require_int(value, owner: str, minimum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(
            f"{owner} must be an integer greater than or equal to {minimum}"
        )
    return value


def require_number(value, owner: str, positive: bool):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise TypeError(f"{owner} must be a number")
    number = float(value)
    if not math.isfinite(number) or (number <= 0 if positive else number < 0):
        qualifier = "positive" if positive else "non-negative"
        raise ValueError(f"{owner} must be a finite {qualifier} number")
    return value


def require_commit(value, owner: str) -> str:
    commit = require_string(value, owner)
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValueError(f"{owner} must be a lowercase 40-character commit id")
    return commit


def validate_metadata(document: dict, path: Path) -> dict:
    owner = str(path)
    require_string(document.get("GeneratedAtUtc"), f"{owner}.GeneratedAtUtc")
    for field in TEXT_METADATA:
        require_string(document.get(field), f"{owner}.{field}")
    require_int(document.get("ProcessorCount"), f"{owner}.ProcessorCount", 1)
    require_number(document.get("WarmupSeconds"), f"{owner}.WarmupSeconds", True)
    require_number(document.get("DurationSeconds"), f"{owner}.DurationSeconds", True)
    require_int(
        document.get("AllocationIterations"),
        f"{owner}.AllocationIterations",
        1,
    )
    return {field: document[field] for field in METADATA_FIELDS}


def validate_measurement(value, owner: str) -> dict:
    measurement = require_object(value, owner)
    for field in ("OperationsPerSecond", "AverageNanoseconds", "ElapsedSeconds"):
        require_number(measurement.get(field), f"{owner}.{field}", True)
    require_int(measurement.get("Iterations"), f"{owner}.Iterations", 1)
    return measurement


def decode_frame(value, owner: str, serialized_size: int) -> bytes:
    encoded = require_string(value, owner)
    try:
        frame = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError(f"{owner} is not valid Base64: {error}") from error
    if base64.b64encode(frame).decode("ascii") != encoded:
        raise ValueError(f"{owner} is not canonical Base64")
    if len(frame) != serialized_size:
        raise ValueError(
            f"{owner} decodes to {len(frame)} bytes, expected {serialized_size}"
        )
    return frame


def sample_order_name(order) -> str:
    return "-".join(order)


def parse_sample_specs(raw_samples, expected_lanes):
    expected_lane_set = set(expected_lanes)
    expected_specs = {
        (lane, index): order
        for lane in expected_lanes
        for index, order in enumerate(SAMPLE_ORDERS, start=1)
    }
    specs = {}
    input_owners = {}

    for raw_sample in raw_samples:
        (
            lane,
            raw_index,
            order_name,
            first_input,
            second_input,
            third_input,
        ) = raw_sample
        if lane not in expected_lane_set:
            raise ValueError(f"sample lane is not expected: {lane!r}")
        try:
            sample_index = int(raw_index)
        except ValueError as error:
            raise ValueError(
                f"sample index must be an integer: {raw_index!r}"
            ) from error
        if raw_index != str(sample_index):
            raise ValueError(
                f"sample index must use canonical decimal form: {raw_index!r}"
            )

        key = (lane, sample_index)
        expected_order = expected_specs.get(key)
        if expected_order is None:
            raise ValueError(f"unexpected sample index for {lane}: {sample_index}")
        if key in specs:
            raise ValueError(f"duplicate sample for {lane}/{sample_index}")

        expected_order_name = sample_order_name(expected_order)
        if order_name != expected_order_name:
            raise ValueError(
                f"sample {lane}/{sample_index} order must be "
                f"{expected_order_name}, got {order_name}"
            )

        paths = (
            Path(
                require_string(first_input, f"sample {lane}/{sample_index} first input")
            ),
            Path(
                require_string(
                    second_input,
                    f"sample {lane}/{sample_index} second input",
                )
            ),
            Path(
                require_string(
                    third_input,
                    f"sample {lane}/{sample_index} third input",
                )
            ),
        )
        for path in paths:
            normalized = path.resolve()
            if normalized in input_owners:
                raise ValueError(
                    f"worker input {path} is reused by {input_owners[normalized]} "
                    f"and {lane}/{sample_index}"
                )
            input_owners[normalized] = f"{lane}/{sample_index}"

        specs[key] = {
            "Order": expected_order,
            "Paths": paths,
        }

    missing = set(expected_specs) - set(specs)
    if missing:
        raise ValueError(
            "missing samples: "
            + ", ".join(f"{lane}/{index}" for lane, index in sorted(missing))
        )
    return specs


def load_worker(path: Path, expected_lane: str, expected_implementation: str):
    with path.open("r", encoding="utf-8") as source:
        document = require_object(json.load(source), str(path))

    metadata = validate_metadata(document, path)
    implementation = require_string(
        document.get("Implementation"), f"{path}.Implementation"
    )
    if implementation != expected_implementation:
        raise ValueError(
            f"{path}.Implementation must be {expected_implementation}, "
            f"got {implementation}"
        )

    raw_results = document.get("Results")
    if not isinstance(raw_results, list) or len(raw_results) != len(OPERATIONS):
        raise ValueError(f"{path}.Results must contain exactly two operations")

    results = {}
    for index, raw_result in enumerate(raw_results):
        owner = f"{path}.Results[{index}]"
        result = require_object(raw_result, owner)
        lane = result.get("DataType")
        if lane != expected_lane:
            raise ValueError(f"{owner}.DataType must be {expected_lane}, got {lane!r}")
        operation = result.get("Operation")
        if operation not in OPERATIONS:
            raise ValueError(f"{owner}.Operation must be serialize or deserialize")
        if operation in results:
            raise ValueError(
                f"duplicate result for {expected_lane}/{operation} in {path}"
            )

        serialized_size = require_int(
            result.get("SerializedSize"), f"{owner}.SerializedSize", 0
        )
        frame = decode_frame(
            result.get("SerializedFrameBase64"),
            f"{owner}.SerializedFrameBase64",
            serialized_size,
        )
        measurement = validate_measurement(
            result.get("Measurement"), f"{owner}.Measurement"
        )
        allocated = result.get("AllocatedBytesPerOperation")
        if allocated is None:
            raise ValueError(f"{owner}.AllocatedBytesPerOperation is required")
        require_number(
            allocated,
            f"{owner}.AllocatedBytesPerOperation",
            False,
        )

        results[operation] = {
            "SerializedSize": serialized_size,
            "Frame": frame,
            "Measurement": measurement,
            "AllocatedBytesPerOperation": allocated,
        }

    missing_operations = set(OPERATIONS) - set(results)
    if missing_operations:
        raise ValueError(
            f"{path} is missing operations: {', '.join(sorted(missing_operations))}"
        )
    if (
        results["serialize"]["SerializedSize"]
        != results["deserialize"]["SerializedSize"]
        or results["serialize"]["Frame"] != results["deserialize"]["Frame"]
    ):
        raise ValueError(
            f"serialized frame differs between operations for "
            f"{expected_implementation}/{expected_lane} in {path}"
        )
    return metadata, results


def load_samples(specs, expected_lanes):
    metadata = None
    results = {}

    for lane in expected_lanes:
        for sample_index, expected_order in enumerate(SAMPLE_ORDERS, start=1):
            spec = specs[(lane, sample_index)]
            if spec["Order"] != expected_order:
                raise ValueError(
                    f"internal sample order mismatch for {lane}/{sample_index}"
                )
            for role, path in zip(expected_order, spec["Paths"]):
                current_metadata, worker_results = load_worker(
                    path,
                    lane,
                    WORKER_IMPLEMENTATIONS[role],
                )
                if metadata is None:
                    metadata = current_metadata
                elif current_metadata != metadata:
                    differences = [
                        field
                        for field in METADATA_FIELDS
                        if current_metadata[field] != metadata[field]
                    ]
                    raise ValueError(
                        f"{path} has incompatible metadata fields: "
                        f"{', '.join(differences)}"
                    )

                for operation, result in worker_results.items():
                    results[(role, lane, sample_index, operation)] = result

    if metadata is None:
        raise ValueError("no benchmark samples were loaded")
    return metadata, results


def median_summary(samples) -> dict:
    return {
        "OperationsPerSecond": median(
            sample["Measurement"]["OperationsPerSecond"] for sample in samples
        ),
        "AverageNanoseconds": median(
            sample["Measurement"]["AverageNanoseconds"] for sample in samples
        ),
    }


def stable_allocation(samples, owner: str):
    allocations = {sample["AllocatedBytesPerOperation"] for sample in samples}
    if len(allocations) != 1:
        raise ValueError(f"allocation mismatch between samples for {owner}")
    return next(iter(allocations))


def slowdown_percent(candidate: dict, baseline: dict) -> float:
    return (
        candidate["AverageNanoseconds"] / baseline["AverageNanoseconds"] - 1.0
    ) * 100.0


def merge_results(
    metadata: dict,
    results: dict,
    expected_lanes,
    apache_main_commit: str,
    current_commit: str,
    current_dirty: bool,
) -> dict:
    expected_keys = {
        (role, lane, sample_index, operation)
        for role in ROLES
        for lane in expected_lanes
        for sample_index in range(1, len(SAMPLE_ORDERS) + 1)
        for operation in OPERATIONS
    }
    missing = expected_keys - set(results)
    if missing:
        raise ValueError(
            "missing results: "
            + ", ".join(
                f"{role}/{lane}/{sample_index}/{operation}"
                for role, lane, sample_index, operation in sorted(missing)
            )
        )
    unexpected = set(results) - expected_keys
    if unexpected:
        raise ValueError(
            "unexpected results: "
            + ", ".join(
                f"{role}/{lane}/{sample_index}/{operation}"
                for role, lane, sample_index, operation in sorted(unexpected)
            )
        )

    combined_results = []
    violations = []
    for lane in expected_lanes:
        proof = results[("apache-main", lane, 1, "serialize")]
        lane_samples = [
            results[(role, lane, sample_index, operation)]
            for role in ROLES
            for sample_index in range(1, len(SAMPLE_ORDERS) + 1)
            for operation in OPERATIONS
        ]
        for sample in lane_samples:
            if (
                sample["SerializedSize"] != proof["SerializedSize"]
                or sample["Frame"] != proof["Frame"]
            ):
                raise ValueError(f"serialized frame differs between samples for {lane}")

        for operation in OPERATIONS:
            apache_main_samples = [
                results[("apache-main", lane, sample_index, operation)]
                for sample_index in range(1, len(SAMPLE_ORDERS) + 1)
            ]
            ordinary_samples = [
                results[("ordinary", lane, sample_index, operation)]
                for sample_index in range(1, len(SAMPLE_ORDERS) + 1)
            ]
            external_samples = [
                results[("external", lane, sample_index, operation)]
                for sample_index in range(1, len(SAMPLE_ORDERS) + 1)
            ]

            apache_main_allocation = stable_allocation(
                apache_main_samples,
                f"apache-main/{lane}/{operation}",
            )
            ordinary_allocation = stable_allocation(
                ordinary_samples,
                f"ordinary/{lane}/{operation}",
            )
            external_allocation = stable_allocation(
                external_samples,
                f"external/{lane}/{operation}",
            )

            apache_main_median = median_summary(apache_main_samples)
            ordinary_median = median_summary(ordinary_samples)
            external_median = median_summary(external_samples)
            ordinary_slowdown = slowdown_percent(
                ordinary_median,
                apache_main_median,
            )
            external_slowdown = slowdown_percent(
                external_median,
                ordinary_median,
            )
            external_vs_apache_main_slowdown = slowdown_percent(
                external_median,
                apache_main_median,
            )

            if ordinary_slowdown > MAX_SLOWDOWN_PERCENT:
                violations.append(
                    f"{lane}/{operation}: current ordinary is "
                    f"{ordinary_slowdown:+.2f}% slower than apache/main "
                    f"(limit: +{MAX_SLOWDOWN_PERCENT:.2f}%)"
                )
            if external_slowdown > MAX_SLOWDOWN_PERCENT:
                violations.append(
                    f"{lane}/{operation}: current external is "
                    f"{external_slowdown:+.2f}% slower than current ordinary "
                    f"(limit: +{MAX_SLOWDOWN_PERCENT:.2f}%)"
                )
            if external_vs_apache_main_slowdown > MAX_SLOWDOWN_PERCENT:
                violations.append(
                    f"{lane}/{operation}: current external is "
                    f"{external_vs_apache_main_slowdown:+.2f}% slower than "
                    f"apache/main (limit: +{MAX_SLOWDOWN_PERCENT:.2f}%)"
                )
            if ordinary_allocation > apache_main_allocation:
                violations.append(
                    f"{lane}/{operation}: current ordinary allocates "
                    f"{ordinary_allocation:.2f} B/op, above apache/main "
                    f"{apache_main_allocation:.2f} B/op"
                )
            if external_allocation != ordinary_allocation:
                violations.append(
                    f"{lane}/{operation}: current external allocation "
                    f"{external_allocation:.2f} B/op differs from current ordinary "
                    f"{ordinary_allocation:.2f} B/op"
                )

            combined_results.append(
                {
                    "DataType": lane,
                    "Operation": operation,
                    "SerializedSize": proof["SerializedSize"],
                    "ApacheMainMedian": apache_main_median,
                    "CurrentOrdinaryMedian": ordinary_median,
                    "CurrentExternalMedian": external_median,
                    "CurrentOrdinarySlowdownPercent": ordinary_slowdown,
                    "CurrentExternalSlowdownPercent": external_slowdown,
                    "ExternalVsApacheMainSlowdownPercent": (
                        external_vs_apache_main_slowdown
                    ),
                    "ApacheMainAllocatedBytesPerOperation": apache_main_allocation,
                    "CurrentOrdinaryAllocatedBytesPerOperation": ordinary_allocation,
                    "CurrentExternalAllocatedBytesPerOperation": external_allocation,
                    "CurrentOrdinaryAllocationWithinBaseline": (
                        ordinary_allocation <= apache_main_allocation
                    ),
                    "CurrentExternalAllocationEqual": (
                        external_allocation == ordinary_allocation
                    ),
                }
            )

    return {
        "GeneratedAtUtc": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "ApacheMainCommit": apache_main_commit,
        "CurrentCommit": current_commit,
        "CurrentDirty": current_dirty,
        **metadata,
        "MaximumSlowdownPercent": MAX_SLOWDOWN_PERCENT,
        "SamplesPerRole": len(SAMPLE_ORDERS),
        "SampleOrders": [sample_order_name(order) for order in SAMPLE_ORDERS],
        "Passed": not violations,
        "Violations": violations,
        "Results": combined_results,
    }


def print_summary(output: dict) -> None:
    print("=== External-Type Equivalence Median Summary ===")
    for result in output["Results"]:
        print(
            f"{result['DataType']}/{result['Operation']}: "
            f"apache-main={result['ApacheMainMedian']['AverageNanoseconds']:.1f} ns/op, "
            f"ordinary={result['CurrentOrdinaryMedian']['AverageNanoseconds']:.1f} "
            f"ns/op ({result['CurrentOrdinarySlowdownPercent']:+.2f}%), "
            f"external={result['CurrentExternalMedian']['AverageNanoseconds']:.1f} "
            f"ns/op (vs ordinary "
            f"{result['CurrentExternalSlowdownPercent']:+.2f}%, vs apache-main "
            f"{result['ExternalVsApacheMainSlowdownPercent']:+.2f}%), "
            f"allocated="
            f"{result['ApacheMainAllocatedBytesPerOperation']:.2f}/"
            f"{result['CurrentOrdinaryAllocatedBytesPerOperation']:.2f}/"
            f"{result['CurrentExternalAllocatedBytesPerOperation']:.2f} B/op"
        )


def main(argv=None) -> int:
    args = parse_args(argv)
    lanes = args.lane if args.lane is not None else list(LANES)
    if len(lanes) != len(set(lanes)):
        raise ValueError("--lane may specify each lane only once")

    specs = parse_sample_specs(args.sample, lanes)
    metadata, results = load_samples(specs, lanes)
    output = merge_results(
        metadata,
        results,
        lanes,
        require_commit(args.apache_main_commit, "--apache-main-commit"),
        require_commit(args.current_commit, "--current-commit"),
        args.current_dirty == "true",
    )
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as destination:
        json.dump(output, destination, indent=2)
        destination.write("\n")
    print_summary(output)
    if not output["Passed"]:
        for violation in output["Violations"]:
            print(f"regression: {violation}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
