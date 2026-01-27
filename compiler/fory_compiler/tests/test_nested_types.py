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

"""Tests for FDL nested type support."""

import pytest

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.ir.ast import NamedType, ListType
from fory_compiler.ir.validator import SchemaValidator


class TestNestedMessageParsing:
    """Tests for parsing nested messages."""

    def test_simple_nested_message(self):
        """Test parsing a simple nested message."""
        source = """
        message SearchResponse {
            message Result {
                string url = 1;
                string title = 2;
            }
            repeated Result results = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        outer = schema.messages[0]
        assert outer.name == "SearchResponse"
        assert len(outer.nested_messages) == 1
        inner = outer.nested_messages[0]
        assert inner.name == "Result"
        assert len(inner.fields) == 2

    def test_nested_enum(self):
        """Test parsing a nested enum."""
        source = """
        message Outer {
            enum Status {
                UNKNOWN = 0;
                ACTIVE = 1;
                INACTIVE = 2;
            }
            Status status = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        outer = schema.messages[0]
        assert len(outer.nested_enums) == 1
        nested_enum = outer.nested_enums[0]
        assert nested_enum.name == "Status"
        assert len(nested_enum.values) == 3

    def test_deeply_nested_message(self):
        """Test parsing deeply nested messages."""
        source = """
        message Outer {
            message Middle {
                message Inner {
                    string value = 1;
                }
                Inner inner = 1;
            }
            Middle middle = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        outer = schema.messages[0]
        assert outer.name == "Outer"
        middle = outer.nested_messages[0]
        assert middle.name == "Middle"
        inner = middle.nested_messages[0]
        assert inner.name == "Inner"

    def test_mixed_nested_types(self):
        """Test parsing messages with both nested messages and enums."""
        source = """
        message Container {
            enum Type {
                TYPE_UNKNOWN = 0;
                TYPE_A = 1;
                TYPE_B = 2;
            }
            message Item {
                string name = 1;
                Type type = 2;
            }
            repeated Item items = 1;
            Type default_type = 2;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        container = schema.messages[0]
        assert len(container.nested_enums) == 1
        assert len(container.nested_messages) == 1
        assert container.nested_enums[0].name == "Type"
        assert container.nested_messages[0].name == "Item"

    def test_nested_union(self):
        """Test parsing a nested union."""
        source = """
        message Container {
            message Dog { string name = 1; }
            message Cat { string name = 1; }
            union Animal {
                Dog dog = 1;
                Cat cat = 2;
            }
            Animal pet = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        container = schema.messages[0]
        assert len(container.nested_unions) == 1
        union = container.nested_unions[0]
        assert union.name == "Animal"
        assert container.fields[0].field_type.name == "Animal"


class TestQualifiedTypeNames:
    """Tests for qualified type names (Parent.Child)."""

    def test_qualified_type_in_field(self):
        """Test using qualified type names in field definitions."""
        source = """
        message SearchResponse {
            message Result {
                string url = 1;
            }
        }
        message SearchRequest {
            SearchResponse.Result cached_result = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        request = schema.messages[1]
        assert request.name == "SearchRequest"
        field = request.fields[0]
        assert isinstance(field.field_type, NamedType)
        assert field.field_type.name == "SearchResponse.Result"

    def test_qualified_type_in_list(self):
        """Test using qualified type names in list fields."""
        source = """
        message Outer {
            message Inner {
                string value = 1;
            }
        }
        message Container {
            repeated Outer.Inner items = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        container = schema.messages[1]
        field = container.fields[0]
        assert isinstance(field.field_type, ListType)
        assert isinstance(field.field_type.element_type, NamedType)
        assert field.field_type.element_type.name == "Outer.Inner"


class TestNestedTypeValidation:
    """Tests for validation of nested types."""

    def test_valid_nested_type_reference(self):
        """Test that references to nested types are valid."""
        source = """
        message SearchResponse {
            message Result {
                string url = 1;
            }
            repeated Result results = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()
        validator = SchemaValidator(schema)
        is_valid = validator.validate()

        assert is_valid

    def test_valid_qualified_type_reference(self):
        """Test that qualified type references are valid."""
        source = """
        message SearchResponse {
            message Result {
                string url = 1;
            }
        }
        message Collector {
            SearchResponse.Result best_result = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()
        validator = SchemaValidator(schema)
        is_valid = validator.validate()

        assert is_valid

    def test_duplicate_nested_type_names(self):
        """Test that duplicate nested type names are detected."""
        source = """
        message Container {
            message Inner {
                string a = 1;
            }
            message Inner {
                string b = 1;
            }
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()
        validator = SchemaValidator(schema)
        is_valid = validator.validate()

        assert not is_valid
        assert len(validator.errors) == 1
        assert "Duplicate nested type name" in validator.errors[0].message

    def test_duplicate_type_ids_in_nested(self):
        """Test that duplicate type IDs in nested types are detected."""
        source = """
        message Outer [id=100] {
            message Inner [id=100] {
                string value = 1;
            }
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()
        validator = SchemaValidator(schema)
        is_valid = validator.validate()

        assert not is_valid
        assert len(validator.errors) == 1
        assert "Duplicate type ID @100" in validator.errors[0].message

    def test_unknown_nested_type(self):
        """Test that references to unknown nested types are detected."""
        source = """
        message Container {
            NonExistent.Type field = 1;
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()
        validator = SchemaValidator(schema)
        is_valid = validator.validate()

        assert not is_valid
        assert len(validator.errors) == 1
        assert "Unknown type" in validator.errors[0].message


class TestSchemaTypeLookup:
    """Tests for Schema.get_type with nested types."""

    def test_get_nested_type_by_qualified_name(self):
        """Test looking up nested types by qualified name."""
        source = """
        message SearchResponse {
            message Result {
                string url = 1;
            }
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        # Should find by qualified name
        result_type = schema.get_type("SearchResponse.Result")
        assert result_type is not None
        assert result_type.name == "Result"

        # Should find top-level type by simple name
        response_type = schema.get_type("SearchResponse")
        assert response_type is not None
        assert response_type.name == "SearchResponse"

    def test_get_deeply_nested_type(self):
        """Test looking up deeply nested types."""
        source = """
        message A {
            message B {
                message C {
                    string value = 1;
                }
            }
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        c_type = schema.get_type("A.B.C")
        assert c_type is not None
        assert c_type.name == "C"

    def test_get_all_types_includes_nested(self):
        """Test that get_all_types includes nested types."""
        source = """
        message Outer {
            enum Status {
                UNKNOWN = 0;
            }
            message Inner {
                message Deep {
                    string value = 1;
                }
            }
        }
        """
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        all_types = schema.get_all_types()
        type_names = [t.name for t in all_types]

        assert "Outer" in type_names
        assert "Status" in type_names
        assert "Inner" in type_names
        assert "Deep" in type_names


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
