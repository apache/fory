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

"""Tests for Proto service parsing."""

from fory_compiler.frontend.proto.lexer import Lexer
from fory_compiler.frontend.proto.parser import Parser
from fory_compiler.frontend.proto.translator import ProtoTranslator


def parse_and_translate(source):
    lexer = Lexer(source)
    parser = Parser(lexer.tokenize())
    schema = parser.parse()
    translator = ProtoTranslator(schema)
    return translator.translate()


def test_service_parsing():
    source = """
    syntax = "proto3";
    package demo;

    message Request {
        int32 id = 1;
    }

    message Response {
        string result = 1;
    }

    service Greeter {
        rpc SayHello (Request) returns (Response);
        rpc SayGoodbye (Request) returns (Response) {
            option deprecated = true;
        }
    }
    """
    schema = parse_and_translate(source)
    assert len(schema.services) == 1
    service = schema.services[0]
    assert service.name == "Greeter"
    assert len(service.methods) == 2

    m1 = service.methods[0]
    assert m1.name == "SayHello"
    assert m1.request_type.name == "Request"
    assert m1.response_type.name == "Response"
    assert not m1.client_streaming
    assert not m1.server_streaming

    m2 = service.methods[1]
    assert m2.name == "SayGoodbye"
    assert m2.options["deprecated"] is True


def test_streaming_rpc():
    source = """
    syntax = "proto3";
    package demo;

    message Request {}
    message Response {}

    service Streamer {
        rpc ClientStream (stream Request) returns (Response);
        rpc ServerStream (Request) returns (stream Response);
        rpc BidiStream (stream Request) returns (stream Response);
    }
    """
    schema = parse_and_translate(source)
    service = schema.services[0]

    # Client streaming
    m1 = service.methods[0]
    assert m1.name == "ClientStream"
    assert m1.client_streaming is True
    assert m1.server_streaming is False

    # Server streaming
    m2 = service.methods[1]
    assert m2.name == "ServerStream"
    assert m2.client_streaming is False
    assert m2.server_streaming is True

    # Bidi streaming
    m3 = service.methods[2]
    assert m3.name == "BidiStream"
    assert m3.client_streaming is True
    assert m3.server_streaming is True


def test_service_options():
    source = """
    syntax = "proto3";
    package demo;

    service OptionsService {
        option deprecated = true;
        rpc Method (Req) returns (Res);
    }
    
    message Req {}
    message Res {}
    """
    schema = parse_and_translate(source)
    service = schema.services[0]
    assert service.options["deprecated"] is True
