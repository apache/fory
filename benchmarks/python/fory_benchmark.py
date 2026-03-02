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

"""Apache Fory™ vs Pickle vs Msgpack CPython Benchmark Suite

Microbenchmark comparing Apache Fory™, Pickle, and Msgpack serialization
performance in CPython.

Usage:
    python fory_benchmark.py [OPTIONS]

Benchmark Options:
    --operation MODE
        Benchmark operation mode. Default: roundtrip
        Available: roundtrip, serialize, deserialize

    --benchmarks BENCHMARK_LIST
        Comma-separated list of benchmarks to run. Default: all
        Available: dict, large_dict, dict_group, tuple, large_tuple,
                   large_float_tuple, large_boolean_tuple, list, large_list, complex

    --serializers SERIALIZER_LIST
        Comma-separated list of serializers to benchmark. Default: all
        Available: fory, pickle, msgpack
        Example: --serializers fory,pickle

    --no-ref
        Disable reference tracking for Fory (enabled by default)

    --warmup N
        Number of warmup iterations (default: 3)

    --iterations N
        Number of benchmark iterations (default: 20)

    --repeat N
        Number of times to repeat each iteration (default: 5)

    --number N
        Number of times to call function per measurement (inner loop, default: 1000)

    --help
        Show help message and exit

Examples:
    # Run all benchmarks with all serializers
    python fory_benchmark.py

    # Benchmark serialize only
    python fory_benchmark.py --operation serialize

    # Benchmark deserialize only
    python fory_benchmark.py --operation deserialize

    # Run specific benchmarks with both serializers
    python fory_benchmark.py --benchmarks dict,large_dict,complex

    # Compare only Fory performance
    python fory_benchmark.py --serializers fory

    # Compare only Pickle performance
    python fory_benchmark.py --serializers pickle

    # Compare only Msgpack performance
    python fory_benchmark.py --serializers msgpack

    # Run without reference tracking for Fory
    python fory_benchmark.py --no-ref

    # Run with more iterations for better accuracy
    python fory_benchmark.py --iterations 50 --repeat 10

    # Debug with pure Python mode
    python fory_benchmark.py --disable-cython --benchmarks dict
"""

import argparse
import array
from dataclasses import dataclass, is_dataclass
import datetime
import pickle
import random
import statistics
import sys
import timeit
from typing import Any, Dict, List
import pyfory

try:
    import msgpack
except ImportError:
    msgpack = None


# The benchmark case is rewritten from pyperformance bm_pickle
# https://github.com/python/pyperformance/blob/main/pyperformance/data-files/benchmarks/bm_pickle/run_benchmark.py
BENCHMARK_RANDOM_SEED = 5

DICT = {
    "ads_flags": 0,
    "age": 18,
    "birthday": datetime.date(1980, 5, 7),
    "bulletin_count": 0,
    "comment_count": 0,
    "country": "BR",
    "encrypted_id": "G9urXXAJwjE",
    "favorite_count": 9,
    "first_name": "",
    "flags": 412317970704,
    "friend_count": 0,
    "gender": "m",
    "gender_for_display": "Male",
    "id": 302935349,
    "is_custom_profile_icon": 0,
    "last_name": "",
    "locale_preference": "pt_BR",
    "member": 0,
    "tags": ["a", "b", "c", "d", "e", "f", "g"],
    "profile_foo_id": 827119638,
    "secure_encrypted_id": "Z_xxx2dYx3t4YAdnmfgyKw",
    "session_number": 2,
    "signup_id": "201-19225-223",
    "status": "A",
    "theme": 1,
    "time_created": 1225237014,
    "time_updated": 1233134493,
    "unread_message_count": 0,
    "user_group": "0",
    "username": "collinwinter",
    "play_count": 9,
    "view_count": 7,
    "zip": "",
}
LARGE_DICT = {str(i): i for i in range(2**10 + 1)}

