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
- Data types: NumericStruct, Sample, MediaContent and corresponding *List variants.
- Operations: serialize / deserialize.
- Serializers: fory / protobuf / pickle.

Results are written as JSON and consumed by `benchmark_report.py`.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from enum import IntEnum
import json
import os
import pickle
import platform
import statistics
import sys
import timeit
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Tuple

import numpy as np
import pyfory


LIST_SIZE = 5
SCHEMA_MISMATCH_ENV = "FORY_BENCH_SCHEMA_MISMATCH"
DATA_TYPE_ORDER = [
    "struct",
    "sample",
    "mediacontent",
    "structlist",
    "samplelist",
    "mediacontentlist",
]
SERIALIZER_ORDER = ["fory", "protobuf", "pickle"]
OPERATION_ORDER = ["serialize", "deserialize"]

DATA_LABELS = {
    "struct": "NumericStruct",
    "sample": "Sample",
    "mediacontent": "MediaContent",
    "structlist": "NumericStructList",
    "samplelist": "SampleList",
    "mediacontentlist": "MediaContentList",
}
SERIALIZER_LABELS = {
    "fory": "Fory",
    "protobuf": "Protobuf",
    "pickle": "Pickle",
}


def int32_array(values: Iterable[int]) -> np.ndarray:
    return np.array(list(values), dtype=np.int32)


def int64_array(values: Iterable[int]) -> np.ndarray:
    return np.array(list(values), dtype=np.int64)


def float32_array(values: Iterable[float]) -> np.ndarray:
    return np.array(list(values), dtype=np.float32)


def float64_array(values: Iterable[float]) -> np.ndarray:
    return np.array(list(values), dtype=np.float64)


def bool_array(values: Iterable[bool]) -> np.ndarray:
    return np.array(list(values), dtype=np.bool_)


class Player(IntEnum):
    JAVA = 0
    FLASH = 1


class Size(IntEnum):
    SMALL = 0
    LARGE = 1


@dataclass
class NumericStruct:
    f1: pyfory.Int32 = pyfory.field(id=1)
    f2: pyfory.Int32 = pyfory.field(id=2)
    f3: pyfory.Int32 = pyfory.field(id=3)
    f4: pyfory.Int32 = pyfory.field(id=4)
    f5: pyfory.Int32 = pyfory.field(id=5)
    f6: pyfory.Int32 = pyfory.field(id=6)
    f7: pyfory.Int32 = pyfory.field(id=7)
    f8: pyfory.Int32 = pyfory.field(id=8)
    f9: pyfory.Int32 = pyfory.field(id=9)
    f10: pyfory.Int32 = pyfory.field(id=10)
    f11: pyfory.Int32 = pyfory.field(id=11)
    f12: pyfory.Int32 = pyfory.field(id=12)


@dataclass
class NumericStructV2:
    f1: pyfory.Int64 = pyfory.field(id=1)
    f2: pyfory.Int32 = pyfory.field(id=2)
    f3: pyfory.Int32 = pyfory.field(id=3)
    f4: pyfory.Int32 = pyfory.field(id=4)
    f5: pyfory.Int32 = pyfory.field(id=5)
    f6: pyfory.Int32 = pyfory.field(id=6)
    f7: pyfory.Int32 = pyfory.field(id=7)
    f8: pyfory.Int32 = pyfory.field(id=8)
    f9: pyfory.Int32 = pyfory.field(id=9)
    f10: pyfory.Int32 = pyfory.field(id=10)
    f11: pyfory.Int32 = pyfory.field(id=11)
    f12: pyfory.Int32 = pyfory.field(id=12)


