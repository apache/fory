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

"""Codegen smoke tests for schemas that contain service definitions."""

from pathlib import Path
import shutil
import subprocess
from textwrap import dedent
from typing import Dict, Tuple, Type

import pytest

from fory_compiler.cli import (
    cmd_compile,
    compile_file,
    main as foryc_main,
    parse_args,
    resolve_imports,
    validate_scala_generation,
)
from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.frontend.fbs.lexer import Lexer as FbsLexer
from fory_compiler.frontend.fbs.parser import Parser as FbsParser
from fory_compiler.frontend.fbs.translator import FbsTranslator
from fory_compiler.frontend.proto.lexer import Lexer as ProtoLexer
from fory_compiler.frontend.proto.parser import Parser as ProtoParser
from fory_compiler.frontend.proto.translator import ProtoTranslator
from fory_compiler.generators.base import BaseGenerator, GeneratorOptions
from fory_compiler.generators.cpp import CppGenerator
from fory_compiler.generators.csharp import CSharpGenerator
from fory_compiler.generators.dart import DartGenerator
from fory_compiler.generators.go import GoGenerator
from fory_compiler.generators.java import JavaGenerator
from fory_compiler.generators.javascript import JavaScriptGenerator
from fory_compiler.generators.kotlin import KotlinGenerator
from fory_compiler.generators.python import PythonGenerator
from fory_compiler.generators.rust import RustGenerator
from fory_compiler.generators.scala import ScalaGenerator
from fory_compiler.generators.swift import SwiftGenerator
from fory_compiler.ir.ast import Schema
from fory_compiler.ir.validator import SchemaValidator


GENERATOR_CLASSES: Tuple[Type[BaseGenerator], ...] = (
    JavaGenerator,
    PythonGenerator,
    CppGenerator,
    RustGenerator,
    GoGenerator,
    JavaScriptGenerator,
    CSharpGenerator,
    SwiftGenerator,
    ScalaGenerator,
    KotlinGenerator,
    DartGenerator,
)

_GREETER_WITH_SERVICE = dedent(
    """
    package demo.greeter;

    message HelloRequest {
        string name = 1;
    }

    message HelloReply {
        string reply = 1;
    }

    service Greeter {
        rpc SayHello (HelloRequest) returns (HelloReply);
    }
    """
)

_GREETER_WITHOUT_SERVICE = dedent(
    """
    package demo.greeter;

    message HelloRequest {
        string name = 1;
    }

    message HelloReply {
        string reply = 1;
    }
    """
)


def parse_fdl(source: str) -> Schema:
    return Parser(Lexer(source).tokenize()).parse()


def parse_proto(source: str) -> Schema:
    return ProtoTranslator(
        ProtoParser(ProtoLexer(source).tokenize()).parse()
    ).translate()


def parse_fbs(source: str) -> Schema:
    return FbsTranslator(FbsParser(FbsLexer(source).tokenize()).parse()).translate()


def generate_files(
    schema: Schema, generator_cls: Type[BaseGenerator]
) -> Dict[str, str]:
    options = GeneratorOptions(output_dir=Path("/tmp"))
    generator = generator_cls(schema, options)
    return {item.path: item.content for item in generator.generate()}


def generate_service_files(
    schema: Schema, generator_cls: Type[BaseGenerator]
) -> Dict[str, str]:
    options = GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    generator = generator_cls(schema, options)
    return {item.path: item.content for item in generator.generate_services()}


def test_services_do_not_change_models():
    schema_with = parse_fdl(_GREETER_WITH_SERVICE)
    schema_without = parse_fdl(_GREETER_WITHOUT_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        files_with = generate_files(schema_with, generator_cls)
        files_without = generate_files(schema_without, generator_cls)
        assert files_with == files_without, (
            f"{generator_cls.language_name}: service definition changed message output"
        )


def test_unsupported_generators_no_services():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        if generator_cls in (
            JavaGenerator,
            PythonGenerator,
            GoGenerator,
            CSharpGenerator,
            RustGenerator,
            ScalaGenerator,
            KotlinGenerator,
            JavaScriptGenerator,
            DartGenerator,
        ):
            continue
        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = generator_cls(schema, options)
        assert generator.generate_services() == [], (
            f"{generator_cls.language_name}: generate_services() should return []"
        )


def test_java_grpc_fory_marshaller():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, JavaGenerator)
    assert set(files) == {"demo/greeter/GreeterGrpc.java"}
    content = files["demo/greeter/GreeterGrpc.java"]
    assert 'SERVICE_NAME = "demo.greeter.Greeter"' in content
    assert "io.grpc.MethodDescriptor<HelloRequest, HelloReply>" in content
    assert "implements io.grpc.MethodDescriptor.Marshaller<T>" in content
    assert "ThreadSafeFory FORY = GreeterForyModule.getFory()" in content
    assert "fory.serialize(value)" in content
    assert "fory.deserialize(readBytes(stream), type)" in content
    assert "io.grpc.KnownLength" in content
    assert "ProtoUtils" not in content


def test_python_grpc_byte_callbacks():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, PythonGenerator)
    assert set(files) == {"demo_greeter_grpc.py"}
    content = files["demo_greeter_grpc.py"]
    assert "import grpc.aio" in content
    assert "class GreeterStub(object):" in content
    assert "class GreeterServicer(object):" in content
    assert "def add_servicer(servicer, server):" in content
    assert "add_GreeterServicer_to_server" not in content
    assert "self.say_hello = channel.unary_unary(" in content
    assert "async def say_hello(self, request, context):" in content
    assert (
        'await context.abort(grpc.StatusCode.UNIMPLEMENTED, "Method not implemented!")'
        in content
    )
    assert "raise NotImplementedError" not in content
    assert '        "SayHello": grpc.unary_unary_rpc_method_handler(' in content
    assert "servicer.say_hello" in content
    assert "return _models._get_fory().serialize(value)" in content
    assert "return _models._get_fory().deserialize(data)" in content
    assert '"/demo.greeter.Greeter/SayHello"' in content
    assert "SerializeToString" not in content
    assert "FromString" not in content


def test_python_grpc_sync_mode():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    generator = PythonGenerator(
        schema,
        GeneratorOptions(output_dir=Path("/tmp"), grpc=True, grpc_python_mode="sync"),
    )
    files = {item.path: item.content for item in generator.generate_services()}
    assert set(files) == {"demo_greeter_grpc.py"}
    content = files["demo_greeter_grpc.py"]
    assert "import grpc.aio" not in content
    assert "class GreeterStub(object):" in content
    assert "class GreeterServicer(object):" in content
    assert "self.say_hello = channel.unary_unary(" in content
    assert "def say_hello(self, request, context):" in content
    assert "async def say_hello" not in content
    assert "context.set_code(grpc.StatusCode.UNIMPLEMENTED)" in content
    assert 'context.set_details("Method not implemented!")' in content
    assert 'raise NotImplementedError("Method not implemented!")' in content
    assert '"/demo.greeter.Greeter/SayHello"' in content
    assert "SerializeToString" not in content
    assert "FromString" not in content


def test_js_grpc_uses_model_fory():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, JavaScriptGenerator)
    assert set(files) == {"demo_greeter_grpc.ts"}
    content = files["demo_greeter_grpc.ts"]
    assert 'import * as grpc from "@grpc/grpc-js";' in content
    assert (
        "import { HelloReply, HelloRequest, deserializeHelloReply, "
        'deserializeHelloRequest, serializeHelloReply, serializeHelloRequest } from "./demo_greeter";'
        in content
    )
    assert "export interface GreeterHandlers" in content
    assert "createGreeterServiceDefinition()" in content
    assert (
        "addGreeterService(server: grpc.Server, handlers: GreeterHandlers)" in content
    )
    assert "export class GreeterClient extends grpc.Client" in content
    assert '"/demo.greeter.Greeter/SayHello"' in content
    assert (
        "const serializeHelloRequestGrpc = (value: HelloRequest): Buffer => "
        "toGrpcBuffer(serializeHelloRequest(value));"
    ) in content
    assert "requestSerialize: serializeHelloRequestGrpc" in content
    assert "requestDeserialize: deserializeHelloRequest" in content
    assert "responseSerialize: serializeHelloReplyGrpc" in content
    assert "responseDeserialize: deserializeHelloReply" in content
    assert "      serializeHelloRequestGrpc," in content
    assert "      deserializeHelloReply," in content
    assert "(value: HelloRequest) => toGrpcBuffer" not in content
    assert "Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength)" in content
    assert "options?.fory" not in content
    assert "new Fory" not in content
    assert "WeakMap<Fory" not in content
    assert "getForyState" not in content
    assert "getRootCodec" not in content
    assert "Type.struct" not in content
    assert "Type.union" not in content


def test_js_grpc_named_union_root():
    schema = parse_fdl(
        dedent(
            """
            option enable_auto_type_id = false;
            package demo.greeter;

            message HelloRequest {
                string name = 1;
            }

            union Greeting {
                HelloRequest hello = 1;
            }

            service Greeter {
                rpc Classify (Greeting) returns (Greeting);
            }
            """
        )
    )
    files = generate_service_files(schema, JavaScriptGenerator)
    content = files["demo_greeter_grpc.ts"]

    assert (
        'import { Greeting, deserializeGreeting, serializeGreeting } from "./demo_greeter";'
        in content
    )
    assert "requestSerialize: serializeGreetingGrpc" in content
    assert "requestDeserialize: deserializeGreeting" in content
    assert "responseSerialize: serializeGreetingGrpc" in content
    assert "responseDeserialize: deserializeGreeting" in content
    assert "FORY_STATE" not in content
    assert "getRootCodec" not in content
    assert "Type.union" not in content


def test_js_grpc_evolving_false_roots():
    numeric_schema = parse_fdl(
        dedent(
            """
            package demo.stable;

            message Stable [id=100, evolving=false] {
                string name = 1;
            }

            service StableService {
                rpc Call (Stable) returns (Stable);
            }
            """
        )
    )
    numeric_content = generate_service_files(numeric_schema, JavaScriptGenerator)[
        "demo_stable_grpc.ts"
    ]
    assert (
        'import { Stable, deserializeStable, serializeStable } from "./demo_stable";'
        in numeric_content
    )
    assert "requestSerialize: serializeStableGrpc" in numeric_content

    named_schema = parse_fdl(
        dedent(
            """
            option enable_auto_type_id = false;
            package demo.stable;

            message Stable [evolving=false] {
                string name = 1;
            }

            service StableService {
                rpc Call (Stable) returns (Stable);
            }
            """
        )
    )
    named_content = generate_service_files(named_schema, JavaScriptGenerator)[
        "demo_stable_grpc.ts"
    ]
    assert (
        'import { Stable, deserializeStable, serializeStable } from "./demo_stable";'
        in named_content
    )
    assert "responseDeserialize: deserializeStable" in named_content


def test_js_grpc_imported_evolving_default(tmp_path: Path):
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            option enable_auto_type_id = false;
            option evolving = false;
            package demo.common;

            message Stable {
                string name = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            service Api {
                rpc Call (demo.common.Stable) returns (demo.common.Stable);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=tmp_path, grpc=True)
    )
    content = generator.generate_services()[0].content

    assert (
        'import { Stable, deserializeStable, serializeStable } from "./common";'
        in content
    )
    assert "requestSerialize: serializeStableGrpc" in content
    assert "requestDeserialize: deserializeStable" in content
    assert "getForyState" not in content
    assert "FORY_STATE" not in content
    assert "getRootCodec" not in content


def test_js_grpc_nested_roots():
    schema = parse_fdl(
        dedent(
            """
            option enable_auto_type_id = false;
            package demo.nested;

            message Envelope {
                message Request {
                    string name = 1;
                }

                union Result {
                    string ok = 1;
                }
            }

            service Nested {
                rpc Call (Envelope.Request) returns (Envelope.Result);
            }
            """
        )
    )
    files = generate_service_files(schema, JavaScriptGenerator)
    content = files["demo_nested_grpc.ts"]

    assert (
        "import { Envelope, deserializeEnvelopeRequest, deserializeEnvelopeResult, "
        'serializeEnvelopeRequest, serializeEnvelopeResult } from "./demo_nested";'
        in content
    )
    assert "import { Envelope.Request" not in content
    assert "requestSerialize: serializeEnvelopeRequestGrpc" in content
    assert "responseDeserialize: deserializeEnvelopeResult" in content
    assert "getForyState" not in content
    assert "getRootCodec" not in content


