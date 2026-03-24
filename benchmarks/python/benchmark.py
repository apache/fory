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

"""Comprehensive Python benchmark suite for C++ parity benchmark objects.

This script mirrors `benchmarks/cpp/benchmark.cc` coverage and benchmarks:
- Data types: Struct, Sample, MediaContent and corresponding *List variants.
- Operations: serialize / deserialize.
- Serializers: fory / pickle / protobuf.

Results are written as JSON and consumed by `benchmark_report.py`.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
import pickle
import platform
import statistics
import sys
import timeit
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Tuple

import pyfory


LIST_SIZE = 5
DATA_TYPE_ORDER = [
    "struct",
    "sample",
    "mediacontent",
    "structlist",
    "samplelist",
    "mediacontentlist",
]
SERIALIZER_ORDER = ["fory", "pickle", "protobuf"]
OPERATION_ORDER = ["serialize", "deserialize"]

DATA_LABELS = {
    "struct": "Struct",
    "sample": "Sample",
    "mediacontent": "MediaContent",
    "structlist": "StructList",
    "samplelist": "SampleList",
    "mediacontentlist": "MediaContentList",
}
SERIALIZER_LABELS = {
    "fory": "Fory",
    "pickle": "Pickle",
    "protobuf": "Protobuf",
}


@dataclass
class NumericStruct:
    f1: int
    f2: int
    f3: int
    f4: int
    f5: int
    f6: int
    f7: int
    f8: int


@dataclass
class Sample:
    int_value: int
    long_value: int
    float_value: float
    double_value: float
    short_value: int
    char_value: int
    boolean_value: bool
    int_value_boxed: int
    long_value_boxed: int
    float_value_boxed: float
    double_value_boxed: float
    short_value_boxed: int
    char_value_boxed: int
    boolean_value_boxed: bool
    int_array: List[int]
    long_array: List[int]
    float_array: List[float]
    double_array: List[float]
    short_array: List[int]
    char_array: List[int]
    boolean_array: List[bool]
    string: str


@dataclass
class Media:
    uri: str
    title: str
    width: int
    height: int
    format: str
    duration: int
    size: int
    bitrate: int
    has_bitrate: bool
    persons: List[str]
    player: int
    copyright: str


@dataclass
class Image:
    uri: str
    title: str
    width: int
    height: int
    size: int


@dataclass
class MediaContent:
    media: Media
    images: List[Image]


@dataclass
class StructList:
    struct_list: List[NumericStruct]


@dataclass
class SampleList:
    sample_list: List[Sample]


@dataclass
class MediaContentList:
    media_content_list: List[MediaContent]


def create_numeric_struct() -> NumericStruct:
    return NumericStruct(
        f1=-12345,
        f2=987654321,
        f3=-31415,
        f4=27182818,
        f5=-32000,
        f6=1000000,
        f7=-999999999,
        f8=42,
    )


def create_sample() -> Sample:
    return Sample(
        int_value=123,
        long_value=1230000,
        float_value=12.345,
        double_value=1.234567,
        short_value=12345,
        char_value=ord("!"),
        boolean_value=True,
        int_value_boxed=321,
        long_value_boxed=3210000,
        float_value_boxed=54.321,
        double_value_boxed=7.654321,
        short_value_boxed=32100,
        char_value_boxed=ord("$"),
        boolean_value_boxed=False,
        int_array=[-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
        long_array=[-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400],
        float_array=[-12.34, -12.3, -12.0, -1.0, 0.0, 1.0, 12.0, 12.3, 12.34],
        double_array=[-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234],
        short_array=[-1234, -123, -12, -1, 0, 1, 12, 123, 1234],
        char_array=[ord(c) for c in "asdfASDF"],
        boolean_array=[True, False, False, True],
        string="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
    )


def create_media_content() -> MediaContent:
    media = Media(
        uri="http://javaone.com/keynote.ogg",
        title="",
        width=641,
        height=481,
        format="video/theora\u1234",
        duration=18000001,
        size=58982401,
        bitrate=0,
        has_bitrate=False,
        persons=["Bill Gates, Jr.", "Steven Jobs"],
        player=1,
        copyright="Copyright (c) 2009, Scooby Dooby Doo",
    )
    images = [
        Image(
            uri="http://javaone.com/keynote_huge.jpg",
            title="Javaone Keynote\u1234",
            width=32000,
            height=24000,
            size=1,
        ),
        Image(
            uri="http://javaone.com/keynote_large.jpg",
            title="",
            width=1024,
            height=768,
            size=1,
        ),
        Image(
            uri="http://javaone.com/keynote_small.jpg",
            title="",
            width=320,
            height=240,
            size=0,
        ),
    ]
    return MediaContent(media=media, images=images)


def create_struct_list() -> StructList:
    return StructList(struct_list=[create_numeric_struct() for _ in range(LIST_SIZE)])


def create_sample_list() -> SampleList:
    return SampleList(sample_list=[create_sample() for _ in range(LIST_SIZE)])


def create_media_content_list() -> MediaContentList:
    return MediaContentList(
        media_content_list=[create_media_content() for _ in range(LIST_SIZE)]
    )


def create_benchmark_data() -> Dict[str, Any]:
    return {
        "struct": create_numeric_struct(),
        "sample": create_sample(),
        "mediacontent": create_media_content(),
        "structlist": create_struct_list(),
        "samplelist": create_sample_list(),
        "mediacontentlist": create_media_content_list(),
    }


def load_bench_pb2(proto_dir: Path):
    bench_pb2_path = proto_dir / "bench_pb2.py"
    if not bench_pb2_path.exists():
        raise FileNotFoundError(
            f"{bench_pb2_path} does not exist. Run benchmarks/python/run.sh first to generate protobuf bindings."
        )
    proto_dir_abs = str(proto_dir.resolve())
    if proto_dir_abs not in sys.path:
        sys.path.insert(0, proto_dir_abs)
    import bench_pb2  # type: ignore

    return bench_pb2


def to_pb_struct(bench_pb2, obj: NumericStruct):
    pb = bench_pb2.Struct()
    pb.f1 = obj.f1
    pb.f2 = obj.f2
    pb.f3 = obj.f3
    pb.f4 = obj.f4
    pb.f5 = obj.f5
    pb.f6 = obj.f6
    pb.f7 = obj.f7
    pb.f8 = obj.f8
    return pb


def from_pb_struct(pb_obj) -> NumericStruct:
    return NumericStruct(
        f1=pb_obj.f1,
        f2=pb_obj.f2,
        f3=pb_obj.f3,
        f4=pb_obj.f4,
        f5=pb_obj.f5,
        f6=pb_obj.f6,
        f7=pb_obj.f7,
        f8=pb_obj.f8,
    )


def to_pb_sample(bench_pb2, obj: Sample):
    pb = bench_pb2.Sample()
    pb.int_value = obj.int_value
    pb.long_value = obj.long_value
    pb.float_value = obj.float_value
    pb.double_value = obj.double_value
    pb.short_value = obj.short_value
    pb.char_value = obj.char_value
    pb.boolean_value = obj.boolean_value
    pb.int_value_boxed = obj.int_value_boxed
    pb.long_value_boxed = obj.long_value_boxed
    pb.float_value_boxed = obj.float_value_boxed
    pb.double_value_boxed = obj.double_value_boxed
    pb.short_value_boxed = obj.short_value_boxed
    pb.char_value_boxed = obj.char_value_boxed
    pb.boolean_value_boxed = obj.boolean_value_boxed
    pb.int_array.extend(obj.int_array)
    pb.long_array.extend(obj.long_array)
    pb.float_array.extend(obj.float_array)
    pb.double_array.extend(obj.double_array)
    pb.short_array.extend(obj.short_array)
    pb.char_array.extend(obj.char_array)
    pb.boolean_array.extend(obj.boolean_array)
    pb.string = obj.string
    return pb


def from_pb_sample(pb_obj) -> Sample:
    return Sample(
        int_value=pb_obj.int_value,
        long_value=pb_obj.long_value,
        float_value=pb_obj.float_value,
        double_value=pb_obj.double_value,
        short_value=pb_obj.short_value,
        char_value=pb_obj.char_value,
        boolean_value=pb_obj.boolean_value,
        int_value_boxed=pb_obj.int_value_boxed,
        long_value_boxed=pb_obj.long_value_boxed,
        float_value_boxed=pb_obj.float_value_boxed,
        double_value_boxed=pb_obj.double_value_boxed,
        short_value_boxed=pb_obj.short_value_boxed,
        char_value_boxed=pb_obj.char_value_boxed,
        boolean_value_boxed=pb_obj.boolean_value_boxed,
        int_array=list(pb_obj.int_array),
        long_array=list(pb_obj.long_array),
        float_array=list(pb_obj.float_array),
        double_array=list(pb_obj.double_array),
        short_array=list(pb_obj.short_array),
        char_array=list(pb_obj.char_array),
        boolean_array=list(pb_obj.boolean_array),
        string=pb_obj.string,
    )


def to_pb_image(bench_pb2, obj: Image):
    pb = bench_pb2.Image()
    pb.uri = obj.uri
    if obj.title:
        pb.title = obj.title
    pb.width = obj.width
    pb.height = obj.height
    pb.size = obj.size
    return pb


def from_pb_image(pb_obj) -> Image:
    title = pb_obj.title if pb_obj.HasField("title") else ""
    return Image(
        uri=pb_obj.uri,
        title=title,
        width=pb_obj.width,
        height=pb_obj.height,
        size=pb_obj.size,
    )


def to_pb_media(bench_pb2, obj: Media):
    pb = bench_pb2.Media()
    pb.uri = obj.uri
    if obj.title:
        pb.title = obj.title
    pb.width = obj.width
    pb.height = obj.height
    pb.format = obj.format
    pb.duration = obj.duration
    pb.size = obj.size
    pb.bitrate = obj.bitrate
    pb.has_bitrate = obj.has_bitrate
    pb.persons.extend(obj.persons)
    pb.player = obj.player
    pb.copyright = obj.copyright
    return pb


def from_pb_media(pb_obj) -> Media:
    title = pb_obj.title if pb_obj.HasField("title") else ""
    return Media(
        uri=pb_obj.uri,
        title=title,
        width=pb_obj.width,
        height=pb_obj.height,
        format=pb_obj.format,
        duration=pb_obj.duration,
        size=pb_obj.size,
        bitrate=pb_obj.bitrate,
        has_bitrate=pb_obj.has_bitrate,
        persons=list(pb_obj.persons),
        player=pb_obj.player,
        copyright=pb_obj.copyright,
    )


def to_pb_mediacontent(bench_pb2, obj: MediaContent):
    pb = bench_pb2.MediaContent()
    pb.media.CopyFrom(to_pb_media(bench_pb2, obj.media))
    for image in obj.images:
        pb.images.add().CopyFrom(to_pb_image(bench_pb2, image))
    return pb


def from_pb_mediacontent(pb_obj) -> MediaContent:
    return MediaContent(
        media=from_pb_media(pb_obj.media),
        images=[from_pb_image(img) for img in pb_obj.images],
    )


def to_pb_structlist(bench_pb2, obj: StructList):
    pb = bench_pb2.StructList()
    for item in obj.struct_list:
        pb.struct_list.add().CopyFrom(to_pb_struct(bench_pb2, item))
    return pb


def from_pb_structlist(pb_obj) -> StructList:
    return StructList(struct_list=[from_pb_struct(item) for item in pb_obj.struct_list])


def to_pb_samplelist(bench_pb2, obj: SampleList):
    pb = bench_pb2.SampleList()
    for item in obj.sample_list:
        pb.sample_list.add().CopyFrom(to_pb_sample(bench_pb2, item))
    return pb


def from_pb_samplelist(pb_obj) -> SampleList:
    return SampleList(sample_list=[from_pb_sample(item) for item in pb_obj.sample_list])


def to_pb_mediacontentlist(bench_pb2, obj: MediaContentList):
    pb = bench_pb2.MediaContentList()
    for item in obj.media_content_list:
        pb.media_content_list.add().CopyFrom(to_pb_mediacontent(bench_pb2, item))
    return pb


def from_pb_mediacontentlist(pb_obj) -> MediaContentList:
    return MediaContentList(
        media_content_list=[
            from_pb_mediacontent(item) for item in pb_obj.media_content_list
        ]
    )


PROTO_CONVERTERS = {
    "struct": (to_pb_struct, from_pb_struct, "Struct"),
    "sample": (to_pb_sample, from_pb_sample, "Sample"),
    "mediacontent": (to_pb_mediacontent, from_pb_mediacontent, "MediaContent"),
    "structlist": (to_pb_structlist, from_pb_structlist, "StructList"),
    "samplelist": (to_pb_samplelist, from_pb_samplelist, "SampleList"),
    "mediacontentlist": (
        to_pb_mediacontentlist,
        from_pb_mediacontentlist,
        "MediaContentList",
    ),
}


def build_fory() -> pyfory.Fory:
    fory = pyfory.Fory(xlang=True, compatible=True, ref=False)
    fory.register_type(NumericStruct, type_id=1)
    fory.register_type(Sample, type_id=2)
    fory.register_type(Media, type_id=3)
    fory.register_type(Image, type_id=4)
    fory.register_type(MediaContent, type_id=5)
    fory.register_type(StructList, type_id=6)
    fory.register_type(SampleList, type_id=7)
    fory.register_type(MediaContentList, type_id=8)
    return fory


def run_benchmark(
    func: Callable[..., Any],
    args: Tuple[Any, ...],
    *,
    warmup: int,
    iterations: int,
    repeat: int,
    number: int,
) -> Tuple[float, float]:
    for _ in range(warmup):
        for _ in range(number):
            func(*args)

    samples: List[float] = []
    for _ in range(iterations):
        timer = timeit.Timer(lambda: func(*args))
        loop_times = timer.repeat(repeat=repeat, number=number)
        samples.extend([time_total / number for time_total in loop_times])

    mean = statistics.mean(samples)
    stdev = statistics.stdev(samples) if len(samples) > 1 else 0.0
    return mean, stdev


def format_time(seconds: float) -> str:
    if seconds < 1e-6:
        return f"{seconds * 1e9:.2f} ns"
    if seconds < 1e-3:
        return f"{seconds * 1e6:.2f} us"
    if seconds < 1:
        return f"{seconds * 1e3:.2f} ms"
    return f"{seconds:.2f} s"


def fory_serialize(fory: pyfory.Fory, obj: Any) -> None:
    fory.serialize(obj)


def fory_deserialize(fory: pyfory.Fory, binary: bytes) -> None:
    fory.deserialize(binary)


def pickle_serialize(obj: Any) -> None:
    pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL)


def pickle_deserialize(binary: bytes) -> None:
    pickle.loads(binary)


def protobuf_serialize(bench_pb2, datatype: str, obj: Any) -> None:
    to_pb, _, _ = PROTO_CONVERTERS[datatype]
    pb_obj = to_pb(bench_pb2, obj)
    pb_obj.SerializeToString()


def protobuf_deserialize(bench_pb2, datatype: str, binary: bytes) -> None:
    _, from_pb, pb_type_name = PROTO_CONVERTERS[datatype]
    pb_cls = getattr(bench_pb2, pb_type_name)
    pb_obj = pb_cls()
    pb_obj.ParseFromString(binary)
    from_pb(pb_obj)


def benchmark_name(serializer: str, datatype: str, operation: str) -> str:
    return f"BM_{SERIALIZER_LABELS[serializer]}_{DATA_LABELS[datatype]}_{operation.capitalize()}"


def build_case(
    serializer: str,
    operation: str,
    datatype: str,
    obj: Any,
    *,
    fory: pyfory.Fory,
    bench_pb2,
) -> Tuple[Callable[..., Any], Tuple[Any, ...]]:
    if serializer == "fory":
        if operation == "serialize":
            return fory_serialize, (fory, obj)
        return fory_deserialize, (fory, fory.serialize(obj))

    if serializer == "pickle":
        if operation == "serialize":
            return pickle_serialize, (obj,)
        return pickle_deserialize, (
            pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL),
        )

    if serializer == "protobuf":
        if operation == "serialize":
            return protobuf_serialize, (bench_pb2, datatype, obj)
        to_pb, _, _ = PROTO_CONVERTERS[datatype]
        pb_binary = to_pb(bench_pb2, obj).SerializeToString()
        return protobuf_deserialize, (bench_pb2, datatype, pb_binary)

    raise ValueError(f"Unsupported serializer: {serializer}")


def calculate_serialized_sizes(
    benchmark_data: Dict[str, Any],
    selected_datatypes: Iterable[str],
    *,
    fory: pyfory.Fory,
    bench_pb2,
) -> Dict[str, Dict[str, int]]:
    sizes: Dict[str, Dict[str, int]] = {}
    for datatype in selected_datatypes:
        obj = benchmark_data[datatype]
        datatype_sizes: Dict[str, int] = {}

        datatype_sizes["fory"] = len(fory.serialize(obj))
        datatype_sizes["pickle"] = len(
            pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL)
        )

        to_pb, _, _ = PROTO_CONVERTERS[datatype]
        datatype_sizes["protobuf"] = len(to_pb(bench_pb2, obj).SerializeToString())

        sizes[datatype] = datatype_sizes
    return sizes


def parse_csv_list(value: str, allowed: Iterable[str], default: List[str]) -> List[str]:
    if value == "all":
        return list(default)
    selected = [item.strip().lower() for item in value.split(",") if item.strip()]
    invalid = [item for item in selected if item not in allowed]
    if invalid:
        raise ValueError(
            f"Invalid values: {', '.join(invalid)}. Allowed: {', '.join(sorted(allowed))}"
        )
    ordered = [item for item in default if item in selected]
    return ordered


def benchmark_number(base_number: int, datatype: str) -> int:
    scale = {
        "struct": 1.0,
        "sample": 0.5,
        "mediacontent": 0.4,
        "structlist": 0.25,
        "samplelist": 0.2,
        "mediacontentlist": 0.15,
    }
    return max(1, int(base_number * scale.get(datatype, 1.0)))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Comprehensive Fory/Pickle/Protobuf benchmark for Python"
    )
    parser.add_argument(
        "--operation",
        default="all",
        choices=["all", "serialize", "deserialize"],
        help="Benchmark operation: all, serialize, deserialize",
    )
    parser.add_argument(
        "--data",
        default="all",
        help="Comma-separated data types: struct,sample,mediacontent,structlist,samplelist,mediacontentlist or all",
    )
    parser.add_argument(
        "--serializer",
        default="all",
        help="Comma-separated serializers: fory,pickle,protobuf or all",
    )
    parser.add_argument(
        "--warmup",
        type=int,
        default=3,
        help="Warmup iterations (default: 3)",
    )
    parser.add_argument(
        "--iterations",
        type=int,
        default=15,
        help="Measurement iterations (default: 15)",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=5,
        help="Timer repeat count per iteration (default: 5)",
    )
    parser.add_argument(
        "--number",
        type=int,
        default=1000,
        help="Function calls per timer measurement (default: 1000)",
    )
    parser.add_argument(
        "--proto-dir",
        default=str(Path(__file__).with_name("proto")),
        help="Directory containing generated bench_pb2.py",
    )
    parser.add_argument(
        "--output-json",
        default=str(Path(__file__).with_name("results") / "benchmark_results.json"),
        help="Output JSON file path",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    proto_dir = Path(args.proto_dir)
    bench_pb2 = load_bench_pb2(proto_dir)

    selected_datatypes = parse_csv_list(args.data, DATA_TYPE_ORDER, DATA_TYPE_ORDER)
    selected_serializers = parse_csv_list(
        args.serializer, SERIALIZER_ORDER, SERIALIZER_ORDER
    )
    selected_operations = (
        OPERATION_ORDER if args.operation == "all" else [args.operation]
    )

    benchmark_data = create_benchmark_data()
    fory = build_fory()

    print(
        f"Benchmarking {len(selected_datatypes)} data type(s), "
        f"{len(selected_serializers)} serializer(s), "
        f"{len(selected_operations)} operation(s)"
    )
    print(
        f"Warmup={args.warmup}, Iterations={args.iterations}, Repeat={args.repeat}, Number={args.number}"
    )
    print("=" * 96)

    results = []

    for datatype in selected_datatypes:
        obj = benchmark_data[datatype]
        call_number = benchmark_number(args.number, datatype)
        for operation in selected_operations:
            for serializer in selected_serializers:
                case_name = benchmark_name(serializer, datatype, operation)
                print(f"Running {case_name} ...", end=" ", flush=True)

                func, func_args = build_case(
                    serializer,
                    operation,
                    datatype,
                    obj,
                    fory=fory,
                    bench_pb2=bench_pb2,
                )
                mean, stdev = run_benchmark(
                    func,
                    func_args,
                    warmup=args.warmup,
                    iterations=args.iterations,
                    repeat=args.repeat,
                    number=call_number,
                )

                results.append(
                    {
                        "name": case_name,
                        "serializer": serializer,
                        "datatype": datatype,
                        "operation": operation,
                        "mean_seconds": mean,
                        "stdev_seconds": stdev,
                        "mean_ns": mean * 1e9,
                        "stdev_ns": stdev * 1e9,
                        "number": call_number,
                    }
                )
                print(f"{format_time(mean)} ± {format_time(stdev)}")

    sizes = calculate_serialized_sizes(
        benchmark_data,
        selected_datatypes,
        fory=fory,
        bench_pb2=bench_pb2,
    )

    output_path = Path(args.output_json)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    payload = {
        "context": {
            "python_version": platform.python_version(),
            "python_implementation": platform.python_implementation(),
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": platform.processor() or "Unknown",
            "enable_fory_debug_output": os.getenv("ENABLE_FORY_DEBUG_OUTPUT", "0"),
            "warmup": args.warmup,
            "iterations": args.iterations,
            "repeat": args.repeat,
            "number": args.number,
            "operations": selected_operations,
            "datatypes": selected_datatypes,
            "serializers": selected_serializers,
            "list_size": LIST_SIZE,
        },
        "benchmarks": results,
        "sizes": sizes,
    }

    with output_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)

    print("=" * 96)
    print(f"Benchmark JSON written to: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
