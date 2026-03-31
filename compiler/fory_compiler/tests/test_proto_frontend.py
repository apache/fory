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

"""Tests for the proto frontend translation."""

import pytest
import tempfile
from pathlib import Path

from fory_compiler.frontend.base import FrontendError
from fory_compiler.frontend.proto import ProtoFrontend
from fory_compiler.ir.ast import PrimitiveType
from fory_compiler.ir.types import PrimitiveKind
from fory_compiler.cli import resolve_imports
from fory_compiler.ir.validator import SchemaValidator


def test_proto_type_mapping():
    source = """
    syntax = "proto3";
    package demo;

    message Person {
        int32 age = 1;
        sint32 score = 2;
        fixed32 id = 3;
        sfixed64 balance = 4;
    }
    """
    schema = ProtoFrontend().parse(source)
    fields = {f.name: f.field_type for f in schema.messages[0].fields}

    assert isinstance(fields["age"], PrimitiveType)
    assert fields["age"].kind == PrimitiveKind.VAR_UINT32
    assert fields["score"].kind == PrimitiveKind.VARINT32
    assert fields["id"].kind == PrimitiveKind.UINT32
    assert fields["balance"].kind == PrimitiveKind.INT64


def test_proto_oneof_translation():
    source = """
    syntax = "proto3";

    message Event {
        oneof payload {
            string text = 1;
            int32 number = 2;
        }
    }
    """
    schema = ProtoFrontend().parse(source)
    event = schema.messages[0]

    assert len(event.nested_unions) == 1
    union = event.nested_unions[0]
    assert union.name == "Payload"
    case_names = [f.name for f in union.fields]
    case_numbers = [f.number for f in union.fields]
    assert case_names == ["text", "number"]
    assert case_numbers == [1, 2]

    payload_field = [f for f in event.fields if f.name == "payload"][0]
    assert payload_field.optional is True
    assert payload_field.field_type.name == "Payload"


def test_proto_oneof_type_name_uses_pascal_case():
    source = """
    syntax = "proto3";

    message Event {
        oneof payload_data {
            string text = 1;
        }
    }
    """
    schema = ProtoFrontend().parse(source)
    event = schema.messages[0]

    assert len(event.nested_unions) == 1
    union = event.nested_unions[0]
    assert union.name == "PayloadData"

    payload_field = [f for f in event.fields if f.name == "payload_data"][0]
    assert payload_field.optional is True
    assert payload_field.field_type.name == "PayloadData"


def test_proto_file_option_enable_auto_type_id():
    source = """
    syntax = "proto3";
    package demo;

    option (fory).enable_auto_type_id = false;

    message User {
        string name = 1;
    }
    """
    schema = ProtoFrontend().parse(source)
    assert schema.get_option("enable_auto_type_id") is False


def test_proto_nested_qualified_types_pass():
    source = """
    syntax = "proto3";
    package com.example;

    message A {
        message B {
            message C {}
        }
    }
    message Outer {
        A.B.C              c1 = 1;
        com.example.A.B.C  c2 = 2;
        .com.example.A.B.C c3 = 3;
    }
    """
    schema = ProtoFrontend().parse(source)
    validator = SchemaValidator(schema)
    assert validator.validate()


def test_proto_nested_qualified_types_fail():
    # X is only accessible as A.X; pure X is not in scope at B's level.
    source = """
    syntax = "proto3";
    package demo;

    message A {
        message X{}
    }
    message B {
        X x1 = 1;
        X x2 = 2;
    }
    """
    with pytest.raises(FrontendError):
        ProtoFrontend().parse(source)


def test_proto_same_package_qualified_types_pass():
    source = """
    syntax = "proto3";
    package com.example;

    message Foo {}

    message Bar {
        Foo              f1 = 1;
        com.example.Foo  f2 = 2;
        .com.example.Foo f3 = 3;
    }
    """
    schema = ProtoFrontend().parse(source)
    validator = SchemaValidator(schema)
    assert validator.validate()


def test_proto_imported_package_qualified_types_fail():
    # Pure 'Address' is not visible from package 'main'; must use 'common.Address'.
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        common_proto = tmpdir / "common.proto"
        common_proto.write_text(
            """
            syntax = "proto3";
            package common;

            message Address {}
            """
        )
        main_proto = tmpdir / "main.proto"
        main_proto.write_text(
            """
            syntax = "proto3";
            package main;
            import "common.proto";

            message User {
                Address addr1 = 1;
                Address addr2 = 2;
            }
            """
        )
        with pytest.raises(FrontendError):
            resolve_imports(main_proto, [tmpdir])