def test_js_grpc_imported_nested_roots(tmp_path: Path):
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            option enable_auto_type_id = false;
            package demo.common;

            message Envelope {
                message Request {
                    string name = 1;
                }

                union Result {
                    string ok = 1;
                }
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            service Nested {
                rpc Call (demo.common.Envelope.Request) returns (demo.common.Envelope.Result);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=tmp_path, grpc=True)
    )
    content = generator.generate_services()[0].content

    assert (
        "import { Envelope, deserializeEnvelopeRequest, deserializeEnvelopeResult, "
        'serializeEnvelopeRequest, serializeEnvelopeResult } from "./common";'
        in content
    )
    assert "Envelope.Request" in content
    assert "Envelope.Result" in content
    assert "requestSerialize: serializeEnvelopeRequestGrpc" in content
    assert "responseDeserialize: deserializeEnvelopeResult" in content
    assert "getForyState" not in content
    assert "Envelope.ResultCase" not in content


def test_js_grpc_imported_default_package(tmp_path: Path):
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            message Request {
                string name = 1;
            }

            message Reply {
                string text = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            service Api {
                rpc Call (Request) returns (Reply);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=tmp_path, grpc=True)
    )
    content = generator.generate_services()[0].content

    assert (
        "import { Reply, Request, deserializeReply, deserializeRequest, "
        'serializeReply, serializeRequest } from "./common";' in content
    )
    assert "requestSerialize: serializeRequestGrpc" in content
    assert "responseDeserialize: deserializeReply" in content
    assert "FORY_STATE" not in content
    assert "getRootCodec" not in content


def test_js_grpc_web_codegen():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
    )
    files = {item.path: item.content for item in generator.generate_services()}
    assert set(files) == {"demo_greeter_grpc_web.ts"}
    content = files["demo_greeter_grpc_web.ts"]
    assert 'import * as grpcWeb from "grpc-web";' in content
    assert (
        "import { HelloReply, HelloRequest, deserializeHelloReply, "
        'deserializeHelloRequest, serializeHelloReply, serializeHelloRequest } from "./demo_greeter";'
        in content
    )
    assert "export class GreeterWebClient" in content
    assert "export class GreeterWebPromiseClient" in content
    assert "type GrpcWebMessageType<T> = new (...args: unknown[]) => T;" in content
    assert "new grpcWeb.MethodDescriptor<" in content
    assert (
        "const root0GrpcWebType = Object as unknown as GrpcWebMessageType<HelloRequest>;"
        in content
    )
    assert "  serializeHelloRequest,\n  deserializeHelloReply," in content
    assert "this.client.thenableCall(" in content
    assert "PromiseCallOptions" not in content
    assert "unaryCall" not in content
    assert "GrpcWebUnaryClientBase" not in content
    assert "grpcWeb.MethodType.UNARY" in content
    assert "ForyGrpcWebWireFormat" in content
    assert 'this.wireFormat = options?.wireFormat ?? "grpcweb";' in content
    assert "credentials?: null" not in content
    assert "void credentials" not in content
    assert "options?.credentials" not in content
    assert "options?.fory" not in content
    assert "new Fory" not in content
    assert "WeakMap<Fory" not in content
    assert "getForyState" not in content
    assert "FORY_STATE" not in content
    assert "Type.struct" not in content
    assert "Type.union" not in content


def test_js_grpc_web_stream_text():
    schema = parse_fdl(
        dedent(
            """
            package demo.greeter;

            message HelloRequest {}
            message HelloReply {}

            service Greeter {
                rpc LotsOfReplies (HelloRequest) returns (stream HelloReply);
            }
            """
        )
    )
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
    )
    content = next(iter(item.content for item in generator.generate_services()))
    assert 'this.wireFormat = options?.wireFormat ?? "grpcwebtext";' in content
    assert "grpcweb binary mode does not support server streaming" in content
    assert "grpcWeb.MethodType.SERVER_STREAMING" in content
    assert "WebPromiseClient" not in content
    assert "createGreeterWebPromiseClient" not in content


def test_js_grpc_web_rejects_client():
    schema = parse_fdl(
        dedent(
            """
            package demo.streams;

            message Req {}
            message Res {}

            service Streamer {
                rpc Client (stream Req) returns (Res);
            }
            """
        )
    )
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
    )
    with pytest.raises(ValueError, match="gRPC-Web does not support"):
        generator.generate_services()


def test_js_grpc_exports_are_per_target():
    schema = parse_fdl(
        dedent(
            """
            package demo.exports;

            message Req {}
            message Res {}

            service Foo {
                rpc Call (Req) returns (Res);
            }

            service FooWeb {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )

    node = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    assert {item.path for item in node.generate_services()} == {"demo_exports_grpc.ts"}

    web = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
    )
    assert {item.path for item in web.generate_services()} == {
        "demo_exports_grpc_web.ts"
    }

    both = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True, grpc_web=True)
    )
    assert {item.path for item in both.generate_services()} == {
        "demo_exports_grpc.ts",
        "demo_exports_grpc_web.ts",
    }


def test_js_grpc_model_export_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            message GreeterClient {}
            message Reply {}

            service Greeter {
                rpc Call (GreeterClient) returns (Reply);
            }
            """
        )
    )
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )

    with pytest.raises(ValueError, match="model import collides"):
        generator.generate_services()


def test_js_grpc_web_helper_name_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.webcollision;

            message ForyGrpcWebWireFormat {}
            message Reply {}

            service Api {
                rpc Call (ForyGrpcWebWireFormat) returns (Reply);
            }
            """
        )
    )
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
    )

    with pytest.raises(ValueError, match="model import collides"):
        generator.generate_services()


def test_js_grpc_root_helper_name_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.roots;

            message Foo {
                message Bar {}
            }
            message FooGrpc {}
            message FooBar {}

            service Api {
                rpc One (Foo) returns (FooGrpc);
                rpc Two (FooGrpc) returns (Foo);
                rpc Three (Foo.Bar) returns (FooBar);
            }
            """
        )
    )
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )

    with pytest.raises(ValueError, match="registration field collision"):
        generator.generate()


def test_js_grpc_web_descriptor_ids():
    schema = parse_fdl(
        dedent(
            """
            package demo.webnames;

            message Req {}
            message Res {}

            service Foo {
                rpc BarBaz (Req) returns (Res);
            }

            service FooBar {
                rpc Baz (Req) returns (Res);
            }
            """
        )
    )
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
    )
    content = generator.generate_services()[0].content

    assert "const methodDescriptor0_0 =" in content
    assert "const methodDescriptor1_0 =" in content
    assert "methodDescriptorFooBarBaz" not in content
    assert "      methodDescriptor0_0," in content
    assert "      methodDescriptor1_0," in content


def test_js_grpc_web_format_per_service():
    schema = parse_fdl(
        dedent(
            """
            package demo.webformat;

            message Req {}
            message Res {}

            service UnaryOnly {
                rpc Call (Req) returns (Res);
            }

            service StreamOnly {
                rpc Watch (Req) returns (stream Res);
            }
            """
        )
    )
    generator = JavaScriptGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
    )
    content = generator.generate_services()[0].content
    unary_start = content.index("export class UnaryOnlyWebClient")
    stream_start = content.index("export class StreamOnlyWebClient")
    unary_block = content[unary_start:stream_start]
    stream_block = content[stream_start:]

    assert 'this.wireFormat = options?.wireFormat ?? "grpcweb";' in unary_block
    assert "grpcweb binary mode does not support server streaming" not in unary_block
    assert 'this.wireFormat = options?.wireFormat ?? "grpcwebtext";' in stream_block
    assert "grpcweb binary mode does not support server streaming" in stream_block


def test_js_grpc_reserved_methods():
    schema = parse_fdl(
        dedent(
            """
            package demo.keywords;

            message Req {}
            message Res {}

            service Factory {
                rpc constructor (Req) returns (Res);
                rpc makeUnaryRequest (Req) returns (Res);
                rpc client (Req) returns (Res);
                rpc then (Req) returns (Res);
                rpc wireFormat (Req) returns (Res);
            }
            """
        )
    )

    node = next(
        iter(
            JavaScriptGenerator(
                schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
            ).generate_services()
        )
    ).content
    assert 'constructorPath: "/demo.keywords.Factory/constructor"' in node
    assert "  constructor_(request: Req" in node
    assert "  constructor(request: Req" not in node
    assert "  makeUnaryRequest_(request: Req" in node
    assert "  makeUnaryRequest(request: Req" not in node
    assert "  then_(request: Req" in node
    assert "  then(request: Req" not in node

    web = next(
        iter(
            JavaScriptGenerator(
                schema, GeneratorOptions(output_dir=Path("/tmp"), grpc_web=True)
            ).generate_services()
        )
    ).content
    assert 'constructorPath: "/demo.keywords.Factory/constructor"' in web
    assert 'thenPath: "/demo.keywords.Factory/then"' in web
    assert "  constructor_(" in web
    assert "  constructor(request: Req" not in web
    assert "  client_(" in web
    assert "  client(" not in web
    assert "  then_(" in web
    assert "  then(" not in web
    assert "  wireFormat_(" in web
    assert "  wireFormat(" not in web


def test_kotlin_grpc_fory_marshaller():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, KotlinGenerator)
    assert set(files) == {"demo/greeter/GreeterGrpcKt.kt"}
    content = files["demo/greeter/GreeterGrpcKt.kt"]
    assert "public object GreeterGrpcKt" in content
    assert 'SERVICE_NAME: String = "demo.greeter.Greeter"' in content
    assert "private val FORY: org.apache.fory.ThreadSafeFory" in content
    assert "GreeterForyModule.getFory()" in content
    assert (
        "private val serviceDescriptorValue: io.grpc.ServiceDescriptor by lazy"
        in content
    )
    assert "public val serviceDescriptor: io.grpc.ServiceDescriptor" in content
    assert (
        "private val sayHelloMethodValue: io.grpc.MethodDescriptor<HelloRequest, HelloReply> by lazy"
        in content
    )
    assert (
        "public val sayHelloMethod: io.grpc.MethodDescriptor<HelloRequest, HelloReply>"
        in content
    )
    assert "public abstract class GreeterCoroutineImplBase" in content
    assert "io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition" in content
    assert "public class GreeterCoroutineStub @JvmOverloads constructor" in content
    assert "io.grpc.kotlin.ClientCalls.unaryRpc" in content
    assert "implements" not in content
    assert "private class ForyMarshaller<T : Any>(" in content
    assert "fory.serialize(value)" in content
    assert "fory.deserialize(readBytes(stream), type)" in content
    assert "stream is io.grpc.KnownLength" in content
    assert "readUnknownLengthBytes(stream)" in content
    assert "ProtoUtils" not in content
    assert "MessageLite" not in content


def test_csharp_grpc_fory_marshaller():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, CSharpGenerator)
    assert set(files) == {"demo/greeter/GreeterGrpc.cs"}
    content = files["demo/greeter/GreeterGrpc.cs"]
    assert "public static partial class Greeter" in content
    assert 'static readonly string __ServiceName = "demo.greeter.Greeter"' in content
    assert (
        "private static readonly global::Apache.Fory.ThreadSafeFory __Fory" in content
    )
    assert "demoGreeterForyModule" not in content
    assert "grpc::Marshallers.Create(__Serialize_" in content
    assert "context.Complete(__Fory.Serialize<" in content
    assert "PayloadAsReadOnlySequence()" in content
    assert "PayloadAsNewBuffer" not in content
    assert "new grpc::Method<global::demo.greeter.HelloRequest" in content
    assert "grpc::MethodType.Unary" in content
    assert '[grpc::BindServiceMethod(typeof(Greeter), "BindService")]' in content
    assert (
        "public partial class GreeterClient : grpc::ClientBase<GreeterClient>"
        in content
    )
    assert "protected override GreeterClient NewInstance" in content
    assert "public static grpc::ServerServiceDefinition BindService" in content
    assert (
        "public static void BindService(grpc::ServiceBinderBase serviceBinder"
        in content
    )
    assert "serviceImpl == null" in content
    assert "new grpc::UnaryServerMethod" in content
    assert "MethodImplOptions.NoInlining" in content
    assert "ProtoUtils" not in content
    hot_path = content.split("__ThrowSerializeError", 1)[0]
    assert '$"' not in hot_path
    assert "System.Reflection" not in content
    assert "ValueTuple" not in content
    assert "Tuple<" not in content
    assert "Activator" not in content


