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

"""Tests for generated code consistency across frontends."""

from pathlib import Path
from textwrap import dedent
from typing import Dict, Tuple, Type

from fory_compiler.frontend.fbs import FBSFrontend
from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.frontend.proto import ProtoFrontend
from fory_compiler.generators.base import BaseGenerator, GeneratorOptions
from fory_compiler.generators.cpp import CppGenerator
from fory_compiler.generators.go import GoGenerator
from fory_compiler.generators.java import JavaGenerator
from fory_compiler.generators.python import PythonGenerator
from fory_compiler.generators.rust import RustGenerator
from fory_compiler.generators.csharp import CSharpGenerator
from fory_compiler.generators.javascript import JavaScriptGenerator
from fory_compiler.generators.swift import SwiftGenerator
from fory_compiler.ir.ast import Schema


GENERATOR_CLASSES: Tuple[Type[BaseGenerator], ...] = (
    JavaGenerator,
    PythonGenerator,
    CppGenerator,
    RustGenerator,
    GoGenerator,
    CSharpGenerator,
    JavaScriptGenerator,
    SwiftGenerator,
)


def parse_fdl(source: str) -> Schema:
    return Parser(Lexer(source).tokenize()).parse()


def parse_proto(source: str) -> Schema:
    return ProtoFrontend().parse(source)


def parse_fbs(source: str) -> Schema:
    return FBSFrontend().parse(source)


def generate_files(
    schema: Schema, generator_cls: Type[BaseGenerator]
) -> Dict[str, str]:
    options = GeneratorOptions(output_dir=Path("/tmp"))
    generator = generator_cls(schema, options)
    return {item.path: item.content for item in generator.generate()}


def render_files(files: Dict[str, str]) -> str:
    return "\n".join(content for _, content in sorted(files.items()))


def assert_language_outputs_equal(
    schemas: Dict[str, Schema], generator_cls: Type[BaseGenerator]
) -> None:
    baseline_label = None
    baseline_files: Dict[str, str] = {}
    for label, schema in schemas.items():
        files = generate_files(schema, generator_cls)
        if baseline_label is None:
            baseline_label = label
            baseline_files = files
            continue
        assert files == baseline_files, (
            f"{generator_cls.language_name} output mismatch for {label} vs {baseline_label}"
        )


def assert_all_languages_equal(schemas: Dict[str, Schema]) -> None:
    for generator_cls in GENERATOR_CLASSES:
        assert_language_outputs_equal(schemas, generator_cls)


def test_generated_code_scalar_types_equivalent():
    fdl = dedent(
        """
        package gen;

        message ScalarTypes {
            bool active = 1;
            int32 i32 = 2;
            int64 i64 = 3;
            uint32 u32 = 4;
            uint64 u64 = 5;
            float32 f32 = 6;
            float64 f64 = 7;
            string name = 8;
        }
        """
    )
    proto = dedent(
        """
        syntax = "proto3";
        package gen;

        message ScalarTypes {
            bool active = 1;
            sint32 i32 = 2;
            sint64 i64 = 3;
            uint32 u32 = 4;
            uint64 u64 = 5;
            float f32 = 6;
            double f64 = 7;
            string name = 8;
        }
        """
    )
    fbs = dedent(
        """
        namespace gen;

        table ScalarTypes {
            active:bool;
            i32:int;
            i64:long;
            u32:uint;
            u64:ulong;
            f32:float;
            f64:double;
            name:string;
        }
        """
    )
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
        "fbs": parse_fbs(fbs),
    }
    assert_all_languages_equal(schemas)


