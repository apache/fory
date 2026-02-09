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

"""Tests for FBS service parsing."""

from fory_compiler.frontend.fbs.lexer import Lexer
from fory_compiler.frontend.fbs.parser import Parser
from fory_compiler.frontend.fbs.translator import FbsTranslator


def parse_and_translate(source):
    lexer = Lexer(source)
    parser = Parser(lexer.tokenize())
    schema = parser.parse()
    translator = FbsTranslator(schema)
    return translator.translate()


def test_rpc_service_parsing():
    source = """
    namespace demo;

    table Request {
        id: int;
    }

    table Response {
        result: string;
    }

    rpc_service Greeter {
        SayHello(Request):Response;
        SayGoodbye(Request):Response (deprecated);
    }
    """
    schema = parse_and_translate(source)
    assert len(schema.services) == 1
    service = schema.services[0]
    assert service.name == "Greeter"
    assert len(service.methods) == 2

    assert service.methods[0].name == "SayHello"
    assert service.methods[0].request_type.name == "Request"
    assert service.methods[0].response_type.name == "Response"
    assert not service.methods[0].client_streaming
    assert not service.methods[0].server_streaming

    assert service.methods[1].name == "SayGoodbye"
    assert service.methods[1].options["deprecated"] is True


def test_service_keyword_parsing():
    """Test using 'service' keyword instead of 'rpc_service'."""
    source = """
    namespace demo;

    service Greeter {
        SayHello(Request):Response;
    }
    """
    schema = parse_and_translate(source)
    assert len(schema.services) == 1
    assert schema.services[0].name == "Greeter"


def test_streaming_attributes():
    source = """
    namespace demo;

    rpc_service Streamer {
        ClientStream(Request):Response (streaming: "client");
        ServerStream(Request):Response (streaming: "server");
        BidiStream(Request):Response (streaming: "bidi");
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