@dataclass
class Sample:
    int_value: pyfory.Int32 = pyfory.field(id=1)
    long_value: pyfory.Int64 = pyfory.field(id=2)
    float_value: pyfory.Float32 = pyfory.field(id=3)
    double_value: pyfory.Float64 = pyfory.field(id=4)
    short_value: pyfory.Int32 = pyfory.field(id=5)
    char_value: pyfory.Int32 = pyfory.field(id=6)
    boolean_value: bool = pyfory.field(id=7)
    int_value_boxed: pyfory.Int32 = pyfory.field(id=8)
    long_value_boxed: pyfory.Int64 = pyfory.field(id=9)
    float_value_boxed: pyfory.Float32 = pyfory.field(id=10)
    double_value_boxed: pyfory.Float64 = pyfory.field(id=11)
    short_value_boxed: pyfory.Int32 = pyfory.field(id=12)
    char_value_boxed: pyfory.Int32 = pyfory.field(id=13)
    boolean_value_boxed: bool = pyfory.field(id=14)
    int_array: pyfory.NDArray[pyfory.Int32] = pyfory.field(id=15)
    long_array: pyfory.NDArray[pyfory.Int64] = pyfory.field(id=16)
    float_array: pyfory.NDArray[pyfory.Float32] = pyfory.field(id=17)
    double_array: pyfory.NDArray[pyfory.Float64] = pyfory.field(id=18)
    short_array: pyfory.NDArray[pyfory.Int32] = pyfory.field(id=19)
    char_array: pyfory.NDArray[pyfory.Int32] = pyfory.field(id=20)
    boolean_array: pyfory.NDArray[bool] = pyfory.field(id=21)
    string: str = pyfory.field(id=22)


@dataclass
class SampleV2:
    int_value: pyfory.Int64 = pyfory.field(id=1)
    long_value: pyfory.Int64 = pyfory.field(id=2)
    float_value: pyfory.Float32 = pyfory.field(id=3)
    double_value: pyfory.Float64 = pyfory.field(id=4)
    short_value: pyfory.Int32 = pyfory.field(id=5)
    char_value: pyfory.Int32 = pyfory.field(id=6)
    boolean_value: bool = pyfory.field(id=7)
    int_value_boxed: pyfory.Int32 = pyfory.field(id=8)
    long_value_boxed: pyfory.Int64 = pyfory.field(id=9)
    float_value_boxed: pyfory.Float32 = pyfory.field(id=10)
    double_value_boxed: pyfory.Float64 = pyfory.field(id=11)
    short_value_boxed: pyfory.Int32 = pyfory.field(id=12)
    char_value_boxed: pyfory.Int32 = pyfory.field(id=13)
    boolean_value_boxed: bool = pyfory.field(id=14)
    int_array: pyfory.NDArray[pyfory.Int32] = pyfory.field(id=15)
    long_array: pyfory.NDArray[pyfory.Int64] = pyfory.field(id=16)
    float_array: pyfory.NDArray[pyfory.Float32] = pyfory.field(id=17)
    double_array: pyfory.NDArray[pyfory.Float64] = pyfory.field(id=18)
    short_array: pyfory.NDArray[pyfory.Int32] = pyfory.field(id=19)
    char_array: pyfory.NDArray[pyfory.Int32] = pyfory.field(id=20)
    boolean_array: pyfory.NDArray[bool] = pyfory.field(id=21)
    string: str = pyfory.field(id=22)


@dataclass
class Media:
    uri: str = pyfory.field(id=1)
    title: str = pyfory.field(id=2)
    width: pyfory.Int32 = pyfory.field(id=3)
    height: pyfory.Int32 = pyfory.field(id=4)
    format: str = pyfory.field(id=5)
    duration: pyfory.Int64 = pyfory.field(id=6)
    size: pyfory.Int64 = pyfory.field(id=7)
    bitrate: pyfory.Int32 = pyfory.field(id=8)
    has_bitrate: bool = pyfory.field(id=9)
    persons: List[str] = pyfory.field(id=10)
    player: Player = pyfory.field(id=11)
    copyright: str = pyfory.field(id=12)


@dataclass
class MediaV2:
    uri: str = pyfory.field(id=1)
    title: str = pyfory.field(id=2)
    width: pyfory.Int64 = pyfory.field(id=3)
    height: pyfory.Int32 = pyfory.field(id=4)
    format: str = pyfory.field(id=5)
    duration: pyfory.Int64 = pyfory.field(id=6)
    size: pyfory.Int64 = pyfory.field(id=7)
    bitrate: pyfory.Int32 = pyfory.field(id=8)
    has_bitrate: bool = pyfory.field(id=9)
    persons: List[str] = pyfory.field(id=10)
    player: Player = pyfory.field(id=11)
    copyright: str = pyfory.field(id=12)


@dataclass
class Image:
    uri: str = pyfory.field(id=1)
    title: str = pyfory.field(id=2)
    width: pyfory.Int32 = pyfory.field(id=3)
    height: pyfory.Int32 = pyfory.field(id=4)
    size: Size = pyfory.field(id=5)