def test_proto_imported_package_qualified_types_pass():
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        common1_proto = tmpdir / "common1.proto"
        common1_proto.write_text(
            """
            syntax = "proto3";
            package com.lib1;

            message Address {
                message Country {}
            }
            """
        )
        common2_proto = tmpdir / "common2.proto"
        common2_proto.write_text(
            """
            syntax = "proto3";
            package com.lib2;

            message Address {}
            """
        )
        main_proto = tmpdir / "main.proto"
        main_proto.write_text(
            """
            syntax = "proto3";
            package main;
            import "common1.proto";
            import "common2.proto";

            message User {
                com.lib1.Address         a1 = 1;
                .com.lib2.Address        a2 = 2;
                com.lib1.Address.Country a3 = 3;
            }
            """
        )
        schema = resolve_imports(main_proto, [tmpdir])
        validator = SchemaValidator(schema)
        assert validator.validate()


def test_proto_transitive_imports_fail():
    # baz.proto is only transitively imported via bar.proto; main.proto must not reference baz.Foo directly.
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        (tmpdir / "baz.proto").write_text(
            """
            syntax = "proto3";
            package baz;

            message Foo {}
            """
        )
        (tmpdir / "bar.proto").write_text(
            """
            syntax = "proto3";
            package bar;
            import "baz.proto";

            message Bar { baz.Foo foo = 1; }
            """
        )
        main_proto = tmpdir / "main.proto"
        main_proto.write_text(
            """
            syntax = "proto3";
            package main;
            import "bar.proto";

            message User { baz.Foo foo = 1; }
            """
        )
        with pytest.raises(FrontendError):
            resolve_imports(main_proto, [tmpdir])


def test_proto_same_package_transitive_import_fail():
    # c1.proto and c2.proto share package common; main.proto only imports
    # c2.proto. Referencing common.Foo (defined in c1.proto) must fail because
    # c1.proto is not directly imported, even though its package name matches
    # the directly-imported c2.proto.
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        (tmpdir / "c1.proto").write_text(
            """
            syntax = "proto3";
            package common;

            message Foo {}
            """
        )
        (tmpdir / "c2.proto").write_text(
            """
            syntax = "proto3";
            package common;
            import "c1.proto";

            message Bar {}
            """
        )
        main_proto = tmpdir / "main.proto"
        main_proto.write_text(
            """
            syntax = "proto3";
            package main;
            import "c2.proto";

            message User { common.Foo foo = 1; }
            """
        )
        with pytest.raises(FrontendError):
            resolve_imports(main_proto, [tmpdir])


def test_proto_local_type_shadows_import_pass():
    # main.proto defines its own Address and also imports common.proto which
    # defines another Address. An unqualified field reference Address should
    # resolve to the local definition and pass validation.
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        (tmpdir / "common.proto").write_text(
            """
            syntax = "proto3";
            package common;

            message Address {}
            """
        )
        main_proto = tmpdir / "main.proto"
        main_proto.write_text(
            """
            syntax = "proto3";
            package main;
            import "common.proto";

            message Address {}
            message User { Address addr = 1; }
            """
        )
        schema = resolve_imports(main_proto, [tmpdir])
        validator = SchemaValidator(schema)
        assert validator.validate()


def test_proto_service_rpc_transitive_import_fail():
    # main.proto imports bar.proto which imports baz.proto. Using baz.Foo as
    # an RPC request/response type must fail because baz.proto is only
    # transitively imported.
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        (tmpdir / "baz.proto").write_text(
            """
            syntax = "proto3";
            package baz;

            message Foo {}
            """
        )
        (tmpdir / "bar.proto").write_text(
            """
            syntax = "proto3";
            package bar;
            import "baz.proto";

            message Bar {}
            """
        )
        main_proto = tmpdir / "main.proto"
        main_proto.write_text(
            """
            syntax = "proto3";
            package main;
            import "bar.proto";

            service FooService { rpc GetFoo(baz.Foo) returns (baz.Foo); }
            """
        )
        with pytest.raises(FrontendError):
            resolve_imports(main_proto, [tmpdir])


def test_proto_same_type_and_package_names_fail():
    # When a message name matches the package name, protoc rejects the relative
    # qualified form `demo.demo` because the first `demo` resolves to the
    # message (not the package) and that message has no nested `demo` type.
    # The absolute form `.demo.demo` is valid.
    source = """
    syntax = "proto3";
    package demo;

    message demo {}

    message Ref {
        demo.demo d = 1;
    }
    """
    with pytest.raises(FrontendError):
        ProtoFrontend().parse(source)
