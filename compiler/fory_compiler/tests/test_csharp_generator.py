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

"""Tests for C# generator behavior."""

import warnings
from pathlib import Path

from fory_compiler.cli import main as foryc_main, resolve_imports
from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.csharp import CSharpGenerator
from fory_compiler.ir.validator import SchemaValidator


def parse_schema(source: str):
    return Parser(Lexer(source).tokenize()).parse()


def generate(source: str):
    schema = parse_schema(source)
    generator = CSharpGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    return generator.generate()[0]


def test_csharp_namespace_option_used():
    file = generate(
        """
        package payment;
        option csharp_namespace = "MyCorp.Payment.V1";

        message Payment {
            string id = 1;
        }
        """
    )

    assert file.path == "MyCorp/Payment/V1/Payment.cs"
    assert "namespace MyCorp.Payment.V1;" in file.content
    assert "public sealed partial class Payment" in file.content


def test_csharp_namespace_uses_package():
    file = generate(
        """
        package com.example.models;

        message User {
            string name = 1;
        }
        """
    )

    assert file.path == "com/example/models/ComExampleModels.cs"
    assert "namespace com.example.models;" in file.content


def test_csharp_semantic_model_attributes():
    file = generate(
        """
        package example;

        enum Status {
            READY = 1;
        }

        message Item {
            string name = 1;
        }

        union Choice {
            string text = 0;
            fixed int32 code = 1;
            Item item = 2;
        }

        message Envelope {
            Status status = 1;
            Choice choice = 2;
        }
        """
    )

    assert "[ForyEnum]" in file.content
    assert "[ForyUnion]" in file.content
    assert "public abstract partial record Choice" in file.content
    assert "[ForyUnknownCase]" in file.content
    assert (
        "public sealed partial record Unknown(UnknownCase Value) : Choice;"
        in file.content
    )
    assert "[ForyCase(0)]" in file.content
    assert "public sealed partial record Text(string Value) : Choice;" in file.content
    assert "[ForyCase(1, Type = typeof(S.Fixed<S.Int32>))]" in file.content
    assert "public sealed partial record Code(int Value) : Choice;" in file.content
    assert "[ForyCase(2)]" in file.content
    assert (
        "public sealed partial record Item(global::example.Item Value) : Choice;"
        in file.content
    )
    assert ": Union" not in file.content
    assert "public enum ChoiceCase" not in file.content
    assert "public static Choice Text" not in file.content
    assert "public bool IsText" not in file.content
    assert "TextValue()" not in file.content
    assert "Case()" not in file.content
    assert "GetCaseId()" not in file.content
    assert "[ForyStruct]" in file.content
    assert "[ForyObject]" not in file.content


def test_csharp_registers_fdl_name():
    file = generate(
        """
        package myapp.models;
        option csharp_namespace = "MyCorp.Generated.Models";
        option enable_auto_type_id = false;

        message User {
            string name = 1;
        }
        """
    )

    assert (
        'fory.Register<global::MyCorp.Generated.Models.User>("myapp.models", "User");'
        in file.content
    )


def test_csharp_field_encoding_attributes():
    file = generate(
        """
        package example;

        message Encoded {
            fixed int32 fixed_id = 1;
            tagged uint64 tagged = 2;
            int32 plain = 3;
        }
        """
    )

    assert "[ForyField(1, Type = typeof(S.Fixed<S.Int32>))]" in file.content
    assert "[ForyField(2, Type = typeof(S.Tagged<S.UInt64>))]" in file.content
    assert "[ForyField(3)]" in file.content
    assert "public int Plain { get; set; }" in file.content


def test_csharp_nested_type_attrs():
    file = generate(
        """
        package example;

        message Nested {
            map<fixed uint32, list<optional tagged uint64>> values = 1;
            map<string, optional float16> optional_halves = 2;
        }
        """
    )

    assert (
        "[ForyField(1, Type = typeof(S.Map<S.Fixed<S.UInt32>, S.List<S.Tagged<S.UInt64>>>))]"
        in file.content
    )
    assert (
        "public Dictionary<uint, List<ulong?>> Values { get; set; } = new();"
        in file.content
    )
    assert (
        "public Dictionary<string, Half?> OptionalHalves { get; set; } = new();"
        in file.content
    )


def test_csharp_reduced_precision_carriers():
    file = generate(
        """
        package example;

        message Reduced {
            float16 f16 = 1;
            bfloat16 bf16 = 2;
            list<float16> f16_values = 3;
            list<bfloat16> bf16_values = 4;
        }
        """
    )

    assert "public Half F16 { get; set; }" in file.content
    assert "public BFloat16 Bf16 { get; set; }" in file.content
    assert "public List<Half> F16Values { get; set; } = new();" in file.content
    assert "public List<BFloat16> Bf16Values { get; set; } = new();" in file.content


def test_csharp_imported_modules():
    repo_root = Path(__file__).resolve().parents[3]
    idl_dir = repo_root / "integration_tests" / "idl_tests" / "idl"
    schema = resolve_imports(idl_dir / "root.idl", [idl_dir])

    generator = CSharpGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    file = generator.generate()[0]

    assert "global::addressbook.AddressbookForyModule.Install(fory);" in file.content
    assert "global::tree.TreeForyModule.Install(fory);" in file.content


def test_csharp_model_file_uses_owner_name(tmp_path: Path):
    schema_file = tmp_path / "order-events.fdl"
    schema_file.write_text(
        """
        package app.events;
        option csharp_namespace = "App.Events";

        message Event {
            string name = 1;
        }
        """
    )

    schema = resolve_imports(schema_file)
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    file = CSharpGenerator(schema, GeneratorOptions(output_dir=tmp_path)).generate()[0]

    assert file.path == "App/Events/OrderEvents.cs"
    assert "public static class OrderEventsForyModule" in file.content
    assert "public static class EventsForyModule" not in file.content


