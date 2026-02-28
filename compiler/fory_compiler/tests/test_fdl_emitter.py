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

"""Tests for FDL emission."""

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.frontend.proto import ProtoFrontend
from fory_compiler.ir.emitter import FDLEmitter


def test_emit_round_trip_preserves_list_modifiers():
    source = """
    package demo;

    message Foo [id=10] {
        list<optional string> tags = 1;
        list<ref Bar> items = 2;
        fixed_uint32 count = 3;
    }

    message Bar [id=11] {
        string name = 1;
    }
    """
    lexer = Lexer(source)
    parser = Parser(lexer.tokenize())
    schema = parser.parse()

    emitted = FDLEmitter(schema).emit()
    assert "message Foo [id=10]" in emitted
    assert "list<optional string> tags = 1;" in emitted
    assert "list<ref Bar> items = 2;" in emitted
    assert "fixed_uint32 count = 3;" in emitted

    round_trip = Parser(Lexer(emitted).tokenize()).parse()
    foo = round_trip.messages[0]
    assert foo.fields[0].element_optional is True
    assert foo.fields[1].element_ref is True


def test_emit_from_proto_translation():
    proto_source = """
    syntax = "proto3";
    package demo;

    message Person {
        option (fory).id = 101;
        int32 id = 1;
        sint64 signed = 2;
        fixed32 fixed_u32 = 3;
        sfixed64 fixed_s64 = 4;
        uint64 uid = 5;
    }
    """
    schema = ProtoFrontend().parse(proto_source)
    emitted = FDLEmitter(schema).emit()

    assert "message Person [id=101]" in emitted
    assert "uint32 id = 1;" in emitted
    assert "int64 signed = 2;" in emitted
    assert "fixed_uint32 fixed_u32 = 3;" in emitted
    assert "fixed_int64 fixed_s64 = 4;" in emitted
    assert "uint64 uid = 5;" in emitted


def test_emit_union_round_trip():
    source = """
    message Dog [id=10] {
        string name = 1;
    }

    message Cat [id=11] {
        string name = 1;
    }

    union Animal [id=0] {
        Dog dog = 1;
        Cat cat = 2;
    }
    """
    schema = Parser(Lexer(source).tokenize()).parse()
    emitted = FDLEmitter(schema).emit()

    assert "union Animal [id=0]" in emitted
    assert "Dog dog = 1;" in emitted
    assert "Cat cat = 2;" in emitted

    round_trip = Parser(Lexer(emitted).tokenize()).parse()
    union = round_trip.unions[0]
    assert union.name == "Animal"
    assert [f.name for f in union.fields] == ["dog", "cat"]
