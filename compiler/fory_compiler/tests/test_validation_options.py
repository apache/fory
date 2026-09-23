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

"""Tests for validation rule options in the FDL schema validator."""

from textwrap import dedent
from typing import List

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.ir.validator import SchemaValidator, ValidationIssue


def _parse(source: str):
    lexer = Lexer(dedent(source))
    parser = Parser(lexer.tokenize())
    return parser.parse()


def _validate(source: str) -> List[ValidationIssue]:
    schema = _parse(source)
    validator = SchemaValidator(schema)
    validator.validate()
    return validator.errors


def _assert_rejected(source: str, fragment: str) -> None:
    errors = _validate(source)
    assert errors, f"expected validation errors for:\n{source}"
    messages = [e.message for e in errors]
    assert any(fragment in m for m in messages), (
        f"expected {fragment!r} in errors: {messages}"
    )


def test_gte_on_non_numeric_field_rejected():
    _assert_rejected(
        """
        package test;
        message M { string s = 1 [gte = 1]; }
        """,
        "requires a numeric primitive type",
    )


def test_min_len_on_non_string_field_rejected():
    _assert_rejected(
        """
        package test;
        message M { int32 x = 1 [min_len = 1]; }
        """,
        "requires a string type",
    )


def test_email_on_non_string_field_rejected():
    _assert_rejected(
        """
        package test;
        message M { int32 x = 1 [email = true]; }
        """,
        "requires a string type",
    )


def test_min_items_on_non_collection_field_rejected():
    _assert_rejected(
        """
        package test;
        message M { int32 x = 1 [min_items = 1]; }
        """,
        "requires a collection type",
    )


def test_gte_with_bool_value_rejected():
    _assert_rejected(
        """
        package test;
        message M { int32 x = 1 [gte = true]; }
        """,
        "requires a numeric primitive value",
    )


def test_min_len_with_string_value_rejected():
    _assert_rejected(
        """
        package test;
        message M { string s = 1 [min_len = "four"]; }
        """,
        "requires a numeric primitive value",
    )


def test_min_len_with_negative_value_rejected():
    _assert_rejected(
        """
        package test;
        message M { string s = 1 [min_len = -1]; }
        """,
        "requires a positive numeric primitive value",
    )


def test_max_items_with_negative_value_rejected():
    _assert_rejected(
        """
        package test;
        message M { list<int32> xs = 1 [max_items = -1]; }
        """,
        "requires a positive numeric primitive value",
    )


def test_email_with_non_bool_value_rejected():
    _assert_rejected(
        """
        package test;
        message M { string s = 1 [email = "yes"]; }
        """,
        "requires a boolean value",
    )


def test_uuid_with_non_bool_value_rejected():
    _assert_rejected(
        """
        package test;
        message M { string s = 1 [uuid = 1]; }
        """,
        "requires a boolean value",
    )


def test_pattern_with_non_string_value_rejected():
    _assert_rejected(
        """
        package test;
        message M { string s = 1 [pattern = 42]; }
        """,
        "requires a string value",
    )


def test_validation_options_checked_in_nested_message():
    _assert_rejected(
        """
        package test;
        message Outer {
            message Inner {
                string s = 1 [min_len = "x"];
            }
            Inner inner = 1;
        }
        """,
        "requires a numeric primitive value",
    )


def test_validation_options_checked_in_union():
    _assert_rejected(
        """
        package test;
        union U {
            int32 x = 1 [gte = true];
        }
        """,
        "requires a numeric primitive value",
    )


def test_valid_validation_options_accepted():
    errors = _validate(
        """
        package test;
        message M {
            int32 x = 1 [gte = 0, lte = 100];
            string s = 2 [min_len = 1, max_len = 10];
            string e = 3 [email = true];
            string u = 4 [uuid = true];
            string p = 5 [pattern = "^[a-z]+$"];
            list<int32> xs = 6 [min_items = 1, max_items = 5];
        }
        """
    )
    assert errors == [], f"unexpected errors: {[e.message for e in errors]}"