@dataclass
class ImageV2:
    uri: str = pyfory.field(id=1)
    title: str = pyfory.field(id=2)
    width: pyfory.Int64 = pyfory.field(id=3)
    height: pyfory.Int32 = pyfory.field(id=4)
    size: Size = pyfory.field(id=5)


@dataclass
class MediaContent:
    media: Media = pyfory.field(id=1)
    images: List[Image] = pyfory.field(id=2)


@dataclass
class MediaContentV2:
    media: MediaV2 = pyfory.field(id=1)
    images: List[ImageV2] = pyfory.field(id=2)


@dataclass
class NumericStructList:
    struct_list: List[NumericStruct] = pyfory.field(id=1)


@dataclass
class NumericStructListV2:
    struct_list: List[NumericStructV2] = pyfory.field(id=1)


@dataclass
class SampleList:
    sample_list: List[Sample] = pyfory.field(id=1)


@dataclass
class SampleListV2:
    sample_list: List[SampleV2] = pyfory.field(id=1)


@dataclass
class MediaContentList:
    media_content_list: List[MediaContent] = pyfory.field(id=1)


@dataclass
class MediaContentListV2:
    media_content_list: List[MediaContentV2] = pyfory.field(id=1)


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
        f9=123456789,
        f10=-42,
        f11=31415926,
        f12=-27182818,
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
        int_array=int32_array([-1234, -123, -12, -1, 0, 1, 12, 123, 1234]),
        long_array=int64_array(
            [-123400, -12300, -1200, -100, 0, 100, 1200, 12300, 123400]
        ),
        float_array=float32_array(
            [-12.34, -12.3, -12.0, -1.0, 0.0, 1.0, 12.0, 12.3, 12.34]
        ),
        double_array=float64_array(
            [-1.234, -1.23, -12.0, -1.0, 0.0, 1.0, 12.0, 1.23, 1.234]
        ),
        short_array=int32_array([-1234, -123, -12, -1, 0, 1, 12, 123, 1234]),
        char_array=int32_array([ord(c) for c in "asdfASDF"]),
        boolean_array=bool_array([True, False, False, True]),
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
        player=Player.FLASH,
        copyright="Copyright (c) 2009, Scooby Dooby Doo",
    )
    images = [
        Image(
            uri="http://javaone.com/keynote_huge.jpg",
            title="Javaone Keynote\u1234",
            width=32000,
            height=24000,
            size=Size.LARGE,
        ),
        Image(
            uri="http://javaone.com/keynote_large.jpg",
            title="",
            width=1024,
            height=768,
            size=Size.LARGE,
        ),
        Image(
            uri="http://javaone.com/keynote_small.jpg",
            title="",
            width=320,
            height=240,
            size=Size.SMALL,
        ),
    ]
    return MediaContent(media=media, images=images)


def create_numeric_struct_list() -> NumericStructList:
    return NumericStructList(
        struct_list=[create_numeric_struct() for _ in range(LIST_SIZE)]
    )


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
        "structlist": create_numeric_struct_list(),
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
    pb = bench_pb2.NumericStruct()
    pb.f1 = obj.f1
    pb.f2 = obj.f2
    pb.f3 = obj.f3
    pb.f4 = obj.f4
    pb.f5 = obj.f5
    pb.f6 = obj.f6
    pb.f7 = obj.f7
    pb.f8 = obj.f8
    pb.f9 = obj.f9
    pb.f10 = obj.f10
    pb.f11 = obj.f11
    pb.f12 = obj.f12
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
        f9=pb_obj.f9,
        f10=pb_obj.f10,
        f11=pb_obj.f11,
        f12=pb_obj.f12,
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
    pb.int_array.extend(obj.int_array.tolist())
    pb.long_array.extend(obj.long_array.tolist())
    pb.float_array.extend(obj.float_array.tolist())
    pb.double_array.extend(obj.double_array.tolist())
    pb.short_array.extend(obj.short_array.tolist())
    pb.char_array.extend(obj.char_array.tolist())
    pb.boolean_array.extend(obj.boolean_array.tolist())
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
        int_array=int32_array(pb_obj.int_array),
        long_array=int64_array(pb_obj.long_array),
        float_array=float32_array(pb_obj.float_array),
        double_array=float64_array(pb_obj.double_array),
        short_array=int32_array(pb_obj.short_array),
        char_array=int32_array(pb_obj.char_array),
        boolean_array=bool_array(pb_obj.boolean_array),
        string=pb_obj.string,
    )


