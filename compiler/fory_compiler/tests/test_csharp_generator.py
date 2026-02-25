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

"""Tests for C# generator behavior."""

import warnings
from pathlib import Path

from fory_compiler.cli import resolve_imports
from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.csharp import CSharpGenerator


def parse_schema(source: str):
    return Parser(Lexer(source).tokenize()).parse()


def generate(source: str):
    schema = parse_schema(source)
    generator = CSharpGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    return generator.generate()[0]


def test_csharp_namespace_option_used():
    file = generate(
        """
        package payment;
        option csharp_namespace = "MyCorp.Payment.V1";

        message Payment {
            string id = 1;
        }
        """
    )

    assert file.path == "MyCorp/Payment/V1/payment.cs"
    assert "namespace MyCorp.Payment.V1;" in file.content
    assert "public sealed partial class Payment" in file.content


def test_csharp_namespace_fallback_to_package():
    file = generate(
        """
        package com.example.models;

        message User {
            string name = 1;
        }
        """
    )

    assert file.path == "com/example/models/com_example_models.cs"
    assert "namespace com.example.models;" in file.content


def test_csharp_registration_uses_fdl_package_for_name_registration():
    file = generate(
        """
        package myapp.models;
        option csharp_namespace = "MyCorp.Generated.Models";
        option enable_auto_type_id = false;

        message User {
            string name = 1;
        }
        """
    )

    assert 'fory.Register<global::MyCorp.Generated.Models.User>("myapp.models", "User");' in file.content


def test_csharp_field_encoding_attributes():
    file = generate(
        """
        package example;

        message Encoded {
            fixed_int32 fixed_id = 1;
            tagged_uint64 tagged = 2;
            int32 plain = 3;
        }
        """
    )

    assert "[Field(Encoding = FieldEncoding.Fixed)]" in file.content
    assert "[Field(Encoding = FieldEncoding.Tagged)]" in file.content
    assert "public int Plain { get; set; }" in file.content


def test_csharp_imported_registration_calls_generated():
    repo_root = Path(__file__).resolve().parents[3]
    idl_dir = repo_root / "integration_tests" / "idl_tests" / "idl"
    schema = resolve_imports(idl_dir / "root.idl", [idl_dir])

    generator = CSharpGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    file = generator.generate()[0]

    assert "global::addressbook.AddressbookForyRegistration.Register(fory);" in file.content
    assert "global::tree.TreeForyRegistration.Register(fory);" in file.content


def test_csharp_namespace_option_is_known():
    source = """
    package myapp;
    option csharp_namespace = "MyCorp.MyApp";

    message User {
      string name = 1;
    }
    """

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        schema = parse_schema(source)

    assert schema.get_option("csharp_namespace") == "MyCorp.MyApp"
    assert not caught