TUPLE = (
    [
        265867233,
        265868503,
        265252341,
        265243910,
        265879514,
        266219766,
        266021701,
        265843726,
        265592821,
        265246784,
        265853180,
        45526486,
        265463699,
        265848143,
        265863062,
        265392591,
        265877490,
        265823665,
        265828884,
        265753032,
    ],
    60,
)
LARGE_TUPLE = tuple(range(2**8 + 1))
benchmark_random = random.Random(BENCHMARK_RANDOM_SEED)
LARGE_FLOAT_TUPLE = tuple(benchmark_random.random() * 10000 for _ in range(2**8 + 1))
LARGE_BOOLEAN_TUPLE = tuple(benchmark_random.random() > 0.5 for _ in range(2**8 + 1))


LIST = [[list(range(10)), list(range(10))] for _ in range(10)]
LARGE_LIST = [i for i in range(2**8 + 1)]


def mutate_dict(orig_dict, random_source):
    new_dict = dict(orig_dict)
    for key, value in new_dict.items():
        rand_val = random_source.random() * sys.maxsize
        if isinstance(key, (int, bytes, str)):
            new_dict[key] = type(key)(rand_val)
    return new_dict


random_source = random.Random(BENCHMARK_RANDOM_SEED)
DICT_GROUP = [mutate_dict(DICT, random_source) for _ in range(3)]


@dataclass
class ComplexObject1:
    f1: Any = None
    f2: str = None
    f3: List[str] = None
    f4: Dict[pyfory.int8, pyfory.int32] = None
    f5: pyfory.int8 = None
    f6: pyfory.int16 = None
    f7: pyfory.int32 = None
    f8: pyfory.int64 = None
    f9: pyfory.float32 = None
    f10: pyfory.float64 = None
    f11: pyfory.int16_array = None
    f12: List[pyfory.int16] = None


@dataclass
class ComplexObject2:
    f1: Any
    f2: Dict[pyfory.int8, pyfory.int32]


COMPLEX_OBJECT = ComplexObject1(
    f1=ComplexObject2(f1=True, f2={-1: 2}),
    f2="abc",
    f3=["abc", "abc"],
    f4={1: 2},
    f5=2**7 - 1,
    f6=2**15 - 1,
    f7=2**31 - 1,
    f8=2**63 - 1,
    f9=1.0 / 2,
    f10=1 / 3.0,
    f11=array.array("h", [-1, 4]),
    f12=[-1, 4],
)

# Global fory instances
fory_with_ref = pyfory.Fory(ref=True)
fory_without_ref = pyfory.Fory(ref=False)

# Register all custom types on both instances
for fory_instance in (fory_with_ref, fory_without_ref):
    fory_instance.register_type(ComplexObject1)
    fory_instance.register_type(ComplexObject2)


def fory_roundtrip(ref, obj):
    fory = fory_with_ref if ref else fory_without_ref
    binary = fory.serialize(obj)
    fory.deserialize(binary)


def fory_serialize(ref, obj):
    fory = fory_with_ref if ref else fory_without_ref
    fory.serialize(obj)


def fory_deserialize(ref, binary):
    fory = fory_with_ref if ref else fory_without_ref
    fory.deserialize(binary)


def pickle_roundtrip(obj):
    binary = pickle.dumps(obj)
    pickle.loads(binary)


def pickle_serialize(obj):
    pickle.dumps(obj)


def pickle_deserialize(binary):
    pickle.loads(binary)


def msgpack_roundtrip(obj):
    binary = msgpack.dumps(obj, use_bin_type=True)
    msgpack.loads(binary, raw=False, strict_map_key=False)


def msgpack_roundtrip_dataclass(obj):
    payload = make_msgpack_compatible(obj)
    binary = msgpack.dumps(payload, use_bin_type=True)
    restored = msgpack.loads(binary, raw=False, strict_map_key=False)
    _restore_dataclass_from_template(restored, obj)


def msgpack_serialize(obj):
    msgpack.dumps(obj, use_bin_type=True)


def msgpack_serialize_dataclass(obj):
    payload = make_msgpack_compatible(obj)
    msgpack.dumps(payload, use_bin_type=True)


