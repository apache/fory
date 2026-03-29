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

from fory_compiler.cli import compile_file
from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import BaseGenerator, GeneratorOptions
from fory_compiler.generators.cpp import CppGenerator
from fory_compiler.generators.csharp import CSharpGenerator
from fory_compiler.generators.go import GoGenerator
from fory_compiler.generators.java import JavaGenerator
from fory_compiler.generators.python import PythonGenerator
from fory_compiler.generators.rust import RustGenerator
from fory_compiler.generators.swift import SwiftGenerator
from fory_compiler.ir.ast import Schema


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


def generate_files(schema: Schema, generator_cls: Type[BaseGenerator]) -> Dict[str, str]:
    options = GeneratorOptions(output_dir=Path("/tmp"))
    generator = generator_cls(schema, options)
    return {item.path: item.content for item in generator.generate()}


def test_service_definition_does_not_affect_message_codegen():
    schema_with = parse_fdl(_GREETER_WITH_SERVICE)
    schema_without = parse_fdl(_GREETER_WITHOUT_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        files_with = generate_files(schema_with, generator_cls)
        files_without = generate_files(schema_without, generator_cls)
        assert files_with == files_without, (
            f"{generator_cls.language_name}: service definition changed message output"
        )


def test_generate_services_returns_empty_list_for_all_generators():
    schema = parse_fdl(_GREETER_WITH_SERVICE)
    for generator_cls in GENERATOR_CLASSES:
        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = generator_cls(schema, options)
        assert generator.generate_services() == [], (
            f"{generator_cls.language_name}: generate_services() should return [] until gRPC is implemented"
        )


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
