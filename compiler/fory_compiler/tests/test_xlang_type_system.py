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

"""Tests for xlang type-system syntax and validation."""

import pytest

from fory_compiler.frontend.fdl.parser import ParseError, Parser
from fory_compiler.frontend.proto import ProtoFrontend
from fory_compiler.ir.ast import ArrayType, ListType, MapType, PrimitiveType
from fory_compiler.ir.emitter import FDLEmitter
from fory_compiler.ir.types import PrimitiveKind
from fory_compiler.ir.validator import (
    INVALID_MAP_KEY_MESSAGE,
    OPTIONAL_ANY_MESSAGE,
    SchemaValidator,
)


def parse_schema(source: str):
    return Parser.from_source(source).parse()


def validate_schema(source: str):
    schema = parse_schema(source)
    validator = SchemaValidator(schema, allow_nested_collections=True)
    return schema, validator, validator.validate()


def test_keyword_integer_modifiers_parse_and_emit():
    schema, validator, ok = validate_schema(
        """
        message Encoded {
            fixed int32 fixed_i32 = 1;
            varint int64 var_i64 = 2;
            fixed uint32 fixed_u32 = 3;
            tagged uint64 tagged_u64 = 4;
            list<optional fixed int32> maybe_fixed = 5;
        }
        """
    )

    assert ok, validator.errors
    fields = {field.name: field.field_type for field in schema.messages[0].fields}
    assert fields["fixed_i32"].kind == PrimitiveKind.INT32
    assert fields["fixed_i32"].encoding_modifier == "fixed"
    assert fields["var_i64"].kind == PrimitiveKind.INT64
    assert fields["var_i64"].encoding_modifier == "varint"
    assert fields["fixed_u32"].kind == PrimitiveKind.UINT32
    assert fields["tagged_u64"].kind == PrimitiveKind.UINT64
    assert fields["tagged_u64"].encoding_modifier == "tagged"

    maybe_fixed = fields["maybe_fixed"]
    assert isinstance(maybe_fixed, ListType)
    assert maybe_fixed.element_type.kind == PrimitiveKind.INT32
    assert maybe_fixed.element_type.encoding_modifier == "fixed"

    emitted = FDLEmitter(schema).emit()
    assert "fixed int32 fixed_i32 = 1;" in emitted
    assert "varint int64 var_i64 = 2;" in emitted
    assert "tagged uint64 tagged_u64 = 4;" in emitted
    assert "list<optional fixed int32> maybe_fixed = 5;" in emitted


def test_array_type_is_distinct_from_list_type():
    schema, validator, ok = validate_schema(
        """
        message Containers {
            list<int32> numbers = 1;
            array<int32> dense_numbers = 2;
            map<string, array<uint8>> bytes_by_name = 3;
        }
        """
    )

    assert ok, validator.errors
    fields = {field.name: field.field_type for field in schema.messages[0].fields}
    assert isinstance(fields["numbers"], ListType)
    assert isinstance(fields["dense_numbers"], ArrayType)
    assert fields["dense_numbers"].element_type.kind == PrimitiveKind.INT32
    assert isinstance(fields["bytes_by_name"], MapType)
    assert isinstance(fields["bytes_by_name"].value_type, ArrayType)

    emitted = FDLEmitter(schema).emit()
    assert "list<int32> numbers = 1;" in emitted
    assert "array<int32> dense_numbers = 2;" in emitted
    assert "map<string, array<uint8>> bytes_by_name = 3;" in emitted


@pytest.mark.parametrize(
    "element",
    [
        "fixed int32",
        "varint int64",
        "tagged uint64",
        "string",
        "bytes",
        "any",
        "date",
        "timestamp",
        "duration",
        "decimal",
        "ExampleState",
        "Child",
        "ChildUnion",
    ],
)
def test_array_rejects_non_fixed_width_number_and_bool_elements(element):
    source = f"""
    enum ExampleState {{
        UNKNOWN = 0;
        READY = 1;
    }}

    union ChildUnion {{
        string note = 1;
    }}

    message Child {{
        string name = 1;
    }}

    message InvalidArray {{
        array<{element}> values = 1;
    }}
    """
    _schema, validator, ok = validate_schema(source)
    assert not ok
    assert any("array<T> elements" in err.message for err in validator.errors)