def msgpack_deserialize(binary):
    msgpack.loads(binary, raw=False, strict_map_key=False)


def msgpack_deserialize_dataclass(binary, dataclass_template):
    restored = msgpack.loads(binary, raw=False, strict_map_key=False)
    _restore_dataclass_from_template(restored, dataclass_template)


def make_msgpack_compatible(obj):
    if isinstance(obj, datetime.date):
        return obj.isoformat()
    if isinstance(obj, array.array):
        return obj.tolist()
    if is_dataclass(obj):
        return {k: make_msgpack_compatible(v) for k, v in vars(obj).items()}
    if isinstance(obj, dict):
        return {
            make_msgpack_compatible(k): make_msgpack_compatible(v)
            for k, v in obj.items()
        }
    if isinstance(obj, list):
        return [make_msgpack_compatible(v) for v in obj]
    if isinstance(obj, tuple):
        return tuple(make_msgpack_compatible(v) for v in obj)
    return obj


def _restore_dataclass_from_template(value, template):
    if not is_dataclass(template):
        return value
    if not isinstance(value, dict):
        return value

    kwargs = {}
    for field in template.__dataclass_fields__.values():
        field_value = value.get(field.name)
        template_value = getattr(template, field.name, None)
        if is_dataclass(template_value):
            kwargs[field.name] = _restore_dataclass_from_template(
                field_value, template_value
            )
        else:
            kwargs[field.name] = field_value
    return type(template)(**kwargs)


def build_fory_benchmark_case(operation: str, ref: bool, obj):
    if operation == "serialize":
        return fory_serialize, (ref, obj)
    if operation == "deserialize":
        fory = fory_with_ref if ref else fory_without_ref
        return fory_deserialize, (ref, fory.serialize(obj))
    return fory_roundtrip, (ref, obj)


def build_pickle_benchmark_case(operation: str, obj):
    if operation == "serialize":
        return pickle_serialize, (obj,)
    if operation == "deserialize":
        return pickle_deserialize, (pickle.dumps(obj),)
    return pickle_roundtrip, (obj,)


def build_msgpack_benchmark_case(operation: str, obj):
    if operation == "serialize":
        if is_dataclass(obj):
            return msgpack_serialize_dataclass, (obj,)
        return msgpack_serialize, (obj,)
    if operation == "deserialize":
        if is_dataclass(obj):
            return msgpack_deserialize_dataclass, (
                msgpack.dumps(make_msgpack_compatible(obj), use_bin_type=True),
                obj,
            )
        return msgpack_deserialize, (
            msgpack.dumps(obj, use_bin_type=True),
        )
    if is_dataclass(obj):
        return msgpack_roundtrip_dataclass, (obj,)
    return msgpack_roundtrip, (obj,)


def benchmark_args():
    """Parse command line arguments"""
    parser = argparse.ArgumentParser(description="Fory vs Pickle vs Msgpack Benchmark")
    parser.add_argument(
        "--operation",
        type=str,
        default="roundtrip",
        choices=["roundtrip", "serialize", "deserialize"],
        help="Benchmark operation mode: roundtrip, serialize, deserialize (default: roundtrip)",
    )
    parser.add_argument(
        "--no-ref",
        action="store_true",
        default=False,
        help="Disable reference tracking for Fory",
    )
    parser.add_argument(
        "--disable-cython",
        action="store_true",
        default=False,
        help="Use pure Python mode for Fory",
    )
    parser.add_argument(
        "--benchmarks",
        type=str,
        default="all",
        help="Comma-separated list of benchmarks to run. Available: dict, large_dict, "
        "dict_group, tuple, large_tuple, large_float_tuple, large_boolean_tuple, "
        "list, large_list, complex. Default: all",
    )
    parser.add_argument(
        "--serializers",
        type=str,
        default="all",
        help="Comma-separated list of serializers to benchmark. Available: fory, pickle, msgpack. Default: all",
    )
    parser.add_argument(
        "--warmup",
        type=int,
        default=3,
        help="Number of warmup iterations (default: 3)",
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=20,
        help="Number of benchmark iterations (default: 20)",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=5,
        help="Number of times to repeat each iteration (default: 5)",
    )
    parser.add_argument(
        "--number",
        type=int,
        default=1000,
        help="Number of times to call function per measurement (inner loop, default: 1000)",
    )
    return parser.parse_args()