def to_pb_image(bench_pb2, obj: Image):
    pb = bench_pb2.Image()
    pb.uri = obj.uri
    if obj.title:
        pb.title = obj.title
    pb.width = obj.width
    pb.height = obj.height
    pb.size = int(obj.size)
    return pb


def from_pb_image(pb_obj) -> Image:
    title = pb_obj.title if pb_obj.HasField("title") else ""
    return Image(
        uri=pb_obj.uri,
        title=title,
        width=pb_obj.width,
        height=pb_obj.height,
        size=Size(pb_obj.size),
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
    pb.player = int(obj.player)
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
        player=Player(pb_obj.player),
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


def to_pb_numeric_struct_list(bench_pb2, obj: NumericStructList):
    pb = bench_pb2.NumericStructList()
    for item in obj.struct_list:
        pb.struct_list.add().CopyFrom(to_pb_struct(bench_pb2, item))
    return pb


def from_pb_numeric_struct_list(pb_obj) -> NumericStructList:
    return NumericStructList(
        struct_list=[from_pb_struct(item) for item in pb_obj.struct_list]
    )


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
    "struct": (to_pb_struct, from_pb_struct, "NumericStruct"),
    "sample": (to_pb_sample, from_pb_sample, "Sample"),
    "mediacontent": (to_pb_mediacontent, from_pb_mediacontent, "MediaContent"),
    "structlist": (
        to_pb_numeric_struct_list,
        from_pb_numeric_struct_list,
        "NumericStructList",
    ),
    "samplelist": (to_pb_samplelist, from_pb_samplelist, "SampleList"),
    "mediacontentlist": (
        to_pb_mediacontentlist,
        from_pb_mediacontentlist,
        "MediaContentList",
    ),
}


def build_fory() -> pyfory.Fory:
    return build_fory_v1()


def build_fory_v1() -> pyfory.Fory:
    fory = pyfory.Fory(xlang=True, compatible=True, ref=False)
    fory.register_type(Player, type_id=101)
    fory.register_type(Size, type_id=102)
    fory.register_type(NumericStruct, type_id=1)
    fory.register_type(Sample, type_id=2)
    fory.register_type(Media, type_id=3)
    fory.register_type(Image, type_id=4)
    fory.register_type(MediaContent, type_id=5)
    fory.register_type(NumericStructList, type_id=6)
    fory.register_type(SampleList, type_id=7)
    fory.register_type(MediaContentList, type_id=8)
    return fory


def build_fory_v2() -> pyfory.Fory:
    fory = pyfory.Fory(xlang=True, compatible=True, ref=False)
    fory.register_type(Player, type_id=101)
    fory.register_type(Size, type_id=102)
    fory.register_type(NumericStructV2, type_id=1)
    fory.register_type(SampleV2, type_id=2)
    fory.register_type(MediaV2, type_id=3)
    fory.register_type(ImageV2, type_id=4)
    fory.register_type(MediaContentV2, type_id=5)
    fory.register_type(NumericStructListV2, type_id=6)
    fory.register_type(SampleListV2, type_id=7)
    fory.register_type(MediaContentListV2, type_id=8)
    return fory


def schema_mismatch_enabled() -> bool:
    return os.getenv(SCHEMA_MISMATCH_ENV) == "1"


def validate_schema_mismatch_selection(selected_serializers: List[str]) -> None:
    if schema_mismatch_enabled() and selected_serializers != ["fory"]:
        raise ValueError(
            f"{SCHEMA_MISMATCH_ENV}=1 supports only Fory benchmarks; "
            "rerun with --serializer fory"
        )