def test_scala_grpc_marshaller():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, ScalaGenerator)
    assert set(files) == {"demo/greeter/GreeterGrpc.scala"}
    content = files["demo/greeter/GreeterGrpc.scala"]
    assert "object GreeterGrpc" in content
    assert 'SERVICE_NAME: String = "demo.greeter.Greeter"' in content
    assert "private val FORY: org.apache.fory.ThreadSafeFory" in content
    assert "GreeterForyModule.getFory" in content
    assert "import org.apache.fory.scala.rpc.{RpcFuture, RpcIterator}" in content
    assert (
        "private lazy val sayHelloMethodValue: io.grpc.MethodDescriptor[HelloRequest, HelloReply]"
        in content
    )
    assert "def sayHello(request: HelloRequest): RpcFuture[HelloReply]" in content
    assert "def sayHelloBlocking(request: HelloRequest): HelloReply" in content
    assert (
        "def sayHelloFuture(\n            request: HelloRequest): com.google.common.util.concurrent.ListenableFuture[HelloReply]"
        in content
    )
    assert "protected override def build(" in content
    assert "private final class ForyMarshaller[T <: AnyRef]" in content
    assert "fory.serialize(value)" in content
    assert "fory.deserialize(readBytes(stream), typ)" in content
    assert "with io.grpc.KnownLength" in content
    assert "ProtoUtils" not in content
    assert "MessageLite" not in content
    assert "ScalaPB" not in content
    assert "org.apache.fory.scala.grpc" not in content


def test_grpc_streaming_method_shapes():
    schema = parse_fdl(
        dedent(
            """
            package demo.streams;

            message Req {}
            message Res {}
            union Payload { Req req = 1; Res res = 2; }

            service Streamer {
                rpc Unary (Req) returns (Res);
                rpc Server (Req) returns (stream Res);
                rpc Client (stream Req) returns (Res);
                rpc Bidi (stream Payload) returns (stream Payload);
            }
            """
        )
    )

    java = next(iter(generate_service_files(schema, JavaGenerator).values()))
    assert "MethodType.UNARY" in java
    assert "MethodType.SERVER_STREAMING" in java
    assert "MethodType.CLIENT_STREAMING" in java
    assert "MethodType.BIDI_STREAMING" in java
    assert "asyncServerStreamingCall" in java
    assert "asyncClientStreamingCall" in java
    assert "asyncBidiStreamingCall" in java
    assert "FutureStub" in java
    assert "futureUnaryCall" in java
    assert "blockingUnaryCall" in java
    assert "blockingServerStreamingCall" in java
    assert "io.grpc.MethodDescriptor<Payload, Payload>" in java

    python = next(iter(generate_service_files(schema, PythonGenerator).values()))
    assert "import grpc.aio" in python
    assert "channel.unary_unary(" in python
    assert "channel.unary_stream(" in python
    assert "channel.stream_unary(" in python
    assert "channel.stream_stream(" in python
    assert "grpc.unary_unary_rpc_method_handler(" in python
    assert "grpc.unary_stream_rpc_method_handler(" in python
    assert "grpc.stream_unary_rpc_method_handler(" in python
    assert "grpc.stream_stream_rpc_method_handler(" in python
    assert "self.unary = channel.unary_unary(" in python
    assert "self.server = channel.unary_stream(" in python
    assert "self.client = channel.stream_unary(" in python
    assert "self.bidi = channel.stream_stream(" in python
    assert "async def unary(self, request, context):" in python
    assert "async def server(self, request, context):" in python
    assert "async def client(self, request_iterator, context):" in python
    assert "async def bidi(self, request_iterator, context):" in python
    assert (
        python.count(
            'await context.abort(grpc.StatusCode.UNIMPLEMENTED, "Method not implemented!")'
        )
        == 4
    )
    assert "yield" not in python
    assert "raise NotImplementedError" not in python

    go = next(iter(generate_service_files(schema, GoGenerator).values()))
    assert "ClientStreams:\ttrue" in go
    assert "ServerStreams:\ttrue" in go
    assert "grpc.ClientStream" in go
    assert "grpc.ServerStream" in go

    kotlin = next(iter(generate_service_files(schema, KotlinGenerator).values()))
    assert "io.grpc.MethodDescriptor.MethodType.UNARY" in kotlin
    assert "io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING" in kotlin
    assert "io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING" in kotlin
    assert "io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING" in kotlin
    assert "public suspend fun unary(" in kotlin
    assert "public fun server(" in kotlin
    assert "public suspend fun client(" in kotlin
    assert "public fun bidi(" in kotlin
    assert "kotlinx.coroutines.flow.Flow<Payload>" in kotlin
    assert "io.grpc.kotlin.ClientCalls.serverStreamingRpc" in kotlin
    assert "io.grpc.kotlin.ClientCalls.clientStreamingRpc" in kotlin
    assert "io.grpc.kotlin.ClientCalls.bidiStreamingRpc" in kotlin
    assert "io.grpc.kotlin.ServerCalls.serverStreamingServerMethodDefinition" in kotlin
    assert "io.grpc.kotlin.ServerCalls.clientStreamingServerMethodDefinition" in kotlin
    assert "io.grpc.kotlin.ServerCalls.bidiStreamingServerMethodDefinition" in kotlin

    csharp = next(iter(generate_service_files(schema, CSharpGenerator).values()))
    assert "grpc::MethodType.Unary" in csharp
    assert "grpc::MethodType.ServerStreaming" in csharp
    assert "grpc::MethodType.ClientStreaming" in csharp
    assert "grpc::MethodType.DuplexStreaming" in csharp
    assert "public virtual global::demo.streams.Res Unary(" in csharp
    assert (
        "public virtual grpc::AsyncUnaryCall<global::demo.streams.Res> UnaryAsync"
        in csharp
    )
    assert (
        "public virtual grpc::AsyncServerStreamingCall<global::demo.streams.Res> Server"
        in csharp
    )
    assert (
        "public virtual grpc::AsyncClientStreamingCall<global::demo.streams.Req, global::demo.streams.Res> Client"
        in csharp
    )
    assert (
        "public virtual grpc::AsyncDuplexStreamingCall<global::demo.streams.Payload, global::demo.streams.Payload> Bidi"
        in csharp
    )
    assert "new grpc::ServerStreamingServerMethod" in csharp
    assert "new grpc::ClientStreamingServerMethod" in csharp
    assert "new grpc::DuplexStreamingServerMethod" in csharp

    scala = next(iter(generate_service_files(schema, ScalaGenerator).values()))
    assert "io.grpc.MethodDescriptor.MethodType.UNARY" in scala
    assert "io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING" in scala
    assert "io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING" in scala
    assert "io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING" in scala
    assert "def unary(request: Req): RpcFuture[Res]" in scala
    assert "def server(request: Req): RpcIterator[Res]" in scala
    assert (
        "def client(\n            responseObserver: io.grpc.stub.StreamObserver[Res]\n        ): io.grpc.stub.StreamObserver[Req]"
        in scala
    )
    assert (
        "def bidi(\n            responseObserver: io.grpc.stub.StreamObserver[Payload]\n        ): io.grpc.stub.StreamObserver[Payload]"
        in scala
    )
    assert "io.grpc.stub.ClientCalls.asyncServerStreamingCall" in scala
    assert "io.grpc.stub.ClientCalls.asyncClientStreamingCall" in scala
    assert "io.grpc.stub.ClientCalls.asyncBidiStreamingCall" in scala
    assert "new RpcIteratorAdapter(" in scala
    assert "call.request(1)" in scala
    assert "call.sendMessage(request)" in scala
    assert "call.halfClose()" in scala


def test_go_grpc_service_codegen():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, GoGenerator)
    assert len(files) == 1
    content = next(iter(files.values()))
    assert "func NewGreeterClient(cc grpc.ClientConnInterface) GreeterClient" in content
    assert "func RegisterGreeterServer(" in content
    assert "type GreeterClient interface" in content
    assert "type GreeterServer interface" in content
    assert "type UnimplementedGreeterServer struct" in content
    assert "CodecV2{}" in content
    assert "type CodecV2 struct{}" in content
    assert "func (c CodecV2) Marshal(v any) (mem.BufferSlice, error)" in content
    assert "func (c CodecV2) Unmarshal(data mem.BufferSlice, v any) error" in content
    assert "getFory().Serialize(v)" in content
    assert "getFory().Deserialize(b, v)" in content
    assert "*fory.Fory" not in content
    assert "c.fory" not in content
    assert '"/demo.greeter.Greeter/SayHello"' in content
    assert "mustEmbedUnimplementedGreeterServer()" in content


def test_go_grpc_uses_idl_names():
    schema = parse_fdl(
        dedent(
            """
            package demo.routes;

            message Req {}
            message Res {}

            service Router {
                rpc sayHello (Req) returns (Res);
                rpc stream_replies (Req) returns (stream Res);
                rpc clientTalk (stream Req) returns (Res);
                rpc bidi_chat (stream Req) returns (stream Res);
            }
            """
        )
    )

    content = next(iter(generate_service_files(schema, GoGenerator).values()))
    assert "SayHello(ctx context.Context" in content
    assert "StreamReplies(ctx context.Context" in content
    assert "ClientTalk(ctx context.Context" in content
    assert "BidiChat(ctx context.Context" in content
    assert '"/demo.routes.Router/sayHello"' in content
    assert '"/demo.routes.Router/stream_replies"' in content
    assert '"/demo.routes.Router/clientTalk"' in content
    assert '"/demo.routes.Router/bidi_chat"' in content
    assert 'MethodName:\t"sayHello"' in content
    assert 'StreamName:\t"stream_replies"' in content
    assert 'StreamName:\t"clientTalk"' in content
    assert 'StreamName:\t"bidi_chat"' in content
    assert 'MethodName:\t"SayHello"' not in content
    assert 'StreamName:\t"StreamReplies"' not in content


def test_java_outer_service_types():
    schema = parse_fdl(
        dedent(
            """
            package demo.outer;
            option java_outer_classname = "OuterModels";

            message Req {}
            message Res {}

            service OuterService {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )

    files = generate_service_files(schema, JavaGenerator)
    content = files["demo/outer/OuterServiceGrpc.java"]
    assert "io.grpc.MethodDescriptor<OuterModels.Req, OuterModels.Res>" in content
    assert "marshaller(OuterModels.Req.class)" in content
    assert "marshaller(OuterModels.Res.class)" in content


def test_grpc_imported_java_types(tmp_path: Path):
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package common;
            option java_package = "com.example.common";
            option java_outer_classname = "CommonModels";

            message Shared {}

            service ApiService {
                rpc ImportedCall (Shared) returns (Shared);
            }
            """
        )
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        dedent(
            """
            package api;
            option java_package = "com.example.api";

            import "common.fdl";

            message Local {}

            service ApiService {
                rpc Get (Shared) returns (Local);
            }
            """
        )
    )

    schema = resolve_imports(main, [tmp_path])
    validator = SchemaValidator(schema)
    assert validator.validate(), [issue.message for issue in validator.errors]
    assert [service.name for service in schema.services] == ["ApiService"]

    java_files = generate_service_files(schema, JavaGenerator)
    assert set(java_files) == {"com/example/api/ApiServiceGrpc.java"}
    java = java_files["com/example/api/ApiServiceGrpc.java"]
    assert (
        "io.grpc.MethodDescriptor<com.example.common.CommonModels.Shared, Local>"
        in java
    )
    assert "marshaller(com.example.common.CommonModels.Shared.class)" in java

    python_files = generate_service_files(schema, PythonGenerator)
    assert set(python_files) == {"api_grpc.py"}
    python = python_files["api_grpc.py"]
    assert "class ApiServiceStub" in python
    assert "ImportedCall" not in python

    kotlin_files = generate_service_files(schema, KotlinGenerator)
    assert set(kotlin_files) == {"api/ApiServiceGrpcKt.kt"}
    kotlin = kotlin_files["api/ApiServiceGrpcKt.kt"]
    assert "io.grpc.MethodDescriptor<common.Shared, Local>" in kotlin
    assert "marshaller(common.Shared::class.java)" in kotlin
    assert "public open suspend fun get(request: common.Shared): Local" in kotlin

    csharp_files = generate_service_files(schema, CSharpGenerator)
    assert set(csharp_files) == {"api/ApiServiceGrpc.cs"}
    csharp = csharp_files["api/ApiServiceGrpc.cs"]
    assert (
        "grpc::Method<global::common.Shared, global::api.Local> __Method_Get" in csharp
    )
    assert "grpc::Marshaller<global::common.Shared>" in csharp
    assert (
        "public virtual global::System.Threading.Tasks.Task<global::api.Local> Get("
        in csharp
    )

    scala_files = generate_service_files(schema, ScalaGenerator)
    assert set(scala_files) == {"api/ApiServiceGrpc.scala"}
    scala = scala_files["api/ApiServiceGrpc.scala"]
    assert "io.grpc.MethodDescriptor[common.Shared, Local]" in scala
    assert "marshaller(classOf[common.Shared])" in scala
    assert "def get(request: common.Shared): RpcFuture[Local]" in scala