def test_generated_code_integer_encoding_variants_equivalent():
    fdl = dedent(
        """
        package gen;

        message EncodingTypes {
            fixed_int32 fi32 = 1;
            fixed_int64 fi64 = 2;
            fixed_uint32 fu32 = 3;
            fixed_uint64 fu64 = 4;
            tagged_int64 ti64 = 5;
            tagged_uint64 tu64 = 6;
        }
        """
    )
    proto = dedent(
        """
        syntax = "proto3";
        package gen;

        message EncodingTypes {
            sfixed32 fi32 = 1;
            sfixed64 fi64 = 2;
            fixed32 fu32 = 3;
            fixed64 fu64 = 4;
            int64 ti64 = 5 [(fory).type = "tagged_int64"];
            uint64 tu64 = 6 [(fory).type = "tagged_uint64"];
        }
        """
    )
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
    }
    assert_all_languages_equal(schemas)

    python_output = render_files(generate_files(schemas["fdl"], PythonGenerator))
    assert "pyfory.tagged_int64" in python_output
    assert "pyfory.tagged_uint64" in python_output


def test_generated_code_list_modifier_aliases_equivalent():
    repeated = dedent(
        """
        package gen;

        message Item {
            string name = 1;
        }

        message Container {
            repeated string tags = 1;
            optional repeated string labels = 2;
            repeated optional string aliases = 3;
            ref repeated Item items = 4;
            repeated ref Item children = 5;
        }
        """
    )
    list_syntax = dedent(
        """
        package gen;

        message Item {
            string name = 1;
        }

        message Container {
            list<string> tags = 1;
            optional list<string> labels = 2;
            list<optional string> aliases = 3;
            ref list<Item> items = 4;
            list<ref Item> children = 5;
        }
        """
    )
    schemas = {
        "repeated": parse_fdl(repeated),
        "list": parse_fdl(list_syntax),
    }
    assert_all_languages_equal(schemas)


def test_generated_code_primitive_arrays_equivalent():
    fdl = dedent(
        """
        package gen;

        message ArrayTypes {
            repeated bool flags = 1;
            repeated int8 i8s = 2;
            repeated int16 i16s = 3;
            repeated int32 i32s = 4;
            repeated int64 i64s = 5;
            repeated uint8 u8s = 6;
            repeated uint16 u16s = 7;
            repeated uint32 u32s = 8;
            repeated uint64 u64s = 9;
            repeated float32 f32s = 10;
            repeated float64 f64s = 11;
        }
        """
    )
    proto = dedent(
        """
        syntax = "proto3";
        package gen;

        message ArrayTypes {
            repeated bool flags = 1;
            repeated int8 i8s = 2;
            repeated int16 i16s = 3;
            repeated sint32 i32s = 4;
            repeated sint64 i64s = 5;
            repeated uint8 u8s = 6;
            repeated uint16 u16s = 7;
            repeated uint32 u32s = 8;
            repeated uint64 u64s = 9;
            repeated float f32s = 10;
            repeated double f64s = 11;
        }
        """
    )
    fbs = dedent(
        """
        namespace gen;

        table ArrayTypes {
            flags:[bool];
            i8s:[byte];
            i16s:[short];
            i32s:[int];
            i64s:[long];
            u8s:[ubyte];
            u16s:[ushort];
            u32s:[uint];
            u64s:[ulong];
            f32s:[float];
            f64s:[double];
        }
        """
    )
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
        "fbs": parse_fbs(fbs),
    }
    assert_all_languages_equal(schemas)


def test_generated_code_list_types_equivalent():
    fdl = dedent(
        """
        package gen;

        message ListItem {
            string value = 1;
        }

        message ListTypes {
            repeated string names = 1;
            repeated bool flags = 2;
            repeated ListItem items = 3;
        }
        """
    )
    proto = dedent(
        """
        syntax = "proto3";
        package gen;

        message ListItem {
            string value = 1;
        }

        message ListTypes {
            repeated string names = 1;
            repeated bool flags = 2;
            repeated ListItem items = 3;
        }
        """
    )
    fbs = dedent(
        """
        namespace gen;

        table ListItem {
            value:string;
        }

        table ListTypes {
            names:[string];
            flags:[bool];
            items:[ListItem];
        }
        """
    )
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
        "fbs": parse_fbs(fbs),
    }
    assert_all_languages_equal(schemas)


