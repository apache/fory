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
from textwrap import dedent
from typing import Dict, Tuple, Type

from fory_compiler.cli import compile_file, resolve_imports
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
from fory_compiler.generators.go import GoGenerator
from fory_compiler.generators.java import JavaGenerator
from fory_compiler.generators.python import PythonGenerator
from fory_compiler.generators.rust import RustGenerator
from fory_compiler.generators.swift import SwiftGenerator
from fory_compiler.ir.ast import Schema
from fory_compiler.ir.validator import SchemaValidator


GENERATOR_CLASSES: Tuple[Type[BaseGenerator], ...] = (
    JavaGenerator,
    PythonGenerator,
    CppGenerator,
    RustGenerator,
    GoGenerator,
    CSharpGenerator,
    SwiftGenerator,
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


def test_service_definition_does_not_affect_message_codegen():
    schema_with = parse_fdl(_GREETER_WITH_SERVICE)
    schema_without = parse_fdl(_GREETER_WITHOUT_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        files_with = generate_files(schema_with, generator_cls)
        files_without = generate_files(schema_without, generator_cls)
        assert files_with == files_without, (
            f"{generator_cls.language_name}: service definition changed message output"
        )


def test_generate_services_returns_empty_list_for_unsupported_generators():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        if generator_cls in (JavaGenerator, PythonGenerator):
            continue
        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = generator_cls(schema, options)
        assert generator.generate_services() == [], (
            f"{generator_cls.language_name}: generate_services() should return []"
        )


def test_java_grpc_service_codegen_contains_fory_marshaller():
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


def test_python_grpc_service_codegen_uses_byte_callbacks():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    files = generate_service_files(schema, PythonGenerator)
    assert set(files) == {"demo_greeter_grpc.py"}
    content = files["demo_greeter_grpc.py"]
    assert "class GreeterStub(object):" in content
    assert "class GreeterServicer(object):" in content
    assert "def add_servicer(servicer, server):" in content
    assert "add_GreeterServicer_to_server" not in content
    assert "self.say_hello = channel.unary_unary(" in content
    assert "def say_hello(self, request, context):" in content
    assert '        "SayHello": grpc.unary_unary_rpc_method_handler(' in content
    assert "servicer.say_hello" in content
    assert "return _models._get_fory().serialize(value)" in content
    assert "return _models._get_fory().deserialize(data)" in content
    assert '"/demo.greeter.Greeter/SayHello"' in content
    assert "SerializeToString" not in content
    assert "FromString" not in content


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
    assert "channel.unary_unary(" in python
    assert "channel.unary_stream(" in python
    assert "channel.stream_unary(" in python
    assert "channel.stream_stream(" in python
    assert "grpc.stream_stream_rpc_method_handler(" in python
    assert "self.unary = channel.unary_unary(" in python
    assert "self.server = channel.unary_stream(" in python
    assert "self.client = channel.stream_unary(" in python
    assert "self.bidi = channel.stream_stream(" in python


def test_java_outer_classname_service_references_nested_model_types():
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


def test_grpc_services_use_imported_java_type_references(tmp_path: Path):
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


def test_proto_grpc_services_use_imported_qualified_type_references(tmp_path: Path):
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


def test_proto_grpc_absolute_rpc_type_uses_package_type_not_nested_shadow():
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


def test_proto_grpc_absolute_rpc_type_prefers_longest_package_prefix(tmp_path: Path):
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


def test_java_grpc_service_class_collision_fails():
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


def test_java_grpc_service_class_collision_with_imported_type_fails(tmp_path: Path):
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


def test_java_grpc_service_class_collision_with_imported_outer_fails(tmp_path: Path):
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


def test_python_grpc_method_keywords_are_safe_names():
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


def test_python_grpc_service_registration_collisions_fail():
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


def test_default_package_java_grpc_output_path_and_service_name():
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
    assert "demo/proto/ProtoSvcGrpc.java" in proto_java
    assert "demo_proto_grpc.py" in proto_python
    assert "MethodType.SERVER_STREAMING" in proto_java["demo/proto/ProtoSvcGrpc.java"]
    assert "channel.unary_stream(" in proto_python["demo_proto_grpc.py"]

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
    assert "demo/fbs/FbsSvcGrpc.java" in fbs_java
    assert "demo_fbs_grpc.py" in fbs_python
    assert 'SERVICE_NAME = "demo.fbs.FbsSvc"' in fbs_java["demo/fbs/FbsSvcGrpc.java"]
    assert '"/demo.fbs.FbsSvc/Call"' in fbs_python["demo_fbs_grpc.py"]


def test_service_schema_produces_one_file_per_message_per_language():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        files = generate_files(schema, generator_cls)
        assert len(files) >= 1, (
            f"{generator_cls.language_name}: expected at least one generated file"
        )


def test_compile_service_schema_with_grpc_flag(tmp_path: Path):
    example_path = Path(__file__).resolve().parents[2] / "examples" / "service.fdl"
    lang_dirs = {}
    for lang in ("java", "python", "rust", "go", "cpp", "csharp", "swift"):
        lang_dirs[lang] = tmp_path / lang
    ok = compile_file(example_path, lang_dirs, grpc=True)
    assert ok is True
    for lang, lang_dir in lang_dirs.items():
        files = [p for p in lang_dir.rglob("*") if p.is_file()]
        assert len(files) >= 1, f"{lang}: expected at least one file with grpc=True"
    assert (lang_dirs["java"] / "demo" / "greeter" / "GreeterGrpc.java").exists()
    assert (lang_dirs["python"] / "demo_greeter_grpc.py").exists()


def test_generated_message_contains_key_signatures():
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