def test_proto_grpc_imported_types(tmp_path: Path):
    common = tmp_path / "common.proto"
    common.write_text(
        dedent(
            """
            syntax = "proto3";
            package common;
            option java_package = "com.example.common";
            option java_outer_classname = "CommonModels";

            message Shared {}
            """
        )
    )
    main = tmp_path / "main.proto"
    main.write_text(
        dedent(
            """
            syntax = "proto3";
            package api;
            option java_package = "com.example.api";

            import "common.proto";

            message Local {}

            service ApiService {
                rpc Get (common.Shared) returns (.api.Local);
            }
            """
        )
    )

    schema = resolve_imports(main, [tmp_path])
    validator = SchemaValidator(schema)
    assert validator.validate(), [issue.message for issue in validator.errors]

    java_files = generate_service_files(schema, JavaGenerator)
    java = java_files["com/example/api/ApiServiceGrpc.java"]
    assert (
        "io.grpc.MethodDescriptor<com.example.common.CommonModels.Shared, Local>"
        in java
    )
    assert "marshaller(com.example.common.CommonModels.Shared.class)" in java

    rust_files = generate_service_files(schema, RustGenerator)
    rust_service = rust_files["api_service.rs"]
    assert "request: ::tonic::Request<crate::common::Shared>," in rust_service
    assert "::tonic::Response<crate::api::Local>" in rust_service
    assert "crate::common::common::Shared" not in rust_service
    assert "crate::api::api::Local" not in rust_service

    kotlin_files = generate_service_files(schema, KotlinGenerator)
    kotlin = kotlin_files["api/ApiServiceGrpcKt.kt"]
    assert "io.grpc.MethodDescriptor<common.Shared, Local>" in kotlin
    assert "marshaller(common.Shared::class.java)" in kotlin

    csharp_files = generate_service_files(schema, CSharpGenerator)
    csharp = csharp_files["api/ApiServiceGrpc.cs"]
    assert (
        "grpc::Method<global::common.Shared, global::api.Local> __Method_Get" in csharp
    )
    assert "grpc::Marshaller<global::common.Shared>" in csharp

    scala_files = generate_service_files(schema, ScalaGenerator)
    scala = scala_files["api/ApiServiceGrpc.scala"]
    assert "io.grpc.MethodDescriptor[common.Shared, Local]" in scala
    assert "marshaller(classOf[common.Shared])" in scala


def test_proto_grpc_absolute_type():
    schema = parse_proto(
        dedent(
            """
            syntax = "proto3";
            package demo;

            message demo {
                message Request {}
            }
            message Request {}
            message Response {}

            service ApiService {
                rpc Get (.demo.Request) returns (.demo.Response);
            }
            """
        )
    )
    validator = SchemaValidator(schema)
    assert validator.validate(), [issue.message for issue in validator.errors]

    java_files = generate_service_files(schema, JavaGenerator)
    java = java_files["demo/ApiServiceGrpc.java"]
    assert "io.grpc.MethodDescriptor<Request, Response>" in java
    assert "io.grpc.MethodDescriptor<demo.Request, Response>" not in java

    rust_files = generate_service_files(schema, RustGenerator)
    rust_service = rust_files["demo_service.rs"]
    assert "request: ::tonic::Request<crate::demo::Request>," in rust_service
    assert "::tonic::Response<crate::demo::Response>" in rust_service
    assert "crate::demo::demo::Request" not in rust_service
    assert "crate::demo::::demo::Request" not in rust_service

    kotlin_files = generate_service_files(schema, KotlinGenerator)
    kotlin = kotlin_files["demo/ApiServiceGrpcKt.kt"]
    assert "io.grpc.MethodDescriptor<Request, Response>" in kotlin
    assert "io.grpc.MethodDescriptor<demo.Request, Response>" not in kotlin

    csharp_files = generate_service_files(schema, CSharpGenerator)
    csharp = csharp_files["demo/ApiServiceGrpc.cs"]
    assert "grpc::Method<global::demo.Request, global::demo.Response>" in csharp
    assert "global::demo.demo.Request" not in csharp

    scala_files = generate_service_files(schema, ScalaGenerator)
    scala = scala_files["demo/ApiServiceGrpc.scala"]
    assert "io.grpc.MethodDescriptor[Request, Response]" in scala
    assert "io.grpc.MethodDescriptor[demo.Request, Response]" not in scala


def test_proto_grpc_longest_package(tmp_path: Path):
    common = tmp_path / "common.proto"
    common.write_text(
        dedent(
            """
            syntax = "proto3";
            package alpha.beta;
            option java_package = "pkg.two";

            message C {}
            """
        )
    )
    main = tmp_path / "main.proto"
    main.write_text(
        dedent(
            """
            syntax = "proto3";
            package alpha;
            option java_package = "pkg.one";

            import "common.proto";

            message beta {
                message C {}
            }

            service ApiService {
                rpc Get (.alpha.beta.C) returns (.alpha.beta.C);
            }
            """
        )
    )

    schema = resolve_imports(main, [tmp_path])
    validator = SchemaValidator(schema)
    assert validator.validate(), [issue.message for issue in validator.errors]

    java_files = generate_service_files(schema, JavaGenerator)
    java = java_files["pkg/one/ApiServiceGrpc.java"]
    assert "io.grpc.MethodDescriptor<pkg.two.C, pkg.two.C>" in java
    assert "marshaller(pkg.two.C.class)" in java
    assert "io.grpc.MethodDescriptor<beta.C, beta.C>" not in java

    rust_files = generate_service_files(schema, RustGenerator)
    rust_service = rust_files["alpha_service.rs"]
    assert "request: ::tonic::Request<crate::alpha_beta::C>," in rust_service
    assert "::tonic::Response<crate::alpha_beta::C>" in rust_service
    assert "crate::alpha::beta::C" not in rust_service

    kotlin_files = generate_service_files(schema, KotlinGenerator)
    kotlin = kotlin_files["alpha/ApiServiceGrpcKt.kt"]
    assert "io.grpc.MethodDescriptor<alpha.beta.C, alpha.beta.C>" in kotlin
    assert "io.grpc.MethodDescriptor<beta.C, beta.C>" not in kotlin

    csharp_files = generate_service_files(schema, CSharpGenerator)
    csharp = csharp_files["alpha/ApiServiceGrpc.cs"]
    assert "grpc::Method<global::alpha.beta.C, global::alpha.beta.C>" in csharp
    assert "global::alpha.beta.beta.C" not in csharp

    scala_files = generate_service_files(schema, ScalaGenerator)
    scala = scala_files["alpha/ApiServiceGrpc.scala"]
    assert "io.grpc.MethodDescriptor[alpha.beta.C, alpha.beta.C]" in scala
    assert "io.grpc.MethodDescriptor[beta.C, beta.C]" not in scala


def test_java_grpc_class_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            message GreeterGrpc {}
            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )
    generator = JavaGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        generator.generate_services()
    except ValueError as e:
        assert "Java gRPC service class GreeterGrpc conflicts" in str(e)
    else:
        raise AssertionError("Expected Java gRPC service class collision")


def test_kotlin_grpc_class_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            message GreeterGrpcKt {}
            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )
    with pytest.raises(ValueError, match="Kotlin generated file path collision"):
        KotlinGenerator(schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True))


def test_csharp_grpc_class_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            message Greeter {}
            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )
    generator = CSharpGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    with pytest.raises(ValueError, match="C# gRPC service class Greeter conflicts"):
        generator.generate_services()


def test_scala_grpc_class_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            message GreeterGrpc {}
            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )
    generator = ScalaGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    with pytest.raises(ValueError, match="Scala generated file path collision"):
        generator.generate_services()


def test_scala_grpc_preflight_collision(tmp_path: Path, capsys):
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package demo.collision;

            message GreeterGrpc {}
            """
        )
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        dedent(
            """
            package demo.collision;

            import "common.fdl";

            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )

    assert validate_scala_generation([main], [tmp_path], grpc=True) is False
    err = capsys.readouterr().err
    assert "Scala generated file path collision" in err
    assert "GreeterGrpc.scala" in err


def test_java_grpc_class_import_collision(tmp_path: Path):
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package demo.collision;

            message GreeterGrpc {}
            """
        )
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        dedent(
            """
            package demo.collision;

            import "common.fdl";

            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )

    schema = resolve_imports(main, [tmp_path])
    generator = JavaGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        generator.generate_services()
    except ValueError as e:
        assert "Java gRPC service class GreeterGrpc conflicts" in str(e)
    else:
        raise AssertionError("Expected imported Java gRPC service class collision")


def test_java_grpc_import_outer_collision(tmp_path: Path):
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package demo.collision;
            option java_outer_classname = "GreeterGrpc";

            message Shared {}
            """
        )
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        dedent(
            """
            package demo.collision;

            import "common.fdl";

            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )

    schema = resolve_imports(main, [tmp_path])
    generator = JavaGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        generator.generate_services()
    except ValueError as e:
        assert "Java gRPC service class GreeterGrpc conflicts" in str(e)
    else:
        raise AssertionError("Expected imported Java outer class collision")


def test_grpc_method_name_collisions_fail():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            message Req {}
            message Res {}

            service Greeter {
                rpc Foo (Req) returns (Res);
                rpc foo (Req) returns (Res);
            }
            """
        )
    )

    java_generator = JavaGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        java_generator.generate_services()
    except ValueError as e:
        assert "Java gRPC" in str(e) and "Foo and foo" in str(e)
    else:
        raise AssertionError("Expected Java gRPC method name collision")

    python_generator = PythonGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        python_generator.generate_services()
    except ValueError as e:
        assert "Python gRPC method name collision" in str(e)
    else:
        raise AssertionError("Expected Python gRPC method name collision")

    rust_generator = RustGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        rust_generator.generate_services()
    except ValueError as e:
        assert "Rust name collision" in str(e)
    else:
        raise AssertionError("Expected Rust gRPC method name collision")

    go_generator = GoGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        go_generator.generate_services()
    except ValueError as e:
        assert "Go gRPC method name collision" in str(e)
        assert "Foo and foo" in str(e)
    else:
        raise AssertionError("Expected Go gRPC method name collision")

    kotlin_generator = KotlinGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        kotlin_generator.generate_services()
    except ValueError as e:
        assert "Kotlin gRPC method name collision" in str(e)
        assert "Foo and foo" in str(e)
    else:
        raise AssertionError("Expected Kotlin gRPC method name collision")

    csharp_generator = CSharpGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        csharp_generator.generate_services()
    except ValueError as e:
        assert "C# gRPC method name collision" in str(e)
        assert "Foo and foo" in str(e)
    else:
        raise AssertionError("Expected C# gRPC method name collision")

    scala_generator = ScalaGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        scala_generator.generate_services()
    except ValueError as e:
        assert "Scala gRPC method name collision" in str(e)
        assert "Foo and foo" in str(e)
    else:
        raise AssertionError("Expected Scala gRPC method name collision")


def test_scala_grpc_method_scopes():
    schema = parse_fdl(
        dedent(
            """
            package demo.scope;

            message Req {}
            message Res {}

            service Greeter {
                rpc Cancel (Req) returns (Res);
                rpc Close (Req) returns (stream Res);
                rpc ReadBytes (Req) returns (Res);
                rpc CompletePromise (Req) returns (Res);
            }
            """
        )
    )

    files = generate_service_files(schema, ScalaGenerator)
    content = files["demo/scope/GreeterGrpc.scala"]
    assert "def cancel(request: Req): RpcFuture[Res]" in content
    assert "def close(request: Req): RpcIterator[Res]" in content
    assert "def readBytes(request: Req): RpcFuture[Res]" in content
    assert "def completePromise(request: Req): RpcFuture[Res]" in content


def test_grpc_method_keywords_safe():
    schema = parse_fdl(
        dedent(
            """
            package demo.keywords;

            message Req {}
            message Res {}

            service Greeter {
                rpc Class (Req) returns (Res);
            }
            """
        )
    )

    java = next(iter(generate_service_files(schema, JavaGenerator).values()))
    assert "public void class_(Req request," in java
    assert "public Res class_(Req request)" in java
    assert "serviceImpl.class_((Req) request," in java

    python = next(iter(generate_service_files(schema, PythonGenerator).values()))
    assert "self.class_ = channel.unary_unary(" in python
    assert "def class_(self, request, context):" in python
    assert "servicer.class_" in python
    assert '        "Class": grpc.unary_unary_rpc_method_handler(' in python

    kotlin = next(iter(generate_service_files(schema, KotlinGenerator).values()))
    assert "public open suspend fun `class`(request: Req): Res" in kotlin
    assert "public suspend fun `class`(" in kotlin
    assert "implementation = ::`class`" in kotlin

    scala = next(iter(generate_service_files(schema, ScalaGenerator).values()))
    assert "def `class`(request: Req): RpcFuture[Res]" in scala
    assert "def `class`(request: Req, responseObserver:" in scala
    assert 'SERVICE_NAME,\n                    "Class"' in scala


def test_python_grpc_registration_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            service FooBar {}
            service FooBAR {}
            """
        )
    )

    generator = PythonGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        generator.generate_services()
    except ValueError as e:
        assert "Python gRPC service registration collision" in str(e)
    else:
        raise AssertionError("Expected Python gRPC service registration collision")


