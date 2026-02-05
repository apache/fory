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

"""Tests for weak_ref field option."""

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.ir.validator import SchemaValidator


def parse_schema(source: str):
    return Parser(Lexer(source).tokenize()).parse()


def test_parse_weak_ref_option():
    source = """
    message Node {
        ref(weak=true) Node parent = 1;
    }
    """
    schema = parse_schema(source)
    field = schema.messages[0].fields[0]
    assert field.ref_options.get("weak_ref") is True


def test_weak_ref_requires_ref():
    source = """
    message Parent {
        Child child = 1 [weak_ref = true];
    }

    message Child {
        int32 id = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert not validator.validate()
    assert any(
        "weak_ref requires ref tracking" in err.message for err in validator.errors
    )


def test_weak_ref_rejects_primitive():
    source = """
    message Foo {
        ref(weak=true) int32 id = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert not validator.validate()
    assert any(
        "weak_ref is only valid for named message types" in err.message
        for err in validator.errors
    )


def test_weak_ref_rejects_enum():
    source = """
    enum Status {
        UNKNOWN = 0;
    }

    message Foo {
        ref(weak=true) Status status = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert not validator.validate()
    assert any(
        "weak_ref is only valid for message/union types" in err.message
        for err in validator.errors
    )


def test_repeated_ref_weak_ref_ok():
    source = """
    message Parent {
        repeated ref(weak=true) Child children = 1;
    }

    message Child {
        int32 id = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()


def test_weak_ref_requires_repeated_ref():
    source = """
    message Parent {
        repeated Child children = 1 [weak_ref = true];
    }

    message Child {
        int32 id = 1;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert not validator.validate()
    assert any(
        "weak_ref requires list element refs" in err.message for err in validator.errors
    )


def test_list_and_map_ref_options_with_thread_safe():
    source = """
    message Foo {
        int32 id = 1;
    }

    message Bar {
        int32 id = 1;
    }

    message Holder {
        list<ref Foo> foos = 1;
        list<ref(weak=true, thread_safe=true) Bar> bars = 2;
        map<Foo, ref(weak=true, thread_safe=true) Bar> bar_map = 3;
    }
    """
    schema = parse_schema(source)
    validator = SchemaValidator(schema)
    assert validator.validate()

    holder = next(m for m in schema.messages if m.name == "Holder")
    foos = holder.fields[0]
    bars = holder.fields[1]
    bar_map = holder.fields[2]

    assert foos.element_ref is True
    assert foos.element_ref_options == {}

    assert bars.element_ref is True
    assert bars.element_ref_options.get("weak_ref") is True
    assert bars.element_ref_options.get("thread_safe_pointer") is True

    assert bar_map.field_type.value_ref is True
    assert bar_map.field_type.value_ref_options.get("weak_ref") is True
    assert bar_map.field_type.value_ref_options.get("thread_safe_pointer") is True
