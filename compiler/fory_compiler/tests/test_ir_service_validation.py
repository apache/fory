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

"""Tests for IR-level service validation rules."""

from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.frontend.proto.lexer import Lexer as ProtoLexer
from fory_compiler.frontend.proto.parser import Parser as ProtoParser
from fory_compiler.frontend.proto.translator import ProtoTranslator
from fory_compiler.frontend.fbs.lexer import Lexer as FbsLexer
from fory_compiler.frontend.fbs.parser import Parser as FbsParser
from fory_compiler.frontend.fbs.translator import FbsTranslator
from fory_compiler.ir.validator import SchemaValidator


def parse_fdl(source: str):
    return Parser.from_source(source).parse()


def parse_proto(source: str):
    lexer = ProtoLexer(source)
    parser = ProtoParser(lexer.tokenize())
    return ProtoTranslator(parser.parse()).translate()


def parse_fbs(source: str):
    lexer = FbsLexer(source)
    parser = FbsParser(lexer.tokenize())
    return FbsTranslator(parser.parse()).translate()


def validate(schema):
    validator = SchemaValidator(schema)
    validator.validate()
    return validator


def test_duplicate_service_names_fails_validation():
    source = """
    package test;

    service Alpha {}
    service Alpha {}
    """
    v = validate(parse_fdl(source))
    assert any("Duplicate service name: Alpha" in e.message for e in v.errors)


def test_unique_service_names_passes_validation():
    source = """
    package test;

    service Alpha {}
    service Beta {}
    """
    v = validate(parse_fdl(source))
    assert v.errors == []


def test_duplicate_method_names_fails_validation():
    source = """
    package test;

    message Req {}
    message Res {}

    service Greeter {
        rpc SayHello (Req) returns (Res);
        rpc SayHello (Req) returns (Res);
    }
    """
    v = validate(parse_fdl(source))
    assert any(
        "Duplicate method name in service Greeter: SayHello" in e.message
        for e in v.errors
    )


def test_same_method_name_in_different_services_passes_validation():
    source = """
    package test;

    message Req {}
    message Res {}

    service Alpha {
        rpc SayHello (Req) returns (Res);
    }

    service Beta {
        rpc SayHello (Req) returns (Res);
    }
    """
    v = validate(parse_fdl(source))
    assert v.errors == []


def test_unique_method_names_passes_validation():
    source = """
    package test;

    message Req {}
    message Res {}

    service Greeter {
        rpc SayHello (Req) returns (Res);
        rpc SayGoodbye (Req) returns (Res);
    }
    """
    v = validate(parse_fdl(source))
    assert v.errors == []


def test_rpc_request_type_enum_fails_validation():
    source = """
    package test;

    enum Status { OK = 0; }
    message Res {}

    service Svc {
        rpc Call (Status) returns (Res);
    }
    """
    v = validate(parse_fdl(source))
    assert any(
        "RPC type 'Status'" in e.message and "not a enum" in e.message for e in v.errors
    )


def test_rpc_response_type_enum_fails_validation():
    source = """
    package test;

    message Req {}
    enum Status { OK = 0; }

    service Svc {
        rpc Call (Req) returns (Status);
    }
    """
    v = validate(parse_fdl(source))
    assert any(
        "RPC type 'Status'" in e.message and "not a enum" in e.message for e in v.errors
    )


def test_rpc_request_type_union_fails_validation():
    source = """
    package test;

    union Payload { string text = 1; }
    message Res {}

    service Svc {
        rpc Call (Payload) returns (Res);
    }
    """
    v = validate(parse_fdl(source))
    assert any(
        "RPC type 'Payload'" in e.message and "not a union" in e.message
        for e in v.errors
    )


def test_rpc_response_type_union_fails_validation():
    source = """
    package test;

    message Req {}
    union Payload { string text = 1; }

    service Svc {
        rpc Call (Req) returns (Payload);
    }
    """
    v = validate(parse_fdl(source))
    assert any(
        "RPC type 'Payload'" in e.message and "not a union" in e.message
        for e in v.errors
    )


def test_rpc_message_types_pass_validation():
    source = """
    package test;

    message Req {}
    message Res {}

    service Svc {
        rpc Call (Req) returns (Res);
    }
    """
    v = validate(parse_fdl(source))
    assert v.errors == []


def test_proto_rpc_enum_type_fails_validation():
    source = """
    syntax = "proto3";
    package test;

    enum Status { OK = 0; }
    message Res { string result = 1; }

    service Svc {
        rpc Call (Status) returns (Res);
    }
    """
    v = validate(parse_proto(source))
    assert any("RPC type 'Status'" in e.message for e in v.errors)


def test_fbs_rpc_duplicate_method_fails_validation():
    source = """
    namespace test;

    table Req { id: int; }
    table Res { result: string; }

    rpc_service Svc {
        Call(Req):Res;
        Call(Req):Res;
    }
    """
    v = validate(parse_fbs(source))
    assert any(
        "Duplicate method name in service Svc: Call" in e.message for e in v.errors
    )


def test_client_streaming_rpc_passes_validation():
    source = """
    package test;

    message Req {}
    message Res {}

    service Svc {
        rpc Call (stream Req) returns (Res);
    }
    """
    v = validate(parse_fdl(source))
    assert v.errors == []


def test_server_streaming_rpc_passes_validation():
    source = """
    package test;

    message Req {}
    message Res {}

    service Svc {
        rpc Call (Req) returns (stream Res);
    }
    """
    v = validate(parse_fdl(source))
    assert v.errors == []


def test_bidi_streaming_rpc_passes_validation():
    source = """
    package test;

    message Req {}
    message Res {}

    service Svc {
        rpc Call (stream Req) returns (stream Res);
    }
    """
    v = validate(parse_fdl(source))
    assert v.errors == []


def test_proto_streaming_rpc_passes_validation():
    source = """
    syntax = "proto3";
    package test;

    message Req { string id = 1; }
    message Res { string result = 1; }

    service Svc {
        rpc ClientStream (stream Req) returns (Res);
        rpc ServerStream (Req) returns (stream Res);
        rpc BidiStream (stream Req) returns (stream Res);
    }
    """
    v = validate(parse_proto(source))
    assert v.errors == []