def test_rust_grpc_module_collision():
    schema = parse_fdl(
        dedent(
            """
            package demo.collision;

            service FooBar {}
            service FooBAR {}
            """
        )
    )

    generator = RustGenerator(
        schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
    )
    try:
        generator.generate_services()
    except ValueError as e:
        assert "Rust name collision" in str(e)
    else:
        raise AssertionError("Expected Rust gRPC service module collision")


def test_java_grpc_default_package():
    schema = parse_fdl(
        dedent(
            """
            message Req {}
            message Res {}

            service DefaultService {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )

    files = generate_service_files(schema, JavaGenerator)
    assert set(files) == {"DefaultServiceGrpc.java"}
    java = files["DefaultServiceGrpc.java"]
    assert "package " not in java
    assert 'SERVICE_NAME = "DefaultService"' in java
    assert "generateFullMethodName(" in java
    assert 'SERVICE_NAME, "Call"' in java


def test_proto_and_fbs_grpc_service_codegen():
    proto_schema = parse_proto(
        dedent(
            """
            syntax = "proto3";
            package demo.proto;

            message Req {}
            message Res {}

            service ProtoSvc {
                rpc Call (Req) returns (stream Res);
            }
            """
        )
    )
    proto_java = generate_service_files(proto_schema, JavaGenerator)
    proto_python = generate_service_files(proto_schema, PythonGenerator)
    proto_scala = generate_service_files(proto_schema, ScalaGenerator)
    assert "demo/proto/ProtoSvcGrpc.java" in proto_java
    assert "demo_proto_grpc.py" in proto_python
    assert "demo/proto/ProtoSvcGrpc.scala" in proto_scala
    assert "MethodType.SERVER_STREAMING" in proto_java["demo/proto/ProtoSvcGrpc.java"]
    assert "channel.unary_stream(" in proto_python["demo_proto_grpc.py"]
    assert "MethodType.SERVER_STREAMING" in proto_scala["demo/proto/ProtoSvcGrpc.scala"]
    assert "RpcIterator[Res]" in proto_scala["demo/proto/ProtoSvcGrpc.scala"]

    fbs_schema = parse_fbs(
        dedent(
            """
            namespace demo.fbs;

            table Req {}
            table Res {}

            rpc_service FbsSvc {
                Call(Req):Res;
            }
            """
        )
    )
    fbs_java = generate_service_files(fbs_schema, JavaGenerator)
    fbs_python = generate_service_files(fbs_schema, PythonGenerator)
    fbs_scala = generate_service_files(fbs_schema, ScalaGenerator)
    assert "demo/fbs/FbsSvcGrpc.java" in fbs_java
    assert "demo_fbs_grpc.py" in fbs_python
    assert "demo/fbs/FbsSvcGrpc.scala" in fbs_scala
    assert 'SERVICE_NAME = "demo.fbs.FbsSvc"' in fbs_java["demo/fbs/FbsSvcGrpc.java"]
    assert '"/demo.fbs.FbsSvc/Call"' in fbs_python["demo_fbs_grpc.py"]
    assert (
        'SERVICE_NAME: String = "demo.fbs.FbsSvc"'
        in fbs_scala["demo/fbs/FbsSvcGrpc.scala"]
    )


def test_service_schema_model_files():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        files = generate_files(schema, generator_cls)
        assert len(files) >= 1, (
            f"{generator_cls.language_name}: expected at least one generated file"
        )


def test_grpc_flag_compiles_services(tmp_path: Path, capsys):
    example_path = Path(__file__).resolve().parents[2] / "examples" / "service.fdl"
    lang_dirs = {}
    for lang in (
        "java",
        "python",
        "rust",
        "go",
        "cpp",
        "csharp",
        "swift",
        "scala",
        "kotlin",
    ):
        lang_dirs[lang] = tmp_path / lang
    ok = compile_file(example_path, lang_dirs, grpc=True, generated_outputs={})
    output = capsys.readouterr().out
    assert ok is True
    for lang, lang_dir in lang_dirs.items():
        files = [p for p in lang_dir.rglob("*") if p.is_file()]
        assert len(files) >= 1, f"{lang}: expected at least one file with grpc=True"
    assert (lang_dirs["java"] / "demo" / "greeter" / "GreeterGrpc.java").exists()
    assert (lang_dirs["python"] / "demo_greeter_grpc.py").exists()
    assert (lang_dirs["go"] / "demo_greeter_grpc.go").exists()
    assert output.count("demo_greeter_grpc.go") == 1
    assert (lang_dirs["rust"] / "demo_greeter_service.rs").exists()
    assert (lang_dirs["rust"] / "demo_greeter_service_grpc.rs").exists()
    assert (lang_dirs["csharp"] / "demo" / "greeter" / "Service.cs").exists()
    assert (lang_dirs["csharp"] / "demo" / "greeter" / "GreeterGrpc.cs").exists()
    assert (lang_dirs["scala"] / "demo" / "greeter" / "GreeterGrpc.scala").exists()
    assert (lang_dirs["kotlin"] / "demo" / "greeter" / "GreeterGrpcKt.kt").exists()


def test_compile_js_grpc_web(tmp_path: Path, capsys):
    example_path = Path(__file__).resolve().parents[2] / "examples" / "service.fdl"
    javascript_out = tmp_path / "javascript"
    ok = compile_file(
        example_path,
        {"javascript": javascript_out},
        grpc_web=True,
        generated_outputs={},
    )
    output = capsys.readouterr().out

    assert ok is True
    assert (javascript_out / "service.ts").exists()
    assert (javascript_out / "service_grpc_web.ts").exists()
    assert not (javascript_out / "service_grpc.ts").exists()
    assert output.count("service_grpc_web.ts") == 1


def test_compile_python_grpc_sync_mode(tmp_path: Path, capsys):
    schema_path = tmp_path / "service.fdl"
    schema_path.write_text(_GREETER_WITH_SERVICE)
    python_out = tmp_path / "python"
    ok = compile_file(
        schema_path,
        {"python": python_out},
        grpc=True,
        grpc_python_mode="sync",
        generated_outputs={},
    )
    output = capsys.readouterr().out

    assert ok is True
    service_file = python_out / "demo_greeter_grpc.py"
    assert service_file.exists()
    content = service_file.read_text()
    assert "import grpc.aio" not in content
    assert "def say_hello(self, request, context):" in content
    assert output.count("demo_greeter_grpc.py") == 1


def test_cli_rejects_grpc_web_non_js(tmp_path: Path, capsys):
    schema_path = tmp_path / "service.fdl"
    schema_path.write_text(_GREETER_WITH_SERVICE)
    args = parse_args(
        [
            str(schema_path),
            "--java_out",
            str(tmp_path / "java"),
            "--grpc-web",
        ]
    )

    assert cmd_compile(args) == 1
    assert (
        "--grpc-web is only supported with JavaScript output" in capsys.readouterr().err
    )


def test_cli_rejects_mode_without_grpc(tmp_path: Path, capsys):
    schema_path = tmp_path / "service.fdl"
    schema_path.write_text(_GREETER_WITH_SERVICE)
    args = parse_args(
        [
            str(schema_path),
            "--python_out",
            str(tmp_path / "python"),
            "--grpc-python-mode",
            "sync",
        ]
    )

    assert cmd_compile(args) == 1
    assert "--grpc-python-mode requires --grpc" in capsys.readouterr().err


def test_cli_rejects_mode_non_python(tmp_path: Path, capsys):
    schema_path = tmp_path / "service.fdl"
    schema_path.write_text(_GREETER_WITH_SERVICE)
    args = parse_args(
        [
            str(schema_path),
            "--java_out",
            str(tmp_path / "java"),
            "--grpc",
            "--grpc-python-mode",
            "sync",
        ]
    )

    assert cmd_compile(args) == 1
    assert (
        "--grpc-python-mode is only supported with Python output"
        in capsys.readouterr().err
    )


def test_js_output_path_collision(tmp_path: Path, capsys):
    first_dir = tmp_path / "first"
    second_dir = tmp_path / "second"
    first_dir.mkdir()
    second_dir.mkdir()
    first = first_dir / "same.fdl"
    second = second_dir / "same.fdl"
    source = dedent(
        """
        package demo.collision;

        message Value {
            string value = 1;
        }
        """
    )
    first.write_text(source)
    second.write_text(source)

    generated_outputs = {}
    javascript_out = tmp_path / "javascript"
    assert compile_file(
        first,
        {"javascript": javascript_out},
        generated_outputs=generated_outputs,
    )
    assert not compile_file(
        second,
        {"javascript": javascript_out},
        generated_outputs=generated_outputs,
    )

    assert "JavaScript output path collision" in capsys.readouterr().err


@pytest.mark.skipif(shutil.which("dotnet") is None, reason="dotnet not installed")
def test_csharp_grpc_dotnet_fixture(tmp_path: Path):
    repo_root = Path(__file__).resolve().parents[3]
    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package demo.shared;
            option csharp_namespace = "Demo.Shared";

            message SharedRequest {
                string name = 1;
            }

            message SharedReply {
                string text = 1;
            }

            union SharedChoice {
                SharedRequest request = 1;
                SharedReply reply = 2;
            }
            """
        )
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        dedent(
            """
            package demo.greeter;
            option csharp_namespace = "Demo.Greeter";

            import "common.fdl";

            message LocalRequest {
                string name = 1;
            }

            message LocalReply {
                string text = 1;
            }

            union LocalChoice {
                LocalRequest request = 1;
                LocalReply reply = 2;
            }

            service Greeter {
                rpc Unary (LocalRequest) returns (LocalReply);
                rpc Server (LocalRequest) returns (stream LocalReply);
                rpc Client (stream SharedRequest) returns (SharedReply);
                rpc Bidi (stream LocalChoice) returns (stream SharedChoice);
            }

            service Empty {}
            """
        )
    )
    out = tmp_path / "out"
    assert (
        foryc_main(
            [
                "--lang",
                "csharp",
                "--csharp_out",
                str(out),
                "--grpc",
                str(common),
                str(main),
            ]
        )
        == 0
    )

    service = out / "Demo" / "Greeter" / "GreeterGrpc.cs"
    empty_service = out / "Demo" / "Greeter" / "EmptyGrpc.cs"
    main_model = out / "Demo" / "Greeter" / "Main.cs"
    common_model = out / "Demo" / "Shared" / "Common.cs"
    assert service.is_file()
    assert empty_service.is_file()
    assert main_model.is_file()
    assert common_model.is_file()
    service_code = service.read_text()
    assert "MainForyModule.GetFory()" in service_code
    assert "global::Demo.Shared.SharedChoice" in service_code
    assert "PayloadAsNewBuffer" not in service_code
    empty_service_code = empty_service.read_text()
    assert "__ServiceName" not in empty_service_code
    assert "__Fory" not in empty_service_code
    assert "__Throw" not in empty_service_code

    project = out / "GrpcValidation.csproj"
    project.write_text(
        dedent(
            f"""
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <OutputType>Exe</OutputType>
                <TargetFramework>net8.0</TargetFramework>
                <LangVersion>12.0</LangVersion>
                <ImplicitUsings>enable</ImplicitUsings>
                <Nullable>enable</Nullable>
                <TreatWarningsAsErrors>true</TreatWarningsAsErrors>
              </PropertyGroup>
              <ItemGroup>
                <ProjectReference Include="{repo_root / "csharp/src/Fory/Fory.csproj"}" />
                <ProjectReference Include="{repo_root / "csharp/src/Fory.Generator/Fory.Generator.csproj"}"
                                  OutputItemType="Analyzer"
                                  ReferenceOutputAssembly="false" />
                <PackageReference Include="Grpc.Core.Api" Version="2.71.0" />
              </ItemGroup>
            </Project>
            """
        ).strip()
    )
    (out / "Program.cs").write_text(CSHARP_GRPC_VALIDATION_PROGRAM)

    result = subprocess.run(
        ["dotnet", "run", "--project", str(project), "-v:quiet"],
        cwd=repo_root,
        text=True,
        capture_output=True,
        timeout=180,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def test_generated_message_signatures():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    java_files = generate_files(schema, JavaGenerator)
    all_java = "\n".join(java_files.values())
    assert "class HelloRequest" in all_java
    assert "class HelloReply" in all_java
    assert "String name" in all_java
    assert "String reply" in all_java

    python_files = generate_files(schema, PythonGenerator)
    all_python = "\n".join(python_files.values())
    assert "HelloRequest" in all_python
    assert "HelloReply" in all_python


CSHARP_GRPC_VALIDATION_PROGRAM = dedent(
    r"""
    using System;
    using System.Buffers;
    using System.Collections.Generic;
    using System.Reflection;
    using System.Threading;
    using System.Threading.Tasks;
    using Apache.Fory;
    using Demo.Greeter;
    using Demo.Shared;
    using Grpc.Core;

    static class Program
    {
        static async Task Main()
        {
            TestMarshallers();
            TestClientDispatch();
            await TestServerBinding();
            TestGeneratedSerializers();
            TestAllocationBaseline();
        }

        static void TestMarshallers()
        {
            Method<LocalRequest, LocalReply> unary =
                GetMethod<LocalRequest, LocalReply>("__Method_Unary");
            Method<SharedRequest, SharedReply> client =
                GetMethod<SharedRequest, SharedReply>("__Method_Client");
            Method<LocalChoice, SharedChoice> bidi =
                GetMethod<LocalChoice, SharedChoice>("__Method_Bidi");

            LocalRequest localRequest = new() { Name = "local" };
            LocalRequest decodedLocal = RoundTrip(unary.RequestMarshaller, localRequest);
            Require(decodedLocal.Name == "local", "local request marshaller");

            LocalReply localReply = new() { Text = "reply" };
            LocalReply decodedReply = RoundTrip(unary.ResponseMarshaller, localReply);
            Require(decodedReply.Text == "reply", "local response marshaller");

            SharedRequest sharedRequest = new() { Name = "shared" };
            SharedRequest decodedShared =
                RoundTrip(client.RequestMarshaller, sharedRequest);
            Require(decodedShared.Name == "shared", "imported request marshaller");

            SharedReply sharedReply = new() { Text = "shared-reply" };
            SharedReply decodedSharedReply =
                RoundTrip(client.ResponseMarshaller, sharedReply);
            Require(
                decodedSharedReply.Text == "shared-reply",
                "imported response marshaller");

            LocalChoice localUnion =
                new LocalChoice.Request(new LocalRequest { Name = "union-local" });
            LocalChoice decodedLocalUnion =
                RoundTrip(bidi.RequestMarshaller, localUnion);
            Require(
                decodedLocalUnion is LocalChoice.Request { Value.Name: "union-local" },
                "local union marshaller");

            SharedChoice sharedUnion =
                new SharedChoice.Reply(new SharedReply { Text = "union-shared" });
            SharedChoice decodedSharedUnion =
                RoundTrip(bidi.ResponseMarshaller, sharedUnion);
            Require(
                decodedSharedUnion is SharedChoice.Reply { Value.Text: "union-shared" },
                "imported union marshaller");

            byte[] bytes = Serialize(unary.RequestMarshaller, localRequest);
            byte[] trailing = new byte[bytes.Length + 1];
            Array.Copy(bytes, trailing, bytes.Length);
            RpcException error = RequireThrows<RpcException>(() =>
                unary.RequestMarshaller.ContextualDeserializer(
                    new BytesDeserializationContext(trailing)));
            Require(error.StatusCode == StatusCode.Internal, "trailing bytes rejected");
        }

        static void TestClientDispatch()
        {
            CapturingInvoker invoker = new();
            Greeter.GreeterClient client = new(invoker);
            LocalRequest request = new() { Name = "client" };

            client.Unary(request);
            client.UnaryAsync(request);
            client.Server(request);
            client.Client();
            client.Bidi();

            Require(invoker.Calls.Count == 5, "client dispatch count");
            Require(invoker.Calls[0] == "Unary:Unary", "blocking unary dispatch");
            Require(invoker.Calls[1] == "AsyncUnary:Unary", "async unary dispatch");
            Require(
                invoker.Calls[2] == "ServerStreaming:Server",
                "server streaming dispatch");
            Require(
                invoker.Calls[3] == "ClientStreaming:Client",
                "client streaming dispatch");
            Require(
                invoker.Calls[4] == "DuplexStreaming:Bidi",
                "duplex streaming dispatch");
        }

        static async Task TestServerBinding()
        {
            CapturingBinder metadataBinder = new();
            Greeter.BindService(metadataBinder, null);
            Require(metadataBinder.Handlers.Count == 4, "metadata binding count");
            Require(
                metadataBinder.Handlers.TrueForAll(static handler => handler is null),
                "metadata binding uses null handlers");

            CapturingBinder binder = new();
            Greeter.BindService(binder, new ServiceImpl());
            Require(binder.Handlers.Count == 4, "implementation binding count");
            Require(binder.Methods[0].Name == "Unary", "unary method name");
            Require(
                binder.Methods[1].Type == MethodType.ServerStreaming,
                "server stream method type");
            Require(
                binder.Methods[2].Type == MethodType.ClientStreaming,
                "client stream method type");
            Require(
                binder.Methods[3].Type == MethodType.DuplexStreaming,
                "duplex method type");

            var unary =
                (UnaryServerMethod<LocalRequest, LocalReply>)binder.Handlers[0]!;
            LocalReply reply = await unary(
                new LocalRequest { Name = "bound" },
                TestServerCallContext.Instance);
            Require(reply.Text == "bound", "unary delegate dispatch");

            var server =
                (ServerStreamingServerMethod<LocalRequest, LocalReply>)
                    binder.Handlers[1]!;
            CapturingServerWriter<LocalReply> serverWriter = new();
            await server(
                new LocalRequest { Name = "stream" },
                serverWriter,
                TestServerCallContext.Instance);
            Require(
                serverWriter.Messages.Count == 1
                    && serverWriter.Messages[0].Text == "stream",
                "server stream dispatch");

            var client =
                (ClientStreamingServerMethod<SharedRequest, SharedReply>)
                    binder.Handlers[2]!;
            SharedReply clientReply = await client(
                new ArrayStreamReader<SharedRequest>(
                    new SharedRequest { Name = "a" },
                    new SharedRequest { Name = "b" }),
                TestServerCallContext.Instance);
            Require(clientReply.Text == "a,b", "client stream dispatch");

            var duplex =
                (DuplexStreamingServerMethod<LocalChoice, SharedChoice>)
                    binder.Handlers[3]!;
            CapturingServerWriter<SharedChoice> duplexWriter = new();
            await duplex(
                new ArrayStreamReader<LocalChoice>(
                    new LocalChoice.Request(new LocalRequest { Name = "duplex" })),
                duplexWriter,
                TestServerCallContext.Instance);
            Require(duplexWriter.Messages.Count == 1, "duplex stream dispatch");
            Require(
                duplexWriter.Messages[0]
                    is SharedChoice.Reply { Value.Text: "duplex" },
                "duplex union response");
        }

        static void TestGeneratedSerializers()
        {
            Fory fory = Fory.Builder().TrackRef(true).Build();
            MainForyModule.Install(fory);
            TypeResolver resolver = Resolver(fory);
            Require(IsGeneratedSerializer<LocalRequest>(resolver), "local message serializer");
            Require(IsGeneratedSerializer<LocalChoice>(resolver), "local union serializer");
            Require(IsGeneratedSerializer<SharedRequest>(resolver), "imported message serializer");
            Require(IsGeneratedSerializer<SharedChoice>(resolver), "imported union serializer");
        }

        static void TestAllocationBaseline()
        {
            Method<LocalRequest, LocalReply> method =
                GetMethod<LocalRequest, LocalReply>("__Method_Unary");
            LocalRequest request = new() { Name = "allocation" };
            BytesSerializationContext serialization = new();
            BytesDeserializationContext deserialization = new(Array.Empty<byte>());
            Apache.Fory.ThreadSafeFory fory = MainForyModule.GetFory();

            for (int i = 0; i < 256; i++)
            {
                DirectRoundTrip(fory, request);
                GeneratedRoundTrip(method.RequestMarshaller, request, serialization, deserialization);
            }

            GC.Collect();
            GC.WaitForPendingFinalizers();
            GC.Collect();
            long direct = Measure(2048, () => DirectRoundTrip(fory, request));
            long generated = Measure(
                2048,
                () => GeneratedRoundTrip(
                    method.RequestMarshaller,
                    request,
                    serialization,
                    deserialization));
            Require(
                generated <= direct + 32768,
                $"generated marshaller allocated {generated} bytes vs direct {direct}");
        }

        static bool IsGeneratedSerializer<T>(TypeResolver resolver)
        {
            string name = resolver.GetSerializer<T>().GetType().Name;
            return name.Contains("__ForySerializer_", StringComparison.Ordinal);
        }

        static TypeResolver Resolver(Fory fory)
        {
            FieldInfo field = typeof(Fory).GetField(
                "_typeResolver",
                BindingFlags.NonPublic | BindingFlags.Instance)
                ?? throw new InvalidOperationException("Fory resolver field not found");
            return (TypeResolver)field.GetValue(fory)!;
        }

        static void DirectRoundTrip(
            Apache.Fory.ThreadSafeFory fory,
            LocalRequest request)
        {
            byte[] bytes = fory.Serialize<LocalRequest>(in request);
            ReadOnlySequence<byte> sequence = new(bytes);
            LocalRequest decoded = fory.Deserialize<LocalRequest>(ref sequence);
            Require(decoded.Name == request.Name, "direct roundtrip");
        }

        static void GeneratedRoundTrip(
            Marshaller<LocalRequest> marshaller,
            LocalRequest request,
            BytesSerializationContext serialization,
            BytesDeserializationContext deserialization)
        {
            serialization.Reset();
            marshaller.ContextualSerializer(request, serialization);
            deserialization.Reset(serialization.Payload);
            LocalRequest decoded = marshaller.ContextualDeserializer(deserialization);
            Require(decoded.Name == request.Name, "generated roundtrip");
            Require(
                deserialization.ReadOnlySequenceCalls == 1
                    && deserialization.NewBufferCalls == 0,
                "generated deserializer used read-only sequence");
        }

        static long Measure(int iterations, Action action)
        {
            long before = GC.GetAllocatedBytesForCurrentThread();
            for (int i = 0; i < iterations; i++)
            {
                action();
            }
            return GC.GetAllocatedBytesForCurrentThread() - before;
        }

        static Method<TRequest, TResponse> GetMethod<TRequest, TResponse>(string name)
            where TRequest : class
            where TResponse : class
        {
            FieldInfo field = typeof(Greeter).GetField(
                name,
                BindingFlags.NonPublic | BindingFlags.Static)
                ?? throw new InvalidOperationException(
                    $"Missing generated method field {name}");
            return (Method<TRequest, TResponse>)field.GetValue(null)!;
        }

        static T RoundTrip<T>(Marshaller<T> marshaller, T value)
        {
            BytesDeserializationContext deserialization =
                new(Serialize(marshaller, value));
            T decoded = marshaller.ContextualDeserializer(deserialization);
            Require(
                deserialization.ReadOnlySequenceCalls == 1
                    && deserialization.NewBufferCalls == 0,
                "deserializer used read-only sequence");
            return decoded;
        }

        static byte[] Serialize<T>(Marshaller<T> marshaller, T value)
        {
            BytesSerializationContext context = new();
            marshaller.ContextualSerializer(value, context);
            return context.Payload;
        }

        static TException RequireThrows<TException>(Action action)
            where TException : Exception
        {
            try
            {
                action();
            }
            catch (TException error)
            {
                return error;
            }
            throw new InvalidOperationException($"Expected {typeof(TException).Name}");
        }

        static void Require(bool condition, string message)
        {
            if (!condition)
            {
                throw new InvalidOperationException(message);
            }
        }
    }

    sealed class BytesSerializationContext : SerializationContext
    {
        readonly ArrayBufferWriter<byte> _writer = new();

        public byte[] Payload { get; private set; } = Array.Empty<byte>();

        public void Reset()
        {
            Payload = Array.Empty<byte>();
        }

        public override void Complete(byte[] payload)
        {
            Payload = payload;
        }

        public override IBufferWriter<byte> GetBufferWriter()
        {
            return _writer;
        }

        public override void SetPayloadLength(int payloadLength)
        {
        }

        public override void Complete()
        {
            Payload = _writer.WrittenMemory.ToArray();
        }
    }

    sealed class BytesDeserializationContext(byte[] payload) : DeserializationContext
    {
        byte[] _payload = payload;

        public int NewBufferCalls { get; private set; }
        public int ReadOnlySequenceCalls { get; private set; }
        public override int PayloadLength => _payload.Length;

        public void Reset(byte[] payload)
        {
            _payload = payload;
            NewBufferCalls = 0;
            ReadOnlySequenceCalls = 0;
        }

        public override byte[] PayloadAsNewBuffer()
        {
            NewBufferCalls++;
            return (byte[])_payload.Clone();
        }

        public override ReadOnlySequence<byte> PayloadAsReadOnlySequence()
        {
            ReadOnlySequenceCalls++;
            return new ReadOnlySequence<byte>(_payload);
        }
    }

    sealed class CapturingInvoker : CallInvoker
    {
        public List<string> Calls { get; } = [];

        public override TResponse BlockingUnaryCall<TRequest, TResponse>(
            Method<TRequest, TResponse> method,
            string? host,
            CallOptions options,
            TRequest request)
        {
            Calls.Add($"Unary:{method.Name}");
            return Create<TResponse>();
        }

        public override AsyncUnaryCall<TResponse> AsyncUnaryCall<TRequest, TResponse>(
            Method<TRequest, TResponse> method,
            string? host,
            CallOptions options,
            TRequest request)
        {
            Calls.Add($"AsyncUnary:{method.Name}");
            return new AsyncUnaryCall<TResponse>(
                Task.FromResult(Create<TResponse>()),
                Task.FromResult(new Metadata()),
                static () => Status.DefaultSuccess,
                static () => new Metadata(),
                static () => { });
        }

        public override AsyncServerStreamingCall<TResponse>
            AsyncServerStreamingCall<TRequest, TResponse>(
                Method<TRequest, TResponse> method,
                string? host,
                CallOptions options,
                TRequest request)
        {
            Calls.Add($"ServerStreaming:{method.Name}");
            return new AsyncServerStreamingCall<TResponse>(
                new ArrayStreamReader<TResponse>(),
                Task.FromResult(new Metadata()),
                static () => Status.DefaultSuccess,
                static () => new Metadata(),
                static () => { });
        }

        public override AsyncClientStreamingCall<TRequest, TResponse>
            AsyncClientStreamingCall<TRequest, TResponse>(
                Method<TRequest, TResponse> method,
                string? host,
                CallOptions options)
        {
            Calls.Add($"ClientStreaming:{method.Name}");
            return new AsyncClientStreamingCall<TRequest, TResponse>(
                new ClientStreamWriter<TRequest>(),
                Task.FromResult(Create<TResponse>()),
                Task.FromResult(new Metadata()),
                static () => Status.DefaultSuccess,
                static () => new Metadata(),
                static () => { });
        }

        public override AsyncDuplexStreamingCall<TRequest, TResponse>
            AsyncDuplexStreamingCall<TRequest, TResponse>(
                Method<TRequest, TResponse> method,
                string? host,
                CallOptions options)
        {
            Calls.Add($"DuplexStreaming:{method.Name}");
            return new AsyncDuplexStreamingCall<TRequest, TResponse>(
                new ClientStreamWriter<TRequest>(),
                new ArrayStreamReader<TResponse>(),
                Task.FromResult(new Metadata()),
                static () => Status.DefaultSuccess,
                static () => new Metadata(),
                static () => { });
        }

        static T Create<T>()
        {
            return Activator.CreateInstance<T>();
        }
    }

    sealed class CapturingBinder : ServiceBinderBase
    {
        public List<IMethod> Methods { get; } = [];
        public List<Delegate?> Handlers { get; } = [];

        public override void AddMethod<TRequest, TResponse>(
            Method<TRequest, TResponse> method,
            UnaryServerMethod<TRequest, TResponse>? handler)
        {
            Methods.Add(method);
            Handlers.Add(handler);
        }

        public override void AddMethod<TRequest, TResponse>(
            Method<TRequest, TResponse> method,
            ClientStreamingServerMethod<TRequest, TResponse>? handler)
        {
            Methods.Add(method);
            Handlers.Add(handler);
        }

        public override void AddMethod<TRequest, TResponse>(
            Method<TRequest, TResponse> method,
            ServerStreamingServerMethod<TRequest, TResponse>? handler)
        {
            Methods.Add(method);
            Handlers.Add(handler);
        }

        public override void AddMethod<TRequest, TResponse>(
            Method<TRequest, TResponse> method,
            DuplexStreamingServerMethod<TRequest, TResponse>? handler)
        {
            Methods.Add(method);
            Handlers.Add(handler);
        }
    }

    sealed class ServiceImpl : Greeter.GreeterBase
    {
        public override Task<LocalReply> Unary(
            LocalRequest request,
            ServerCallContext context)
        {
            return Task.FromResult(new LocalReply { Text = request.Name });
        }

        public override async Task Server(
            LocalRequest request,
            IServerStreamWriter<LocalReply> responseStream,
            ServerCallContext context)
        {
            await responseStream.WriteAsync(new LocalReply { Text = request.Name });
        }

        public override async Task<SharedReply> Client(
            IAsyncStreamReader<SharedRequest> requestStream,
            ServerCallContext context)
        {
            List<string> names = [];
            while (await requestStream.MoveNext(CancellationToken.None))
            {
                names.Add(requestStream.Current.Name);
            }
            return new SharedReply { Text = string.Join(",", names) };
        }

        public override async Task Bidi(
            IAsyncStreamReader<LocalChoice> requestStream,
            IServerStreamWriter<SharedChoice> responseStream,
            ServerCallContext context)
        {
            while (await requestStream.MoveNext(CancellationToken.None))
            {
                string name = requestStream.Current switch
                {
                    LocalChoice.Request request => request.Value.Name,
                    LocalChoice.Reply reply => reply.Value.Text,
                    _ => "unknown",
                };
                await responseStream.WriteAsync(
                    new SharedChoice.Reply(new SharedReply { Text = name }));
            }
        }
    }

    sealed class ArrayStreamReader<T>(params T[] items) : IAsyncStreamReader<T>
    {
        int _index = -1;

        public T Current { get; private set; } = default!;

        public Task<bool> MoveNext(CancellationToken cancellationToken)
        {
            _index++;
            if (_index >= items.Length)
            {
                return Task.FromResult(false);
            }
            Current = items[_index];
            return Task.FromResult(true);
        }
    }

    sealed class CapturingServerWriter<T> : IServerStreamWriter<T>
    {
        public List<T> Messages { get; } = [];
        public WriteOptions? WriteOptions { get; set; }

        public Task WriteAsync(T message)
        {
            Messages.Add(message);
            return Task.CompletedTask;
        }

        public Task WriteAsync(T message, CancellationToken cancellationToken)
        {
            Messages.Add(message);
            return Task.CompletedTask;
        }
    }

    sealed class ClientStreamWriter<T> : IClientStreamWriter<T>
    {
        public WriteOptions? WriteOptions { get; set; }

        public Task CompleteAsync()
        {
            return Task.CompletedTask;
        }

        public Task WriteAsync(T message)
        {
            return Task.CompletedTask;
        }

        public Task WriteAsync(T message, CancellationToken cancellationToken)
        {
            return Task.CompletedTask;
        }
    }

    sealed class TestServerCallContext : ServerCallContext
    {
        public static readonly TestServerCallContext Instance = new();
        readonly Dictionary<object, object> _userState = [];
        Metadata _trailers = [];
        Status _status = Status.DefaultSuccess;

        protected override string MethodCore => "test";
        protected override string HostCore => "localhost";
        protected override string PeerCore => "peer";
        protected override DateTime DeadlineCore => DateTime.UtcNow.AddMinutes(1);
        protected override Metadata RequestHeadersCore => [];
        protected override CancellationToken CancellationTokenCore =>
            CancellationToken.None;
        protected override Metadata ResponseTrailersCore => _trailers;
        protected override Status StatusCore { get => _status; set => _status = value; }
        protected override WriteOptions? WriteOptionsCore { get; set; }
        protected override AuthContext AuthContextCore =>
            new("none", new Dictionary<string, List<AuthProperty>>());

        protected override ContextPropagationToken CreatePropagationTokenCore(
            ContextPropagationOptions? options)
        {
            throw new NotSupportedException();
        }

        protected override Task WriteResponseHeadersAsyncCore(
            Metadata responseHeaders)
        {
            return Task.CompletedTask;
        }

        protected override IDictionary<object, object> UserStateCore => _userState;
    }
    """
).lstrip()


def test_rust_grpc_rejects_unsafe_refs():
    cases = [
        (
            "Rust gRPC payload type Request.node uses non-thread-safe ref",
            """
            package demo.grpc;

            message Node {}

            message Request {
                ref(thread_safe=false) Node node = 1;
            }

            message Response {}

            service Api {
                rpc Call (Request) returns (Response);
            }
            """,
        ),
        (
            "Rust gRPC payload type Request.groups uses non-thread-safe list element ref",
            """
            package demo.grpc;

            message Node {}

            message Request {
                list<ref(thread_safe=false) Node> groups = 1;
            }

            message Response {}

            service Api {
                rpc Call (Request) returns (Response);
            }
            """,
        ),
        (
            "Rust gRPC payload type Request.nodes uses non-thread-safe map value ref",
            """
            package demo.grpc;

            message Node {}

            message Request {
                map<string, ref(thread_safe=false) Node> nodes = 1;
            }

            message Response {}

            service Api {
                rpc Call (Request) returns (Response);
            }
            """,
        ),
    ]

    for message, source in cases:
        schema = parse_fdl(dedent(source))
        generator = RustGenerator(
            schema, GeneratorOptions(output_dir=Path("/tmp"), grpc=True)
        )
        with pytest.raises(ValueError, match=message):
            generator.generate_services()


def test_dart_grpc_streaming_shapes():
    from fory_compiler.generators.dart import DartGenerator

    schema = parse_fdl(
        dedent(
            """
            package demo.streams;

            message Req {}
            message Res {}

            service Streamer {
                rpc UnaryMessage (Req) returns (Res);
                rpc ServerStreamMessage (Req) returns (stream Res);
                rpc ClientStreamMessage (stream Req) returns (Res);
                rpc BidiStreamMessage (stream Req) returns (stream Res);
            }
            """
        )
    )

    content = generate_service_files(schema, DartGenerator)[
        "demo/streams/demo_streams_grpc.dart"
    ]

    assert "ResponseFuture<_models.Res> unaryMessage(" in content
    assert "$createUnaryCall(_$unaryMessage, request, options: options);" in content

    assert "ResponseStream<_models.Res> serverStreamMessage(" in content
    assert (
        "    return $createStreamingCall(\n"
        "      _$serverStreamMessage,\n"
        "      Stream.value(request),\n"
        "      options: options,\n"
        "    );"
    ) in content

    assert "ResponseFuture<_models.Res> clientStreamMessage(" in content
    assert "Stream<_models.Req> request, {" in content
    assert (
        "    return $createStreamingCall(\n"
        "      _$clientStreamMessage,\n"
        "      request,\n"
        "      options: options,\n"
        "    ).single;"
    ) in content

    assert "ResponseStream<_models.Res> bidiStreamMessage(" in content
    assert (
        "$createStreamingCall(_$bidiStreamMessage, request, options: options);"
    ) in content

    assert (
        "'UnaryMessage',\n        unaryMessage_Pre,\n        false,\n        false,"
    ) in content
    assert (
        "'ServerStreamMessage',\n        serverStreamMessage_Pre,\n"
        "        false,\n        true,"
    ) in content
    assert (
        "'ClientStreamMessage',\n        clientStreamMessage_Pre,\n"
        "        true,\n        false,"
    ) in content
    assert (
        "'BidiStreamMessage',\n        bidiStreamMessage_Pre,\n"
        "        true,\n        true,"
    ) in content

    assert "Future<_models.Res> unaryMessage_Pre(" in content
    assert "Stream<_models.Res> serverStreamMessage_Pre(" in content
    assert (
        "  ) async* {\n    yield* serverStreamMessage($call, await $request);"
    ) in content
    assert "Future<_models.Res> clientStreamMessage_Pre(" in content
    assert "  ) {\n    return clientStreamMessage($call, $request);" in content
    assert "Stream<_models.Res> bidiStreamMessage_Pre(" in content

    assert (
        "Future<_models.Res> unaryMessage(\n    ServiceCall call,\n"
        "    _models.Req request,\n  );"
    ) in content
    assert (
        "Stream<_models.Res> serverStreamMessage(\n    ServiceCall call,\n"
        "    _models.Req request,\n  );"
    ) in content
    assert (
        "Future<_models.Res> clientStreamMessage(\n    ServiceCall call,\n"
        "    Stream<_models.Req> request,\n  );"
    ) in content
    assert (
        "Stream<_models.Res> bidiStreamMessage(\n    ServiceCall call,\n"
        "    Stream<_models.Req> request,\n  );"
    ) in content


def test_dart_grpc_fory_codec():
    from fory_compiler.generators.dart import DartGenerator

    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, DartGenerator)
    assert set(files) == {"demo/greeter/demo_greeter_grpc.dart"}
    content = files["demo/greeter/demo_greeter_grpc.dart"]

    assert "This file is generated by Apache Fory compiler." in content
    assert "library;" not in content

    assert "import 'dart:typed_data';" in content
    assert "import 'package:grpc/grpc.dart';" in content
    assert "import 'demo_greeter.dart' as _models;" in content

    assert (
        "_models.DemoGreeterForyModule.getFory().serialize(value, trackRef: true)"
        in content
    )
    assert "_models.DemoGreeterForyModule.getFory().deserialize<T>" in content
    assert "is Uint8List ? bytes : Uint8List.fromList(bytes)" in content

    assert "class GreeterClient extends Client {" in content
    assert "static final _$sayHello =" in content
    assert "ClientMethod<_models.HelloRequest, _models.HelloReply>(" in content
    assert "'/demo.greeter.Greeter/SayHello'," in content
    assert (
        "GreeterClient(super.channel, {super.options, super.interceptors});" in content
    )
    assert "ResponseFuture<_models.HelloReply> sayHello(" in content
    assert "_models.HelloRequest request, {" in content
    assert "$createUnaryCall(_$sayHello, request, options: options);" in content

    assert "abstract class GreeterServiceBase extends Service {" in content
    assert "String get $name => 'demo.greeter.Greeter';" in content
    assert "GreeterServiceBase() {" in content
    assert "$addMethod(" in content
    assert ("ServiceMethod<_models.HelloRequest, _models.HelloReply>(") in content
    assert "'SayHello'," in content
    assert "sayHello_Pre," in content
    assert "Future<_models.HelloReply> sayHello_Pre(" in content
    assert "Future<_models.HelloReply> sayHello(" in content
    assert "ServiceCall call," in content


