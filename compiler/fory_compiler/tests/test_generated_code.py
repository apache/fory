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

import pytest

from fory_compiler.cli import resolve_imports
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
from fory_compiler.generators.dart import DartGenerator
from fory_compiler.ir.ast import Schema
from fory_compiler.ir.validator import SchemaValidator


GENERATOR_CLASSES: Tuple[Type[BaseGenerator], ...] = (
    JavaGenerator,
    PythonGenerator,
    CppGenerator,
    RustGenerator,
    GoGenerator,
    CSharpGenerator,
    JavaScriptGenerator,
    SwiftGenerator,
    DartGenerator,
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


def test_rust_generated_code_uses_fory_temporal_carriers():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message TemporalTypes {
                date day = 1;
                timestamp instant = 2;
                duration elapsed = 3;
            }
            """
        )
    )

    rust_output = render_files(generate_files(schema, RustGenerator))
    assert "pub day: ::fory::Date," in rust_output
    assert "pub instant: ::fory::Timestamp," in rust_output
    assert "pub elapsed: ::fory::Duration," in rust_output
    assert "chrono::" not in rust_output


def test_rust_generated_code_can_use_chrono_temporal_types():
    schema = parse_fdl(
        dedent(
            """
            package gen;
            option rust_use_chrono_temporal_types = true;

            message TemporalTypes {
                date day = 1;
                timestamp instant = 2;
                duration elapsed = 3;
            }
            """
        )
    )

    rust_output = render_files(generate_files(schema, RustGenerator))
    assert "pub day: ::chrono::NaiveDate," in rust_output
    assert "pub instant: ::chrono::NaiveDateTime," in rust_output
    assert "pub elapsed: ::chrono::Duration," in rust_output
    assert "::fory::Date" not in rust_output
    assert "::fory::Timestamp" not in rust_output
    assert "::fory::Duration" not in rust_output


def test_rust_nested_container_ref_uses_correct_pointer_type():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message Node {
                string value = 1;
            }

            message Request {
                list<list<ref Node>> groups = 1;
                map<string, map<string, ref Node>> nodes = 2;
            }
            """
        )
    )

    rust_output = render_files(generate_files(schema, RustGenerator))

    assert (
        "pub groups: ::std::vec::Vec<::std::vec::Vec<::std::sync::Arc<Node>>>,"
        in rust_output
    )
    assert "::std::vec::Vec<::std::vec::Vec<::std::rc::Rc<Node>>>" not in rust_output
    assert (
        "pub nodes: ::std::collections::HashMap<::std::string::String, "
        "::std::collections::HashMap<::std::string::String, ::std::sync::Arc<Node>>>,"
        in rust_output
    )
    assert (
        "::std::collections::HashMap<::std::string::String, "
        "::std::collections::HashMap<::std::string::String, ::std::rc::Rc<Node>>>"
        not in rust_output
    )


def test_generated_code_integer_encoding_variants_equivalent():
    fdl = dedent(
        """
        package gen;

        message EncodingTypes {
            fixed int32 fi32 = 1;
            fixed int64 fi64 = 2;
            fixed uint32 fu32 = 3;
            fixed uint64 fu64 = 4;
            tagged int64 ti64 = 5;
            tagged uint64 tu64 = 6;
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
            int64 ti64 = 5 [(fory).type = "tagged int64"];
            uint64 tu64 = 6 [(fory).type = "tagged uint64"];
        }
        """
    )
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
    }
    assert_all_languages_equal(schemas)

    python_output = render_files(generate_files(schemas["fdl"], PythonGenerator))
    assert "pyfory.TaggedInt64" in python_output
    assert "pyfory.TaggedUInt64" in python_output


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


def test_generated_code_repeated_primitives_are_lists():
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
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
    }
    assert_all_languages_equal(schemas)


