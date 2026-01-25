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

"""Tests for message field defaults in FDL."""

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.ir.validator import SchemaValidator


def test_message_fields_are_optional_by_default():
    source = """
    message Foo {
        Bar bar = 1;
    }

    message Bar {
        int32 id = 1;
    }
    """
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert validator.validate()
    field = schema.messages[0].fields[0]
    assert field.optional is True


def test_message_fields_disallow_optional_modifier_in_fdl():
    source = """
    message Foo {
        optional Bar bar = 1;
    }

    message Bar {
        int32 id = 1;
    }
    """
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert not validator.validate()
    assert any(
        "Message fields are always optional" in str(err) for err in validator.errors
    )


def test_fdl_field_numbers_set_tag_ids():
    source = """
    message Foo {
        int32 id = 7;
    }
    """
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert validator.validate()
    field = schema.messages[0].fields[0]
    assert field.tag_id == field.number