def test_csharp_owner_name_prefixes_digits(tmp_path: Path):
    schema_file = tmp_path / "123-schema.fdl"
    schema_file.write_text(
        """
        package app.events;
        option csharp_namespace = "App.Events";

        message Event {
            string name = 1;
        }
        """
    )

    schema = resolve_imports(schema_file)
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    file = CSharpGenerator(schema, GeneratorOptions(output_dir=tmp_path)).generate()[0]

    assert file.path == "App/Events/Schema123Schema.cs"
    assert "public static class Schema123SchemaForyModule" in file.content


def test_csharp_import_modules_distinct(tmp_path: Path):
    first = tmp_path / "first.fdl"
    first.write_text(
        """
        package shared;
        option csharp_namespace = "Demo.Shared";

        message First {
            string name = 1;
        }
        """
    )
    second = tmp_path / "second.fdl"
    second.write_text(
        """
        package shared;
        option csharp_namespace = "Demo.Shared";

        message Second {
            string name = 1;
        }
        """
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        """
        package app;
        option csharp_namespace = "Demo.App";

        import "first.fdl";
        import "second.fdl";

        message Holder {
            First first = 1;
            Second second = 2;
        }
        """
    )

    schema = resolve_imports(main, [tmp_path])
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    file = CSharpGenerator(schema, GeneratorOptions(output_dir=tmp_path)).generate()[0]

    assert "global::Demo.Shared.FirstForyModule.Install(fory);" in file.content
    assert "global::Demo.Shared.SecondForyModule.Install(fory);" in file.content


def test_csharp_grpc_path_collision(tmp_path: Path, capsys):
    schema_file = tmp_path / "GreeterGrpc.fdl"
    schema_file.write_text(
        """
        package demo.collision;

        message Req {}
        message Res {}

        service Greeter {
            rpc Call (Req) returns (Res);
        }
        """
    )
    out = tmp_path / "out"

    result = foryc_main(
        [
            "--lang",
            "csharp",
            "--csharp_out",
            str(out),
            "--grpc",
            str(schema_file),
        ]
    )

    captured = capsys.readouterr()
    assert result == 1
    assert "C# generated file path collision" in captured.err
    assert not out.exists()


def test_csharp_module_collision(tmp_path: Path, capsys):
    first = tmp_path / "foo-bar.fdl"
    first.write_text(
        """
        package demo.same;

        message First {
            string name = 1;
        }
        """
    )
    second = tmp_path / "foo_bar.fdl"
    second.write_text(
        """
        package demo.same;

        message Second {
            string name = 1;
        }
        """
    )
    out = tmp_path / "out"

    result = foryc_main(
        [
            "--lang",
            "csharp",
            "--csharp_out",
            str(out),
            str(first),
            str(second),
        ]
    )

    captured = capsys.readouterr()
    assert result == 1
    assert "C# generated file path collision" in captured.err
    assert not out.exists()


def test_csharp_output_collision(tmp_path: Path, capsys):
    first_dir = tmp_path / "first"
    second_dir = tmp_path / "second"
    first_dir.mkdir()
    second_dir.mkdir()
    first = first_dir / "common.fdl"
    first.write_text(
        """
        package demo.same;

        message First {
            string name = 1;
        }
        """
    )
    second = second_dir / "common.fdl"
    second.write_text(
        """
        package demo.same;

        message Second {
            string name = 1;
        }
        """
    )
    out = tmp_path / "out"

    result = foryc_main(
        [
            "--lang",
            "csharp",
            "--csharp_out",
            str(out),
            str(first),
            str(second),
        ]
    )

    captured = capsys.readouterr()
    assert result == 1
    assert "C# generated file path collision" in captured.err
    assert not out.exists()


def test_csharp_service_type_collision(tmp_path: Path, capsys):
    model = tmp_path / "model.fdl"
    model.write_text(
        """
        package demo.same;

        message Greeter {
            string name = 1;
        }
        """
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        """
        package demo.same;

        message Req {}
        message Res {}

        service Greeter {
            rpc Call (Req) returns (Res);
        }
        """
    )
    out = tmp_path / "out"

    result = foryc_main(
        [
            "--lang",
            "csharp",
            "--csharp_out",
            str(out),
            "--grpc",
            str(model),
            str(service),
        ]
    )

    captured = capsys.readouterr()
    assert result == 1
    assert "C# top-level symbol collision" in captured.err
    assert not out.exists()


def test_csharp_service_module_collision(tmp_path: Path, capsys):
    common = tmp_path / "common.fdl"
    common.write_text(
        """
        package demo.same;

        message Holder {
            string name = 1;
        }
        """
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        """
        package demo.same;

        message Req {}
        message Res {}

        service CommonForyModule {
            rpc Call (Req) returns (Res);
        }
        """
    )
    out = tmp_path / "out"

    result = foryc_main(
        [
            "--lang",
            "csharp",
            "--csharp_out",
            str(out),
            "--grpc",
            str(common),
            str(service),
        ]
    )

    captured = capsys.readouterr()
    assert result == 1
    assert "C# top-level symbol collision" in captured.err
    assert not out.exists()


def test_csharp_namespace_option_is_known():
    source = """
    package myapp;
    option csharp_namespace = "MyCorp.MyApp";

    message User {
      string name = 1;
    }
    """

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        schema = parse_schema(source)

    assert schema.get_option("csharp_namespace") == "MyCorp.MyApp"
    assert not caught