def test_generated_code_flatbuffers_primitive_vectors_are_arrays():
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
    schema = parse_fbs(fbs)

    java_output = render_files(generate_files(schema, JavaGenerator))
    assert "private boolean[] flags;" in java_output
    assert "private int[] i32s;" in java_output
    assert "private @UInt32Type int[] u32s;" in java_output

    dart_output = render_files(generate_files(schema, DartGenerator))
    assert "BoolList flags = BoolList(0);" in dart_output
    assert "Int32List i32s = Int32List(0);" in dart_output
    assert "Uint32List u32s = Uint32List(0);" in dart_output


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
    schemas = {
        "fdl": parse_fdl(fdl),
        "proto": parse_proto(proto),
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


def test_rust_generated_ref_pointer_default_and_opt_out():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message Node {
                string value = 1;
            }

            message Holder {
                ref Node default_ref = 1;
                ref(thread_safe=false) Node rc_ref = 2;
                ref(weak=true) Node default_weak_ref = 3;
                ref(weak=true, thread_safe=false) Node rc_weak_ref = 4;
                list<ref Node> default_ref_list = 5;
                list<ref(thread_safe=false) Node> rc_ref_list = 6;
            }
            """
        )
    )
    rust_output = render_files(generate_files(schema, RustGenerator))
    assert "pub default_ref: ::std::sync::Arc<Node>," in rust_output
    assert "pub rc_ref: ::std::rc::Rc<Node>," in rust_output
    assert "pub default_weak_ref: ::fory::ArcWeak<Node>," in rust_output
    assert "pub rc_weak_ref: ::fory::RcWeak<Node>," in rust_output
    assert (
        "pub default_ref_list: ::std::vec::Vec<::std::sync::Arc<Node>>," in rust_output
    )
    assert "pub rc_ref_list: ::std::vec::Vec<::std::rc::Rc<Node>>," in rust_output


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


def test_java_nested_name_registration_uses_owner_namespace():
    schema = parse_fdl(
        """
        option enable_auto_type_id = false;
        package demo;

        message Envelope {
            message Payload {
                int32 value = 1;
            }

            enum Kind {
                UNKNOWN = 0;
                ACTIVE = 1;
            }

            union Choice {
                Payload payload = 1;
                string note = 2;
            }

            Payload payload = 1;
            Kind kind = 2;
            Choice choice = 3;
            list<ref Payload> payloads = 4;
        }
        """
    )
    output = render_files(generate_files(schema, JavaGenerator))

    assert "import org.apache.fory.annotation.Ref;" in output
    assert "private List<@Ref Payload> payloads;" in output
    assert 'resolver.register(Envelope.class, "demo", "Envelope");' in output
    assert (
        'resolver.register(Envelope.Payload.class, "demo.Envelope", "Payload");'
        in output
    )
    assert 'resolver.register(Envelope.Kind.class, "demo.Envelope", "Kind");' in output
    assert (
        'resolver.registerUnion(Envelope.Choice.class, "demo.Envelope", "Choice"'
        in output
    )


def test_generated_registration_uses_single_name_for_dart_python_swift():
    schema = parse_fdl(
        """
        option enable_auto_type_id = false;
        package demo;

        message Envelope {
            message Payload {
                int32 value = 1;
            }

            enum Kind {
                UNKNOWN = 0;
                ACTIVE = 1;
            }

            union Choice {
                Payload payload = 1;
                string note = 2;
            }

            Payload payload = 1;
            Kind kind = 2;
            Choice choice = 3;
        }
        """
    )

    dart_output = render_files(generate_files(schema, DartGenerator))
    assert (
        "registerGeneratedStruct(fory, _envelopeForySchema, id: null, name: 'demo.Envelope');"
        in dart_output
    )
    assert (
        "registerGeneratedEnum(fory, _envelopeKindForySchema, id: null, name: 'demo.Envelope.Kind');"
        in dart_output
    )
    assert (
        "fory.registerSerializer(Envelope_Choice, const _Envelope_ChoiceForySerializer(), id: null, name: 'demo.Envelope.Choice');"
        in dart_output
    )
    assert "namespace:" not in dart_output
    assert "typeName:" not in dart_output

    python_output = render_files(generate_files(schema, PythonGenerator))
    assert 'fory.register_type(Envelope, name="demo.Envelope")' in python_output
    assert (
        'fory.register_type(Envelope.Kind, name="demo.Envelope.Kind")' in python_output
    )
    assert (
        'fory.register_union(Envelope.Choice, name="demo.Envelope.Choice", serializer=Envelope.ChoiceSerializer(fory.type_resolver))'
        in python_output
    )
    assert "namespace=" not in python_output
    assert "typename=" not in python_output

    swift_output = render_files(generate_files(schema, SwiftGenerator))
    assert (
        'try fory.register(Demo.Envelope.self, name: "demo.Envelope")' in swift_output
    )
    assert (
        'try fory.register(Demo.Envelope.Kind.self, name: "demo.Envelope.Kind")'
        in swift_output
    )
    assert (
        'try fory.register(Demo.Envelope.Choice.self, name: "demo.Envelope.Choice")'
        in swift_output
    )
    assert "namespace:" not in swift_output


def test_java_default_package_import_registers_dependency(tmp_path):
    common = tmp_path / "common.fdl"
    common.write_text(
        """
        message Common [id=200] {
            string name = 1;
        }
        """
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        """
        import "common.fdl";

        message Holder [id=201] {
            Common common = 1;
        }
        """
    )

    schema = resolve_imports(main)
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    files = generate_files(schema, JavaGenerator)

    module = files["MainForyModule.java"]
    assert "public final class MainForyModule" in module
    assert "fory.register(CommonForyModule.INSTANCE);" in module
    assert "CommonForyModule.register(fory)" not in module


def test_java_rejects_mixed_default_and_named_imports(tmp_path):
    default_dir = tmp_path / "default_in_named"
    default_dir.mkdir()
    (default_dir / "common.fdl").write_text(
        """
        message Common [id=200] {
            string name = 1;
        }
        """
    )
    default_main = default_dir / "main.fdl"
    default_main.write_text(
        """
        package app;
        import "common.fdl";

        message Holder [id=201] {
            Common common = 1;
        }
        """
    )

    with pytest.raises(ValueError, match="default-package"):
        JavaGenerator(
            resolve_imports(default_main), GeneratorOptions(output_dir=tmp_path)
        )

    named_dir = tmp_path / "named_in_default"
    named_dir.mkdir()
    (named_dir / "common.fdl").write_text(
        """
        package shared;

        message Common [id=200] {
            string name = 1;
        }
        """
    )
    named_main = named_dir / "main.fdl"
    named_main.write_text(
        """
        import "common.fdl";

        message Holder [id=201] {
            optional Common common = 1;
        }
        """
    )

    with pytest.raises(ValueError, match="default-package"):
        JavaGenerator(
            resolve_imports(named_main), GeneratorOptions(output_dir=tmp_path)
        )


def test_java_nested_enum_shadowing_does_not_emit_ref_annotations():
    schema = parse_fdl(
        """
        package demo;

        message Node {
            Envelope owner = 1;
        }

        message Envelope {
            enum Node {
                UNKNOWN = 0;
                ACTIVE = 1;
            }

            Node kind = 1;
            list<Node> kinds = 2;
            map<string, Node> indexed = 3;
        }
        """
    )
    output = render_files(generate_files(schema, JavaGenerator))

    assert "import org.apache.fory.annotation.Ref;" not in output
    assert "private Node kind;" in output
    assert "private List<Node> kinds;" in output
    assert "private Map<String, Node> indexed;" in output
    assert "@Ref" not in output


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
    assert "#[derive(::fory::ForyStruct, Clone, PartialEq, Eq, Default)]" in rust_output

    cpp_output = render_files(generate_files(schemas["fdl"], CppGenerator))
    assert "SharedWeak<TreeNode>" in cpp_output
    go_output = render_files(generate_files(schemas["fdl"], GoGenerator))
    assert (
        'Children []*TreeNode `fory:"id=3,nullable=false,type=list(element=_(nullable=false,ref=true))"`'
        in go_output
    )
    assert 'Parent *TreeNode `fory:"id=4,nullable=false,ref"`' in go_output

    java_output = render_files(generate_files(schemas["fdl"], JavaGenerator))
    assert "import org.apache.fory.annotation.Ref;" in java_output
    assert "private @Ref TreeNode parent;" in java_output


def test_java_float16_equals_hash_contract_generation():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message Float16Contract {
                float16 f16 = 1;
                optional float16 opt_f16 = 2;
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert "Objects.equals(f16, that.f16)" in java_output
    assert "Objects.equals(optF16, that.optF16)" in java_output
    assert "return Objects.hash(f16, optF16);" in java_output


def test_java_repeated_float16_generation_uses_float16_list():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message RepeatedFloat16 {
                list<float16> vals = 1;
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert "import org.apache.fory.collection.Float16List;" in java_output
    assert "private Float16List vals;" in java_output


def test_java_nested_array_values_use_deep_equals_hash_generation():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message NestedArrays {
                list<array<int32>> groups = 1;
                map<string, array<uint8>> bytes_by_name = 2;
            }

            union NestedArrayUnion {
                list<array<int32>> groups = 1;
                map<string, array<uint8>> bytes_by_name = 2;
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert (
        "private static boolean deepValueEquals(Object left, Object right)"
        in java_output
    )
    assert "deepValueEquals(groups, that.groups)" in java_output
    assert "deepValueEquals(bytesByName, that.bytesByName)" in java_output
    assert "deepValueHashCode(groups)" in java_output
    assert "deepValueHashCode(bytesByName)" in java_output
    assert "index == that.index && deepValueEquals(value, that.value)" in java_output
    assert "31 * Integer.hashCode(index) + deepValueHashCode(value)" in java_output


def test_java_unsigned_carriers_and_integer_encoding_annotations():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message UnsignedCarriers {
                uint8 u8 = 1;
                uint16 u16 = 2;
                fixed uint32 fixed_u32 = 3;
                uint32 var_u32 = 4;
                optional uint32 maybe_u32 = 5;
                fixed int32 fixed_i32 = 6;
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert "import org.apache.fory.annotation.Nullable;" in java_output
    assert "import org.apache.fory.config.Int32Encoding;" in java_output
    assert "private @UInt8Type int u8;" in java_output
    assert "private @UInt16Type int u16;" in java_output
    assert (
        "private @UInt32Type(encoding = Int32Encoding.FIXED) long fixedU32;"
        in java_output
    )
    assert "private @UInt32Type long varU32;" in java_output
    assert "private @Nullable @UInt32Type Long maybeU32;" in java_output
    assert (
        "private @Int32Type(encoding = Int32Encoding.FIXED) int fixedI32;"
        in java_output
    )


def test_java_nullable_type_use_annotation_targets_top_level_type():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message Money {
                string currency = 1;
            }

            message NullableTargets {
                optional Money money = 1;
                optional bytes payload = 2;
                optional decimal amount = 3;
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert "private @Nullable Money money;" in java_output
    assert "private byte @Nullable [] payload;" in java_output
    assert "private java.math.@Nullable BigDecimal amount;" in java_output


def test_java_evolving_false_generation_uses_struct_evolution_enum():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message DefaultEvolving {
                string value = 1;
            }

            message Stable [evolving=false] {
                string name = 1;

                message NestedStable [evolving=false] {
                    int32 id = 1;
                }
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert "import org.apache.fory.annotation.ForyStruct;" in java_output
    assert "import org.apache.fory.annotation.ForyStruct.Evolution;" in java_output
    assert java_output.count("@ForyStruct(evolution = Evolution.DISABLED)") == 2
    assert java_output.count("@ForyStruct") == 3
    assert "@ForyStruct(evolving = false)" not in java_output


def test_java_nested_integer_annotations_in_generic_containers():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message NestedIntegerAnnotations {
                map<fixed uint32, list<optional tagged uint64>> values = 1;
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert (
        "private Map<@UInt32Type(encoding = Int32Encoding.FIXED) Long, "
        "List<@UInt64Type(encoding = Int64Encoding.TAGGED) Long>> values;"
        in java_output
    )
    go_output = render_files(generate_files(schema, GoGenerator))
    assert (
        'Values map[uint32][]*uint64 `fory:"id=1,type=map(key=uint32(encoding=fixed),'
        'value=list(nullable=false,ref=false,element=uint64(encoding=tagged)))"`'
        in go_output
    )


def test_python_nested_integer_schema_aliases_in_generic_containers():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message NestedIntegerSchemaAliases {
                map<fixed int32, list<tagged int64>> signed_values = 1;
                map<fixed uint32, list<tagged uint64>> unsigned_values = 2;
            }
            """
        )
    )
    python_output = render_files(generate_files(schema, PythonGenerator))
    assert (
        "signed_values: Dict[pyfory.FixedInt32, List[pyfory.TaggedInt64]]"
        in python_output
    )
    assert (
        "unsigned_values: Dict[pyfory.FixedUInt32, List[pyfory.TaggedUInt64]]"
        in python_output
    )