def test_array_rejects_optional_or_ref_elements_at_parse_time():
    with pytest.raises(ParseError, match="optional/ref"):
        parse_schema(
            """
            message InvalidArray {
                array<optional int32> values = 1;
            }
            """
        )

    with pytest.raises(ParseError, match="optional/ref"):
        parse_schema(
            """
            message Child {
                string name = 1;
            }

            message InvalidArray {
                array<ref Child> values = 1;
            }
            """
        )


@pytest.mark.parametrize(
    "key_type",
    [
        "any",
        "bytes",
        "float16",
        "bfloat16",
        "float32",
        "float64",
        "decimal",
        "list<int32>",
        "array<uint8>",
        "map<string, int32>",
    ],
)
def test_map_rejects_non_portable_key_types(key_type):
    _schema, validator, ok = validate_schema(
        f"""
        message InvalidMap {{
            map<{key_type}, string> values = 1;
        }}
        """
    )

    assert not ok
    assert any(INVALID_MAP_KEY_MESSAGE in err.message for err in validator.errors)


@pytest.mark.parametrize(
    "source",
    [
        """
        message Key {
            string id = 1;
        }

        message InvalidMap {
            map<Key, string> values = 1;
        }
        """,
        """
        union Choice {
            string text = 1;
        }

        message InvalidMap {
            map<Choice, string> values = 1;
        }
        """,
        """
        message Key {
            string id = 1;
        }

        union InvalidUnion {
            map<Key, string> values = 1;
        }
        """,
        """
        message Outer {
            message Key {
                string id = 1;
            }

            map<Key, string> values = 1;
        }
        """,
        """
        message Outer {
            message Key {
                string id = 1;
            }
        }

        message InvalidMap {
            map<Outer.Key, string> values = 1;
        }
        """,
        """
        message Outer {
            union Choice {
                string text = 1;
            }

            map<Choice, string> values = 1;
        }
        """,
        """
        message Outer {
            union Choice {
                string text = 1;
            }
        }

        message InvalidMap {
            map<Outer.Choice, string> values = 1;
        }
        """,
    ],
)
def test_map_rejects_message_and_union_key_types(source):
    _schema, validator, ok = validate_schema(source)

    assert not ok
    assert any(INVALID_MAP_KEY_MESSAGE in err.message for err in validator.errors)


@pytest.mark.parametrize(
    "source",
    [
        """
        enum Status {
            UNKNOWN = 0;
            READY = 1;
        }

        message Holder {
            map<Status, string> values = 1;
        }
        """,
        """
        message Outer {
            enum Status {
                UNKNOWN = 0;
                READY = 1;
            }

            map<Status, string> values = 1;
        }
        """,
        """
        message Outer {
            enum Status {
                UNKNOWN = 0;
                READY = 1;
            }
        }

        message Holder {
            map<Outer.Status, string> values = 1;
        }
        """,
    ],
)
def test_map_accepts_enum_key_types(source):
    _schema, validator, ok = validate_schema(source)

    assert ok, validator.errors


@pytest.mark.parametrize(
    "source",
    [
        """
        message Invalid {
            optional any value = 1;
        }
        """,
        """
        message Invalid {
            any value = 1 [nullable = true];
        }
        """,
        """
        message Invalid {
            list<optional any> values = 1;
        }
        """,
        """
        message Invalid {
            map<string, optional any> values = 1;
        }
        """,
    ],
)
def test_optional_any_is_rejected(source):
    _schema, validator, ok = validate_schema(source)

    assert not ok
    assert any(OPTIONAL_ANY_MESSAGE in err.message for err in validator.errors)


@pytest.mark.parametrize(
    "source",
    [
        """
        message Valid {
            any value = 1;
        }
        """,
        """
        message Valid {
            optional list<any> values = 1;
        }
        """,
    ],
)
def test_non_direct_optional_any_is_accepted(source):
    _schema, validator, ok = validate_schema(source)

    assert ok, validator.errors


def test_proto_repeated_fields_remain_list_type():
    schema = ProtoFrontend().parse(
        """
        syntax = "proto3";

        message Numbers {
            repeated int32 values = 1;
        }
        """
    )

    field_type = schema.messages[0].fields[0].field_type
    assert isinstance(field_type, ListType)
    assert isinstance(field_type.element_type, PrimitiveType)
    assert field_type.element_type.kind == PrimitiveKind.UINT32
    assert field_type.element_type.encoding_modifier is None
