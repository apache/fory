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
from dataclasses import dataclass
import datetime
import os
import random
import sys
from typing import Any, Dict, List
import pyfory
import pyperf


# The benchmark case is rewritten from pyperformance bm_pickle
# https://github.com/python/pyperformance/blob/main/pyperformance/data-files/benchmarks/bm_pickle/run_benchmark.py
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
LARGE_TUPLE = tuple(range(2**20 + 1))
LARGE_FLOAT_TUPLE = tuple([random.random() * 10000 for _ in range(2**20 + 1)])
LARGE_BOOLEAN_TUPLE = tuple([bool(random.random() > 0.5) for _ in range(2**20 + 1)])


LIST = [[list(range(10)), list(range(10))] for _ in range(10)]
LARGE_LIST = [i for i in range(2**20 + 1)]


def mutate_dict(orig_dict, random_source):
    new_dict = dict(orig_dict)
    for key, value in new_dict.items():
        rand_val = random_source.random() * sys.maxsize
        if isinstance(key, (int, bytes, str)):
            new_dict[key] = type(key)(rand_val)
    return new_dict


random_source = random.Random(5)
DICT_GROUP = [mutate_dict(DICT, random_source) for _ in range(3)]


@dataclass
class ComplexObject1:
    f1: Any = None
    f2: str = None
    f3: List[str] = None
    f4: Dict[pyfory.Int8Type, pyfory.Int32Type] = None
    f5: pyfory.Int8Type = None
    f6: pyfory.Int16Type = None
    f7: pyfory.Int32Type = None
    f8: pyfory.Int64Type = None
    f9: pyfory.Float32Type = None
    f10: pyfory.Float64Type = None
    f11: pyfory.Int16ArrayType = None
    f12: List[pyfory.Int16Type] = None


@dataclass
class ComplexObject2:
    f1: Any
    f2: Dict[pyfory.Int8Type, pyfory.Int32Type]


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
    f11=[-1, 4],
)


def fory_object(xlang, ref, obj):
    fory = pyfory.Fory(xlang=xlang, ref=ref)
    binary = fory.serialize(obj)
    fory.deserialize(binary)


def fory_data_class(xlang, ref, obj, register_callable):
    fory = pyfory.Fory(xlang=xlang, ref=ref)
    register_callable(fory)
    binary = fory.serialize(obj)
    fory.deserialize(binary)


def benchmark_args():
    parser = argparse.ArgumentParser(description="Fory Benchmark")
    parser.add_argument("--xlang", action="store_true", default=False)
    parser.add_argument("--no-ref", action="store_true", default=False)
    parser.add_argument("--disable-cython", action="store_true", default=False)

    if "--help" in sys.argv:
        parser.print_help()
        return None
    args, unknown_args = parser.parse_known_args()
    sys.argv = sys.argv[:1] + unknown_args
    return args


def micro_benchmark():
    args = benchmark_args()
    runner = pyperf.Runner()
    if args and args.disable_cython:
        os.environ["ENABLE_FORY_CYTHON_SERIALIZATION"] = "0"
        sys.argv += ["--inherit-environ", "ENABLE_FORY_CYTHON_SERIALIZATION"]
    runner.parse_args()
    xlang = args.xlang
    runner.bench_func("fory_dict", fory_object, xlang, not args.no_ref, DICT)
    runner.bench_func(
        "fory_large_dict", fory_object, xlang, not args.no_ref, LARGE_DICT
    )
    runner.bench_func(
        "fory_dict_group", fory_object, xlang, not args.no_ref, DICT_GROUP
    )
    runner.bench_func("fory_tuple", fory_object, xlang, not args.no_ref, TUPLE)
    runner.bench_func(
        "fory_large_tuple", fory_object, xlang, not args.no_ref, LARGE_TUPLE
    )
    runner.bench_func(
        "fory_large_float_tuple",
        fory_object,
        xlang,
        not args.no_ref,
        LARGE_FLOAT_TUPLE,
    )
    runner.bench_func(
        "fory_large_boolean_tuple",
        fory_object,
        xlang,
        not args.no_ref,
        LARGE_BOOLEAN_TUPLE,
    )
    runner.bench_func("fory_list", fory_object, xlang, not args.no_ref, LIST)
    runner.bench_func(
        "fory_large_list", fory_object, xlang, not args.no_ref, LARGE_LIST
    )

    def register_complex(fory):
        if args.xlang:
            fory.register_type(ComplexObject1, typename="example.ComplexObject1")
            fory.register_type(ComplexObject2, typename="example.ComplexObject2")
        else:
            fory.register_type(ComplexObject1)
            fory.register_type(ComplexObject2)

    runner.bench_func(
        "fory_complex",
        fory_data_class,
        xlang,
        not args.no_ref,
        COMPLEX_OBJECT,
        register_complex,
    )


if __name__ == "__main__":
    micro_benchmark()