def test_cpp_nested_integer_specs_in_generic_containers():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message NestedIntegerSpecs {
                map<fixed uint32, list<optional tagged uint64>> values = 1;
            }
            """
        )
    )
    cpp_output = render_files(generate_files(schema, CppGenerator))
    assert (
        "FORY_STRUCT(NestedIntegerSpecs, "
        "(values_, fory::F(1).map(fory::T::uint32().fixed(), "
        "fory::T::list(fory::T::inner(fory::T::uint64().tagged())))));" in cpp_output
    )


def test_cpp_generator_supports_decimal_fields_and_unions():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message Money {
                decimal amount = 1;
            }

            union Value {
                decimal amount = 1;
                Money money = 2;
            }
            """
        )
    )

    cpp_output = render_files(generate_files(schema, CppGenerator))
    assert '#include "fory/serialization/decimal_serializers.h"' in cpp_output
    assert "const fory::serialization::Decimal& amount() const" in cpp_output
    assert "std::variant<fory::serialization::Decimal, Money> value_" in cpp_output
    assert "(amount, fory::serialization::Decimal, fory::F(1))" in cpp_output


def test_java_enum_generation_uses_fory_enum_ids():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            enum Status {
                UNKNOWN = 4096;
                OK = 8192;
            }
            """
        )
    )
    java_output = render_files(generate_files(schema, JavaGenerator))
    assert "import org.apache.fory.annotation.ForyEnumId;" in java_output
    assert "public enum Status {" in java_output
    assert "UNKNOWN(4096)," in java_output
    assert "OK(8192);" in java_output
    assert "private final int id;" in java_output
    assert "Status(int id) {" in java_output
    assert "this.id = id;" in java_output
    assert "@ForyEnumId" in java_output
    assert "public int getId() {" in java_output
    assert "return id;" in java_output


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


def test_go_generator_distinguishes_bytes_from_uint8_lists():
    schema = parse_fdl(
        dedent(
            """
            package gen;

            message ByteSemantics {
                bytes payload = 1;
                list<uint8> values = 2;
            }
            """
        )
    )
    go_output = render_files(generate_files(schema, GoGenerator))
    assert 'Payload []byte `fory:"id=1,type=bytes"`' in go_output
    assert 'Values []uint8 `fory:"id=2"`' in go_output


def test_rust_generated_code_uses_absolute_paths():
    schema = parse_fdl(
        dedent(
            """
            package foo;

            message String {
                string value = 1;
                list<string> items = 2;
                map<string, string> labels = 3;
                any payload = 4;
                ref(weak=true) String parent = 5;
            }

            union Fory {
                String text = 1;
            }
            """
        )
    )
    rust_output = render_files(generate_files(schema, RustGenerator))
    assert "use fory::" not in rust_output
    assert "use std::" not in rust_output
    assert "#[derive(::fory::ForyStruct" in rust_output
    assert "#[derive(::fory::ForyUnion" in rust_output
    assert "#[fory(unknown)]" in rust_output
    assert "Unknown(::fory::UnknownCase)," in rust_output
    assert "#[fory(id = 1, default)]" in rust_output
    assert "impl ::std::default::Default for Fory" in rust_output
    assert "pub value: ::std::string::String," in rust_output
    assert "pub items: ::std::vec::Vec<::std::string::String>," in rust_output
    assert (
        "pub labels: ::std::collections::HashMap<::std::string::String, ::std::string::String>,"
        in rust_output
    )
    assert (
        "pub payload: ::std::sync::Arc<dyn ::std::any::Any + Send + Sync>,"
        in rust_output
    )
    assert "pub parent: ::fory::ArcWeak<String>," in rust_output
    assert "pub fn register_types(fory: &mut ::fory::Fory)" in rust_output
    assert "static FORY: ::std::sync::OnceLock<::fory::Fory>" in rust_output


def test_rust_union_conflicting_payload_uses_self_path():
    schema = parse_fdl(
        dedent(
            """
            package foo;

            message Dog {
                string name = 1;
            }

            union Animal {
                Dog dog = 1;
            }
            """
        )
    )

    rust_output = render_files(generate_files(schema, RustGenerator))
    assert "Dog(self::Dog)," in rust_output
    assert (
        "Self::Dog(<self::Dog as ::fory::ForyDefault>::fory_default())" in rust_output
    )
    assert "Dog(Dog)," not in rust_output