def run_benchmark(func, *args, warmup=3, iterations=20, repeat=5, number=10000):
    """Run a benchmark and return timing statistics

    Args:
        func: Function to benchmark
        *args: Arguments to pass to func
        warmup: Number of warmup iterations
        iterations: Number of measurement iterations
        repeat: Number of times to repeat each measurement
        number: Number of times to call func per measurement (inner loop)

    Returns:
        (mean_time_per_call, stdev_time_per_call)
    """
    # Warmup
    for _ in range(warmup):
        for _ in range(number):
            func(*args)

    # Benchmark - run func 'number' times per measurement
    times = []
    for _ in range(iterations):
        timer = timeit.Timer(lambda: func(*args))
        iteration_times = timer.repeat(repeat=repeat, number=number)
        # Convert total time to time per call
        times.extend([t / number for t in iteration_times])

    mean = statistics.mean(times)
    stdev = statistics.stdev(times) if len(times) > 1 else 0
    return mean, stdev


def format_time(seconds):
    """Format time in human-readable units"""
    if seconds < 1e-6:
        return f"{seconds * 1e9:.2f} ns"
    elif seconds < 1e-3:
        return f"{seconds * 1e6:.2f} us"
    elif seconds < 1:
        return f"{seconds * 1e3:.2f} ms"
    else:
        return f"{seconds:.2f} s"


