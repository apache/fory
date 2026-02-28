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

"""Tests for collection nesting validation."""

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.ir.validator import SchemaValidator


def parse_schema(source: str):
    return Parser(Lexer(source).tokenize()).parse()


def assert_nested_collections_rejected(source: str) -> None:
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert not validator.validate()
    assert any(
        "nested list/map types are not allowed" in err.message
        for err in validator.errors
    )


def test_nested_list_rejected():
    source = """
    message Foo {
        list<list<int32>> values = 1;
    }
    """
    assert_nested_collections_rejected(source)


def test_list_of_map_rejected():
    source = """
    message Foo {
        list<map<string, int32>> values = 1;
    }
    """
    assert_nested_collections_rejected(source)


def test_map_with_list_value_rejected():
    source = """
    message Foo {
        map<string, list<int32>> values = 1;
    }
    """
    assert_nested_collections_rejected(source)


def test_map_with_map_value_rejected():
    source = """
    message Foo {
        map<string, map<string, int32>> values = 1;
    }
    """
    assert_nested_collections_rejected(source)


def test_map_with_list_key_rejected():
    source = """
    message Foo {
        map<list<int32>, int32> values = 1;
    }
    """
    assert_nested_collections_rejected(source)
