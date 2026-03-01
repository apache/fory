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

"""Tests for JavaScript code generation."""

from pathlib import Path
from textwrap import dedent

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.javascript import JavaScriptGenerator
from fory_compiler.ir.ast import Schema


def parse_fdl(source: str) -> Schema:
    return Parser(Lexer(source).tokenize()).parse()


def generate_javascript(source: str) -> str:
    schema = parse_fdl(source)
    options = GeneratorOptions(output_dir=Path("/tmp"))
    generator = JavaScriptGenerator(schema, options)
    files = generator.generate()
    assert len(files) == 1, f"Expected 1 file, got {len(files)}"
    return files[0].content


def test_javascript_enum_generation():
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
    output = generate_javascript(source)

    # Check enum definition
    assert "export enum Color" in output
    assert "RED = 0" in output
    assert "GREEN = 1" in output
    assert "BLUE = 2" in output
    assert "Type ID 101" in output


def test_javascript_message_generation():
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
    output = generate_javascript(source)

    # Check interface definition
    assert "export interface Person" in output
    assert "name: string;" in output
    assert "age: number;" in output
    assert "email?: string | undefined;" in output
    assert "Type ID 102" in output


def test_javascript_nested_message():
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
    output = generate_javascript(source)

    # Check nested interface
    assert "export interface Person" in output
    assert "export interface Address" in output
    assert "street: string;" in output
    assert "city: string;" in output


def test_javascript_nested_enum():
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
    output = generate_javascript(source)

    # Check nested enum
    assert "export enum PhoneType" in output
    assert "MOBILE = 0" in output
    assert "HOME = 1" in output


def test_javascript_nested_enum_registration_uses_simple_name():
    """Test that nested enums are registered with simple names, not qualified names."""
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
    output = generate_javascript(source)

    # Enums are skipped during registration in JavaScript (they are numeric
    # values at runtime and don't need separate Fory registration).
    assert "fory.register('PhoneType'" not in output
    # Messages are still registered (using string name since interfaces
    # don't exist at runtime).
    assert "fory.register('Person', 100)" in output
    # Ensure qualified names are NOT used
    assert "Person.PhoneType" not in output


def test_javascript_union_generation():
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
    output = generate_javascript(source)

    # Check union generation
    assert "export enum AnimalCase" in output
    assert "DOG = 1" in output
    assert "CAT = 2" in output
    assert "export type Animal" in output
    assert "AnimalCase.DOG" in output
    assert "AnimalCase.CAT" in output
    assert "Type ID 103" in output


def test_javascript_collection_types():
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
    output = generate_javascript(source)

    # Check collection types
    assert "items: string[];" in output
    assert "config: Record<string, number>;" in output


def test_javascript_primitive_types():
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
    output = generate_javascript(source)

    # Check type mappings (field names are converted to camelCase)
    assert "fBool: boolean;" in output
    assert "fInt32: number;" in output
    assert "fInt64: bigint | number;" in output
    assert "fUint32: number;" in output
    assert "fUint64: bigint | number;" in output
    assert "fFloat: number;" in output
    assert "fDouble: number;" in output
    assert "fString: string;" in output
    assert "fBytes: Uint8Array;" in output


def test_javascript_file_structure():
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
    output = generate_javascript(source)

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

    # Check registration function (uses last segment of package name)
    assert "export function registerV1Types" in output


def test_javascript_field_naming():
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
    output = generate_javascript(source)

    # Check that field names are properly converted to camelCase
    assert "firstName:" in output
    assert "lastName:" in output
    assert "phoneNumber:" in output
    # Ensure snake_case is not used
    assert "first_name:" not in output
    assert "last_name:" not in output
    assert "phone_number:" not in output


def test_javascript_no_runtime_dependencies():
    """Test that generated code has no gRPC runtime dependencies."""
    source = dedent(
        """
        package example;

        message Request [id=100] {
            string query = 1;
        }
        """
    )
    output = generate_javascript(source)

    # Should not reference gRPC
    assert "@grpc" not in output
    assert "grpc-js" not in output
    assert "require('grpc" not in output
    assert "import.*grpc" not in output


def test_javascript_file_extension():
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
    generator = JavaScriptGenerator(schema, options)
    files = generator.generate()

    assert len(files) == 1
    assert files[0].path.endswith(".js") or files[0].path.endswith(".ts"), (
        f"Unexpected file extension: {files[0].path}"
    )


def test_javascript_enum_value_stripping():
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
    output = generate_javascript(source)

    # Prefixes should be stripped
    assert "MOBILE = 0" in output
    assert "HOME = 1" in output
    assert "WORK = 2" in output
