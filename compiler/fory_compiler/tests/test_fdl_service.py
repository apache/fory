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

import pytest
from fory_compiler.frontend.fdl.parser import Parser, ParseError


def parse(source: str):
    parser = Parser.from_source(source)
    schema = parser.parse()
    return schema


def test_empty_service():
    source = """
    package test;
    service Greeter {}
    """
    schema = parse(source)
    assert len(schema.services) == 1
    service = schema.services[0]
    assert service.name == "Greeter"
    assert len(service.methods) == 0


def test_unary_rpc():
    source = """
    package test;
    
    message HelloRequest {}
    message HelloReply {}

    service Greeter {
        rpc SayHello (HelloRequest) returns (HelloReply);
    }
    """
    schema = parse(source)
    service = schema.services[0]
    assert len(service.methods) == 1
    method = service.methods[0]
    assert method.name == "SayHello"
    assert method.request_type.name == "HelloRequest"
    assert method.response_type.name == "HelloReply"
    assert not method.client_streaming
    assert not method.server_streaming


def test_client_streaming_rpc():
    source = """
    package test;
    
    message HelloRequest {}
    message HelloReply {}

    service Greeter {
        rpc LotsOfGreetings (stream HelloRequest) returns (HelloReply);
    }
    """
    schema = parse(source)
    service = schema.services[0]
    method = service.methods[0]
    assert method.name == "LotsOfGreetings"
    assert method.client_streaming
    assert not method.server_streaming


def test_server_streaming_rpc():
    source = """
    package test;
    
    message HelloRequest {}
    message HelloReply {}

    service Greeter {
        rpc LotsOfReplies (HelloRequest) returns (stream HelloReply);
    }
    """
    schema = parse(source)
    service = schema.services[0]
    method = service.methods[0]
    assert method.name == "LotsOfReplies"
    assert not method.client_streaming
    assert method.server_streaming


def test_bidi_streaming_rpc():
    source = """
    package test;
    
    message HelloRequest {}
    message HelloReply {}

    service Greeter {
        rpc BidiHello (stream HelloRequest) returns (stream HelloReply);
    }
    """
    schema = parse(source)
    service = schema.services[0]
    method = service.methods[0]
    assert method.name == "BidiHello"
    assert method.client_streaming
    assert method.server_streaming


def test_service_options():
    source = """
    package test;
    
    service Greeter {
        option deprecated = true;
    }
    """
    schema = parse(source)
    service = schema.services[0]
    assert service.options["deprecated"] is True


def test_method_options():
    source = """
    package test;
    
    message HelloRequest {}
    message HelloReply {}

    service Greeter {
        rpc SayHello (HelloRequest) returns (HelloReply) {
            option deprecated = true;
        }
    }
    """
    schema = parse(source)
    service = schema.services[0]
    method = service.methods[0]
    assert method.options["deprecated"] is True


def test_invalid_syntax_missing_returns():
    source = """
    package test;
    service Greeter {
        rpc SayHello (HelloRequest);
    }
    """
    with pytest.raises(ParseError):
        parse(source)


def test_invalid_syntax_missing_parens():
    source = """
    package test;
    service Greeter {
        rpc SayHello HelloRequest returns HelloReply;
    }
    """
    with pytest.raises(ParseError):
        parse(source)