def test_dart_grpc_service_class_collision():
    from fory_compiler.generators.dart import DartGenerator

    schema = parse_fdl(
        dedent(
            """
            package demo.collide;

            message GreeterClient {
                string name = 1;
            }

            message HelloRequest {}
            message HelloReply {}

            service Greeter {
                rpc SayHello (HelloRequest) returns (HelloReply);
            }
            """
        )
    )

    import pytest

    with pytest.raises(ValueError) as excinfo:
        generate_service_files(schema, DartGenerator)
    msg = str(excinfo.value)
    assert "GreeterClient" in msg
    assert "conflicts" in msg


def test_dart_grpc_method_collision():
    from fory_compiler.generators.dart import DartGenerator

    schema = parse_fdl(
        dedent(
            """
            package demo.dupes;

            message Req {}
            message Res {}

            service Greeter {
                rpc SayHello   (Req) returns (Res);
                rpc say_hello  (Req) returns (Res);
            }
            """
        )
    )

    import pytest

    with pytest.raises(ValueError) as excinfo:
        generate_service_files(schema, DartGenerator)
    msg = str(excinfo.value)
    assert "Greeter" in msg
    assert "SayHello" in msg
    assert "say_hello" in msg
    assert "sayHello" in msg


def test_dart_grpc_proto_fbs():
    from fory_compiler.generators.dart import DartGenerator

    proto_schema = parse_proto(
        dedent(
            """
            syntax = "proto3";
            package demo.proto;

            message Req {}
            message Res {}

            service ProtoSvc {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )
    proto_dart = generate_service_files(proto_schema, DartGenerator)
    assert "demo/proto/demo_proto_grpc.dart" in proto_dart
    assert (
        "'/demo.proto.ProtoSvc/Call'," in proto_dart["demo/proto/demo_proto_grpc.dart"]
    )
    assert (
        "class ProtoSvcClient extends Client {"
        in proto_dart["demo/proto/demo_proto_grpc.dart"]
    )

    fbs_schema = parse_fbs(
        dedent(
            """
            namespace demo.fbs;

            table Req {}
            table Res {}

            rpc_service FbsSvc {
                Call(Req):Res;
            }
            """
        )
    )
    fbs_dart = generate_service_files(fbs_schema, DartGenerator)
    assert "demo/fbs/demo_fbs_grpc.dart" in fbs_dart
    assert "'/demo.fbs.FbsSvc/Call'," in fbs_dart["demo/fbs/demo_fbs_grpc.dart"]
    assert (
        "class FbsSvcClient extends Client {" in fbs_dart["demo/fbs/demo_fbs_grpc.dart"]
    )


def test_dart_grpc_nested_rpc_payloads():
    from fory_compiler.generators.dart import DartGenerator

    schema = parse_fdl(
        dedent(
            """
            package demo.nested;

            message Envelope {
                message Request {
                    string name = 1;
                }
                message Reply {
                    string name = 1;
                }
            }

            service Nested {
                rpc Call (Envelope.Request) returns (Envelope.Reply);
            }
            """
        )
    )
    content = generate_service_files(schema, DartGenerator)[
        "demo/nested/demo_nested_grpc.dart"
    ]
    assert "ClientMethod<_models.Envelope_Request, _models.Envelope_Reply>(" in content
    assert "Future<_models.Envelope_Reply> call(" in content
    assert "_models.Envelope.Request" not in content


def test_dart_grpc_imported_rpc_payloads(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package demo.common;

            message Shared {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            service Api {
                rpc Call (demo.common.Shared) returns (demo.common.Shared);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "import '../common/common.dart' as demo_common;" in content
    assert "ClientMethod<demo_common.Shared, demo_common.Shared>(" in content
    assert "_models.demo.common.Shared" not in content


def test_dart_imported_model_collision(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package demo.common;

            message GreeterClient {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            message Req {}
            message Res {}

            service Greeter {
                rpc Call (Req) returns (Res);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "class GreeterClient extends Client {" in content
    assert "ClientMethod<_models.Req, _models.Res>(" in content


def test_dart_grpc_import_alias_collision(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package _models;

            message Req {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            message Req {}
            message Res {}

            service Api {
                rpc Call (_models.Req) returns (Res);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "import 'api.dart' as _models;" in content
    assert "import '../../_models/_models.dart' as _models_2;" in content
    assert "ClientMethod<_models_2.Req, _models.Res>(" in content
    assert "ServiceMethod<_models_2.Req, _models.Res>(" in content


def test_dart_grpc_helper_alias(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package _serialize;

            message Shared {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            message Res {}

            service Api {
                rpc Call (_serialize.Shared) returns (Res);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "import '../../_serialize/_serialize.dart' as _serialize_2;" in content
    assert "ClientMethod<_serialize_2.Shared, _models.Res>(" in content
    assert "ServiceMethod<_serialize_2.Shared, _models.Res>(" in content


def test_dart_grpc_service_alias(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package ApiClient;

            message Shared {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            message Res {}

            service Api {
                rpc Call (ApiClient.Shared) returns (Res);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "import '../../ApiClient/ApiClient.dart' as ApiClient_2;" in content
    assert "class ApiClient extends Client {" in content
    assert "ClientMethod<ApiClient_2.Shared, _models.Res>(" in content
    assert "ServiceMethod<ApiClient_2.Shared, _models.Res>(" in content


def test_dart_grpc_support_aliases(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    common = tmp_path / "common.fdl"
    common.write_text(
        dedent(
            """
            package Client;

            message Shared {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "common.fdl";

            message Res {}

            service Api {
                rpc Call (Client.Shared) returns (Res);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "import '../../Client/Client.dart' as Client_2;" in content
    assert "class ApiClient extends Client {" in content
    assert "ClientMethod<Client_2.Shared, _models.Res>(" in content
    assert "ServiceMethod<Client_2.Shared, _models.Res>(" in content


def test_dart_grpc_local_aliases(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    value_schema = tmp_path / "value.fdl"
    value_schema.write_text(
        dedent(
            """
            package value;

            message Shared {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "value.fdl";

            service Api {
                rpc Echo (Shared) returns (Shared);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "import '../../value/value.dart' as value_2;" in content
    assert "ClientMethod<value_2.Shared, value_2.Shared>(" in content
    assert "ServiceMethod<value_2.Shared, value_2.Shared>(" in content
    assert "_deserialize<value_2.Shared>(value)" in content
    assert "(value_2.Shared value) => _serialize(value)" in content
    assert "_models.value.Shared" not in content


def test_dart_grpc_method_aliases(tmp_path: Path):
    from fory_compiler.generators.dart import DartGenerator

    foo_schema = tmp_path / "foo.fdl"
    foo_schema.write_text(
        dedent(
            """
            package foo;

            message Shared {
                string id = 1;
            }
            """
        )
    )
    service = tmp_path / "service.fdl"
    service.write_text(
        dedent(
            """
            package demo.api;

            import "foo.fdl";

            service Api {
                rpc Foo (Shared) returns (Shared);
            }
            """
        )
    )
    schema = resolve_imports(service)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=tmp_path, grpc=True))
    content = generator.generate_services()[0].content

    assert "import '../../foo/foo.dart' as foo_2;" in content
    assert "ResponseFuture<foo_2.Shared> foo(" in content
    assert "Future<foo_2.Shared> foo_Pre(" in content
    assert "foo.Shared" not in content


def test_dart_grpc_reserved_methods():
    from fory_compiler.generators.dart import DartGenerator

    import pytest

    for rpc_name, emitted in [("ToString", "toString"), ("HashCode", "hashCode")]:
        schema = parse_fdl(
            dedent(
                f"""
                package demo.names;

                message Req {{}}
                message Res {{}}

                service Svc {{
                    rpc {rpc_name} (Req) returns (Res);
                }}
                """
            )
        )
        with pytest.raises(ValueError) as excinfo:
            generate_service_files(schema, DartGenerator)
        msg = str(excinfo.value)
        assert "inherited Dart member" in msg
        assert f"Svc.{rpc_name} -> {emitted}" in msg
