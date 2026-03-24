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

from fory_compiler.frontend.proto import ProtoFrontend
from fory_compiler.ir.ast import PrimitiveType
from fory_compiler.ir.types import PrimitiveKind


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