def test_generated_code_map_types_equivalent():
    fdl = dedent(
        """
        package gen;

        message MapValue {
            string id = 1;
        }

        message MapTypes {
            map<string, int32> counts = 1;
            optional map<string, MapValue> entries = 2;
            map<string, ref(weak=true, thread_safe=false) MapValue> weak_entries = 3;
            optional int32 version = 4;
        }
        """
    )
    proto = dedent(
        """
        syntax = "proto3";
        package gen;

        message MapValue {
            string id = 1;
        }

        message MapTypes {
            map<string, sint32> counts = 1;
            map<string, MapValue> entries = 2 [(fory).nullable = true];
            map<string, MapValue> weak_entries = 3 [
                (fory).ref = true,
                (fory).weak_ref = true,
                (fory).thread_safe_pointer = false
            ];
            optional sint32 version = 4;
        }
        """
    )
    # FlatBuffers does not support maps, compare FDL vs proto only.
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
    }
    assert_all_languages_equal(schemas)

    rust_output = render_files(generate_files(schemas["fdl"], RustGenerator))
    assert "RcWeak<MapValue>" in rust_output
    assert "Option<i32>" in rust_output

    cpp_output = render_files(generate_files(schemas["fdl"], CppGenerator))
    assert "SharedWeak<MapValue>" in cpp_output


def test_generated_code_nested_messages_equivalent():
    fdl = dedent(
        """
        package gen;

        message Outer {
            string id = 1;

            message Inner {
                string value = 1;
            }

            Inner inner = 2;
        }
        """
    )
    proto = dedent(
        """
        syntax = "proto3";
        package gen;

        message Outer {
            string id = 1;

            message Inner {
                string value = 1;
            }

            Inner inner = 2;
        }
        """
    )
    # FlatBuffers does not support nested message declarations.
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
    }
    assert_all_languages_equal(schemas)


def test_generated_code_tree_ref_options_equivalent():
    fdl = dedent(
        """
        package tree;

        message TreeNode {
            string id = 1;
            string name = 2;

            repeated ref TreeNode children = 3;
            ref(weak=true) TreeNode parent = 4;
        }
        """
    )
    proto = dedent(
        """
        syntax = "proto3";
        package tree;

        message TreeNode {
            string id = 1;
            string name = 2;

            repeated TreeNode children = 3 [(fory).ref = true];
            TreeNode parent = 4 [(fory).weak_ref = true];
        }
        """
    )
    fbs = dedent(
        """
        namespace tree;

        table TreeNode {
            id: string;
            name: string;
            children: [TreeNode] (fory_ref: true);
            parent: TreeNode (fory_weak_ref: true);
        }
        """
    )
    # Tree ref options should produce identical outputs across frontends.
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
        "fbs": parse_fbs(fbs),
    }
    assert_all_languages_equal(schemas)

    rust_output = render_files(generate_files(schemas["fdl"], RustGenerator))
    assert "ArcWeak<TreeNode>" in rust_output

    cpp_output = render_files(generate_files(schemas["fdl"], CppGenerator))
    assert "SharedWeak<TreeNode>" in cpp_output


def test_go_bfloat16_generation():
    idl = dedent(
        """
        package bfloat16_test;

        message BFloat16Message {
            bfloat16 val = 1;
            optional bfloat16 opt_val = 2;
            list<bfloat16> list_val = 3;
        }
        """
    )
    schema = parse_fdl(idl)
    files = generate_files(schema, GoGenerator)

    assert len(files) == 1
    content = list(files.values())[0]

    # Check imports
    assert 'bfloat16 "github.com/apache/fory/go/fory/bfloat16"' in content

    # Check fields
    assert '\tVal bfloat16.BFloat16 `fory:"id=1"`' in content
    assert (
        '\tOptVal optional.Optional[bfloat16.BFloat16] `fory:"id=2,nullable"`'
        in content
    )
    assert "\tListVal []bfloat16.BFloat16" in content