def micro_benchmark():
    args = benchmark_args()
    ref = not args.no_ref

    # Define benchmark data
    benchmark_data = {
        "dict": DICT,
        "large_dict": LARGE_DICT,
        "dict_group": DICT_GROUP,
        "tuple": TUPLE,
        "large_tuple": LARGE_TUPLE,
        "large_float_tuple": LARGE_FLOAT_TUPLE,
        "large_boolean_tuple": LARGE_BOOLEAN_TUPLE,
        "list": LIST,
        "large_list": LARGE_LIST,
        "complex": COMPLEX_OBJECT,
    }

    # Determine which benchmarks to run
    if args.benchmarks == "all":
        selected_benchmarks = list(benchmark_data.keys())
    else:
        selected_benchmarks = [b.strip() for b in args.benchmarks.split(",")]
        # Validate benchmark names
        invalid = [b for b in selected_benchmarks if b not in benchmark_data]
        if invalid:
            print(f"Error: Invalid benchmark names: {', '.join(invalid)}")
            print(f"Available benchmarks: {', '.join(benchmark_data.keys())}")
            sys.exit(1)

    # Determine which serializers to run
    available_serializers = {"fory", "pickle", "msgpack"}
    if args.serializers == "all":
        selected_serializers = ["fory", "pickle"]
        if msgpack is not None:
            selected_serializers.append("msgpack")
    else:
        selected_serializers = [s.strip() for s in args.serializers.split(",")]
        # Validate serializer names
        invalid = [s for s in selected_serializers if s not in available_serializers]
        if invalid:
            print(f"Error: Invalid serializer names: {', '.join(invalid)}")
            print(f"Available serializers: {', '.join(available_serializers)}")
            sys.exit(1)
        if "msgpack" in selected_serializers and msgpack is None:
            print("Error: msgpack is not installed.")
            print("Install it with: pip install msgpack")
            sys.exit(1)

    msgpack_data = {}
    if "msgpack" in selected_serializers:
        msgpack_data = {
            benchmark_name: (
                data if is_dataclass(data) else make_msgpack_compatible(data)
            )
            for benchmark_name, data in benchmark_data.items()
        }

    print(
        f"\nBenchmarking {len(selected_benchmarks)} benchmark(s) with {len(selected_serializers)} serializer(s)"
    )
    print(f"Operation: {args.operation}")
    print(
        f"Warmup: {args.warmup}, Iterations: {args.iterations}, Repeat: {args.repeat}, Inner loop: {args.number}"
    )
    print(f"Fory reference tracking: {'enabled' if ref else 'disabled'}")
    print("=" * 80)

    # Run selected benchmarks with selected serializers
    results = []
    for benchmark_name in selected_benchmarks:
        data = benchmark_data[benchmark_name]
        benchmark_number = (
            max(1, args.number // 10) if benchmark_name == "large_dict" else args.number
        )

        if "fory" in selected_serializers:
            print(
                f"\nRunning fory_{benchmark_name}_{args.operation}...",
                end=" ",
                flush=True,
            )
            fory_func, fory_args = build_fory_benchmark_case(args.operation, ref, data)
            mean, stdev = run_benchmark(
                fory_func,
                *fory_args,
                warmup=args.warmup,
                iterations=args.iterations,
                repeat=args.repeat,
                number=benchmark_number,
            )
            results.append(("fory", benchmark_name, mean, stdev))
            print(f"{format_time(mean)} ± {format_time(stdev)}")

        if "pickle" in selected_serializers:
            print(
                f"Running pickle_{benchmark_name}_{args.operation}...",
                end=" ",
                flush=True,
            )
            pickle_func, pickle_args = build_pickle_benchmark_case(args.operation, data)
            mean, stdev = run_benchmark(
                pickle_func,
                *pickle_args,
                warmup=args.warmup,
                iterations=args.iterations,
                repeat=args.repeat,
                number=benchmark_number,
            )
            results.append(("pickle", benchmark_name, mean, stdev))
            print(f"{format_time(mean)} ± {format_time(stdev)}")

        if "msgpack" in selected_serializers:
            print(
                f"Running msgpack_{benchmark_name}_{args.operation}...",
                end=" ",
                flush=True,
            )
            msgpack_func, msgpack_args = build_msgpack_benchmark_case(
                args.operation, msgpack_data[benchmark_name]
            )
            mean, stdev = run_benchmark(
                msgpack_func,
                *msgpack_args,
                warmup=args.warmup,
                iterations=args.iterations,
                repeat=args.repeat,
                number=benchmark_number,
            )
            results.append(("msgpack", benchmark_name, mean, stdev))
            print(f"{format_time(mean)} ± {format_time(stdev)}")

    # Print summary
    print("\n" + "=" * 80)
    print("SUMMARY")
    print("=" * 80)
    print(f"{'Serializer':<15} {'Benchmark':<25} {'Mean':<20} {'Std Dev':<20}")
    print("-" * 80)
    for serializer, benchmark, mean, stdev in results:
        print(
            f"{serializer:<15} {benchmark:<25} {format_time(mean):<20} {format_time(stdev):<20}"
        )

    # Calculate speedup if fory and at least one baseline serializer were tested.
    if "fory" in selected_serializers:
        for baseline in selected_serializers:
            if baseline == "fory":
                continue
            print("\n" + "=" * 80)
            print(f"SPEEDUP (Fory vs {baseline.capitalize()})")
            print("=" * 80)
            print(
                f"{'Benchmark':<25} {'Fory':<20} {baseline.capitalize():<20} {'Speedup':<20}"
            )
            print("-" * 80)

            for benchmark_name in selected_benchmarks:
                fory_result = next(
                    (r for r in results if r[0] == "fory" and r[1] == benchmark_name),
                    None,
                )
                baseline_result = next(
                    (r for r in results if r[0] == baseline and r[1] == benchmark_name),
                    None,
                )

                if fory_result and baseline_result:
                    fory_mean = fory_result[2]
                    baseline_mean = baseline_result[2]
                    speedup = baseline_mean / fory_mean
                    speedup_str = (
                        f"{speedup:.2f}x"
                        if speedup >= 1
                        else f"{1 / speedup:.2f}x slower"
                    )
                    print(
                        f"{benchmark_name:<25} {format_time(fory_mean):<20} {format_time(baseline_mean):<20} {speedup_str:<20}"
                    )


if __name__ == "__main__":
    micro_benchmark()
