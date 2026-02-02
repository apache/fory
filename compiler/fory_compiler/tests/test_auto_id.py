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

"""Tests for auto-generated type IDs."""

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.ir.type_id import compute_registered_type_id
from fory_compiler.ir.validator import SchemaValidator


def parse_schema(source: str):
    lexer = Lexer(source)
    parser = Parser(lexer.tokenize())
    return parser.parse()


def test_auto_id_generation_for_message_and_union():
    source = """
    package demo;

    message User {
        string name = 1;
    }

    union Item {
        User user = 1;
        string note = 2;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()

    msg = schema.messages[0]
    union = schema.unions[0]

    assert msg.type_id == compute_registered_type_id("demo.User")
    assert msg.id_generated is True
    assert msg.id_source == "demo.User"

    assert union.type_id == compute_registered_type_id("demo.Item")
    assert union.id_generated is True
    assert union.id_source == "demo.Item"


def test_alias_used_for_auto_id():
    source = """
    package demo;

    message User [alias="PersonAlias"] {
        string name = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()

    msg = schema.messages[0]
    assert msg.type_id == compute_registered_type_id("demo.PersonAlias")
    assert msg.id_generated is True
    assert msg.id_source == "demo.PersonAlias"


def test_package_alias_not_used_for_auto_id():
    source = """
    package demo alias alias_demo;

    message User {
        string name = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()

    msg = schema.messages[0]
    assert msg.type_id == compute_registered_type_id("demo.User")
    assert msg.id_generated is True
    assert msg.id_source == "demo.User"


def test_auto_id_generation_for_enum():
    source = """
    package demo;

    enum Status {
        OK = 0;
        ERROR = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()

    enum = schema.enums[0]
    assert enum.type_id == compute_registered_type_id("demo.Status")
    assert enum.id_generated is True
    assert enum.id_source == "demo.Status"


def test_explicit_id_not_overwritten():
    source = """
    package demo;

    message User [id=100] {
        string name = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()

    msg = schema.messages[0]
    assert msg.type_id == 100
    assert msg.id_generated is False


def test_auto_id_conflict_falls_back_to_name_registration():
    source = """
    package demo;

    message First [alias="Shared"] {
        string name = 1;
    }

    message Second [alias="Shared"] {
        string name = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()

    first, second = schema.messages
    assert first.type_id == compute_registered_type_id("demo.Shared")
    assert first.id_generated is True
    assert second.type_id is None
    assert second.id_generated is False