def verify_schema_mismatch(datatype: str, decoded: Any, expected: Any) -> None:
    if datatype == "struct":
        if not isinstance(decoded, NumericStructV2) or decoded.f1 != expected.f1:
            raise AssertionError("NumericStructV2 schema mismatch read failed")
        return
    if datatype == "sample":
        if not isinstance(decoded, SampleV2) or decoded.int_value != expected.int_value:
            raise AssertionError("SampleV2 schema mismatch read failed")
        return
    if datatype == "mediacontent":
        if (
            not isinstance(decoded, MediaContentV2)
            or not isinstance(decoded.media, MediaV2)
            or decoded.media.width != expected.media.width
            or not decoded.images
            or not isinstance(decoded.images[0], ImageV2)
            or decoded.images[0].width != expected.images[0].width
        ):
            raise AssertionError("MediaContentV2 schema mismatch read failed")
        return
    if datatype == "structlist":
        if (
            not isinstance(decoded, NumericStructListV2)
            or not decoded.struct_list
            or not isinstance(decoded.struct_list[0], NumericStructV2)
            or decoded.struct_list[0].f1 != expected.struct_list[0].f1
        ):
            raise AssertionError("NumericStructListV2 schema mismatch read failed")
        return
    if datatype == "samplelist":
        if (
            not isinstance(decoded, SampleListV2)
            or not decoded.sample_list
            or not isinstance(decoded.sample_list[0], SampleV2)
            or decoded.sample_list[0].int_value != expected.sample_list[0].int_value
        ):
            raise AssertionError("SampleListV2 schema mismatch read failed")
        return
    if datatype == "mediacontentlist":
        if (
            not isinstance(decoded, MediaContentListV2)
            or not decoded.media_content_list
            or not isinstance(decoded.media_content_list[0], MediaContentV2)
            or not isinstance(decoded.media_content_list[0].media, MediaV2)
            or decoded.media_content_list[0].media.width
            != expected.media_content_list[0].media.width
            or not decoded.media_content_list[0].images
            or not isinstance(decoded.media_content_list[0].images[0], ImageV2)
            or decoded.media_content_list[0].images[0].width
            != expected.media_content_list[0].images[0].width
        ):
            raise AssertionError("MediaContentListV2 schema mismatch read failed")
        return
    raise AssertionError(f"Unknown datatype for schema mismatch: {datatype}")


def verify_fory_schema_mismatch(
    benchmark_data: Dict[str, Any],
    selected_datatypes: Iterable[str],
    *,
    writer: pyfory.Fory,
    reader: pyfory.Fory,
) -> None:
    for datatype in selected_datatypes:
        value = benchmark_data[datatype]
        decoded = reader.deserialize(writer.serialize(value))
        verify_schema_mismatch(datatype, decoded, value)


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
    fory_writer: pyfory.Fory,
    fory_reader: pyfory.Fory,
    bench_pb2,
) -> Tuple[Callable[..., Any], Tuple[Any, ...]]:
    if serializer == "fory":
        if operation == "serialize":
            return fory_serialize, (fory_writer, obj)
        return fory_deserialize, (fory_reader, fory_writer.serialize(obj))

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
    selected_serializers: Iterable[str],
    schema_mismatch: bool,
) -> Dict[str, Dict[str, int]]:
    sizes: Dict[str, Dict[str, int]] = {}
    serializer_names = (
        list(selected_serializers) if schema_mismatch else SERIALIZER_ORDER
    )
    for datatype in selected_datatypes:
        obj = benchmark_data[datatype]
        datatype_sizes: Dict[str, int] = {}

        if "fory" in serializer_names:
            datatype_sizes["fory"] = len(fory.serialize(obj))
        if "pickle" in serializer_names:
            datatype_sizes["pickle"] = len(
                pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL)
            )

        if "protobuf" in serializer_names:
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
        help="Comma-separated serializers: fory,protobuf,pickle or all",
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
    validate_schema_mismatch_selection(selected_serializers)
    selected_operations = (
        OPERATION_ORDER if args.operation == "all" else [args.operation]
    )

    benchmark_data = create_benchmark_data()
    mismatch = schema_mismatch_enabled()
    fory_writer = build_fory_v1()
    fory_reader = build_fory_v2() if mismatch else build_fory_v1()
    if mismatch:
        verify_fory_schema_mismatch(
            benchmark_data,
            selected_datatypes,
            writer=fory_writer,
            reader=fory_reader,
        )

    print(
        f"Benchmarking {len(selected_datatypes)} data type(s), {len(selected_serializers)} serializer(s), {len(selected_operations)} operation(s)"
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
                    fory_writer=fory_writer,
                    fory_reader=fory_reader,
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
        fory=fory_writer,
        bench_pb2=bench_pb2,
        selected_serializers=selected_serializers,
        schema_mismatch=mismatch,
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
            "schema_mismatch": mismatch,
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
