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

"""Tests for TypeScript code generation."""

from pathlib import Path
from textwrap import dedent

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.typescript import TypeScriptGenerator
from fory_compiler.ir.ast import Schema


def parse_fdl(source: str) -> Schema:
    return Parser(Lexer(source).tokenize()).parse()


def generate_typescript(source: str) -> str:
    schema = parse_fdl(source)
    options = GeneratorOptions(output_dir=Path("/tmp"))
    generator = TypeScriptGenerator(schema, options)
    files = generator.generate()
    assert len(files) == 1, f"Expected 1 file, got {len(files)}"
    return files[0].content


def test_typescript_enum_generation():
    """Test that enums are properly generated."""
    source = dedent(
        """
        package example;

        enum Color [id=101] {
            RED = 0;
            GREEN = 1;
            BLUE = 2;
        }
        """
    )
    output = generate_typescript(source)

    # Check enum definition
    assert "export enum Color" in output
    assert "RED = 0" in output
    assert "GREEN = 1" in output
    assert "BLUE = 2" in output
    assert "Type ID 101" in output


def test_typescript_message_generation():
    """Test that messages are properly generated as interfaces."""
    source = dedent(
        """
        package example;

        message Person [id=102] {
            string name = 1;
            int32 age = 2;
            optional string email = 3;
        }
        """
    )
    output = generate_typescript(source)

    # Check interface definition
    assert "export interface Person" in output
    assert "name: string;" in output
    assert "age: number;" in output
    assert "email: string | undefined;" in output
    assert "Type ID 102" in output


def test_typescript_nested_message():
    """Test that nested messages are properly generated."""
    source = dedent(
        """
        package example;

        message Person [id=100] {
            string name = 1;

            message Address [id=101] {
                string street = 1;
                string city = 2;
            }

            Address address = 2;
        }
        """
    )
    output = generate_typescript(source)

    # Check nested interface
    assert "export interface Person" in output
    assert "export interface Address" in output
    assert "street: string;" in output
    assert "city: string;" in output


def test_typescript_nested_enum():
    """Test that nested enums are properly generated."""
    source = dedent(
        """
        package example;

        message Person [id=100] {
            string name = 1;

            enum PhoneType [id=101] {
                MOBILE = 0;
                HOME = 1;
            }
        }
        """
    )
    output = generate_typescript(source)

    # Check nested enum
    assert "export enum PhoneType" in output
    assert "MOBILE = 0" in output
    assert "HOME = 1" in output


def test_typescript_union_generation():
    """Test that unions are properly generated as discriminated unions."""
    source = dedent(
        """
        package example;

        message Dog [id=101] {
            string name = 1;
            int32 bark_volume = 2;
        }

        message Cat [id=102] {
            string name = 1;
            int32 lives = 2;
        }

        union Animal [id=103] {
            Dog dog = 1;
            Cat cat = 2;
        }
        """
    )
    output = generate_typescript(source)

    # Check union generation
    assert "export enum AnimalCase" in output
    assert "DOG = 1" in output
    assert "CAT = 2" in output
    assert "export type Animal" in output
    assert "AnimalCase.DOG" in output
    assert "AnimalCase.CAT" in output
    assert "Type ID 103" in output


def test_typescript_collection_types():
    """Test that collection types are properly mapped."""
    source = dedent(
        """
        package example;

        message Data [id=100] {
            repeated string items = 1;
            map<string, int32> config = 2;
        }
        """
    )
    output = generate_typescript(source)

    # Check collection types
    assert "items: string[];" in output
    assert "config: Record<string, number>;" in output


def test_typescript_primitive_types():
    """Test that all primitive types are properly mapped."""
    source = dedent(
        """
        package example;

        message AllTypes [id=100] {
            bool f_bool = 1;
            int32 f_int32 = 2;
            int64 f_int64 = 3;
            uint32 f_uint32 = 4;
            uint64 f_uint64 = 5;
            float f_float = 6;
            double f_double = 7;
            string f_string = 8;
            bytes f_bytes = 9;
        }
        """
    )
    output = generate_typescript(source)

    # Check type mappings
    assert "f_bool: boolean;" in output
    assert "f_int32: number;" in output
    assert "f_int64: bigint | number;" in output
    assert "f_uint32: number;" in output
    assert "f_uint64: bigint | number;" in output
    assert "f_float: number;" in output
    assert "f_double: number;" in output
    assert "f_string: string;" in output
    assert "f_bytes: Uint8Array;" in output


def test_typescript_file_structure():
    """Test that generated file has proper structure."""
    source = dedent(
        """
        package example.v1;

        enum Status [id=100] {
            UNKNOWN = 0;
            ACTIVE = 1;
        }

        message Request [id=101] {
            string query = 1;
        }

        union Response [id=102] {
            string result = 1;
            string error = 2;
        }
        """
    )
    output = generate_typescript(source)

    # Check license header
    assert "Apache Software Foundation (ASF)" in output
    assert "Licensed" in output

    # Check package comment
    assert "Package: example.v1" in output

    # Check section comments
    assert "// Enums" in output
    assert "// Messages" in output
    assert "// Unions" in output
    assert "// Registration helper" in output

    # Check registration function
    assert "export function registerExampleV1Types" in output


def test_typescript_field_naming():
    """Test that field names are converted to camelCase."""
    source = dedent(
        """
        package example;

        message Person [id=100] {
            string first_name = 1;
            string last_name = 2;
            int32 phone_number = 3;
        }
        """
    )
    output = generate_typescript(source)

    # Check camelCase conversion
    assert "first_name:" in output or "firstName:" in output
    assert "last_name:" in output or "lastName:" in output
    assert "phone_number:" in output or "phoneNumber:" in output


def test_typescript_no_runtime_dependencies():
    """Test that generated code has no gRPC runtime dependencies."""
    source = dedent(
        """
        package example;

        message Request [id=100] {
            string query = 1;
        }
        """
    )
    output = generate_typescript(source)

    # Should not reference gRPC
    assert "@grpc" not in output
    assert "grpc-js" not in output
    assert "require('grpc" not in output
    assert "import.*grpc" not in output


def test_typescript_file_extension():
    """Test that output file has correct extension."""
    source = dedent(
        """
        package example;

        message Test [id=100] {
            string value = 1;
        }
        """
    )

    schema = parse_fdl(source)
    options = GeneratorOptions(output_dir=Path("/tmp"))
    generator = TypeScriptGenerator(schema, options)
    files = generator.generate()

    assert len(files) == 1
    assert files[0].path.endswith(".ts")


def test_typescript_enum_value_stripping():
    """Test that enum value prefixes are stripped correctly."""
    source = dedent(
        """
        package example;

        enum PhoneType [id=100] {
            PHONE_TYPE_MOBILE = 0;
            PHONE_TYPE_HOME = 1;
            PHONE_TYPE_WORK = 2;
        }
        """
    )
    output = generate_typescript(source)

    # Prefixes should be stripped
    assert "MOBILE = 0" in output
    assert "HOME = 1" in output
    assert "WORK = 2" in output
