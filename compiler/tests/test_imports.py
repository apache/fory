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

"""Tests for FDL import functionality."""

import pytest
import tempfile
import os
from pathlib import Path

from fory_compiler.parser.lexer import Lexer, TokenType
from fory_compiler.parser.parser import Parser
from fory_compiler.cli import resolve_imports, ImportError


class TestLexerImport:
    """Tests for lexer import token support."""

    def test_import_keyword(self):
        """Test that 'import' is recognized as a keyword."""
        lexer = Lexer('import "foo.fdl";')
        tokens = lexer.tokenize()

        assert tokens[0].type == TokenType.IMPORT
        assert tokens[0].value == "import"

    def test_string_literal_double_quotes(self):
        """Test parsing double-quoted string literals."""
        lexer = Lexer('"hello/world.fdl"')
        tokens = lexer.tokenize()

        assert tokens[0].type == TokenType.STRING
        assert tokens[0].value == "hello/world.fdl"

    def test_string_literal_single_quotes(self):
        """Test parsing single-quoted string literals."""
        lexer = Lexer("'hello/world.fdl'")
        tokens = lexer.tokenize()

        assert tokens[0].type == TokenType.STRING
        assert tokens[0].value == "hello/world.fdl"

    def test_string_literal_escape_sequences(self):
        """Test escape sequences in string literals."""
        lexer = Lexer(r'"path\\to\\file.fdl"')
        tokens = lexer.tokenize()

        assert tokens[0].type == TokenType.STRING
        assert tokens[0].value == "path\\to\\file.fdl"

    def test_full_import_statement(self):
        """Test tokenizing a complete import statement."""
        lexer = Lexer('import "common/types.fdl";')
        tokens = lexer.tokenize()

        assert tokens[0].type == TokenType.IMPORT
        assert tokens[1].type == TokenType.STRING
        assert tokens[1].value == "common/types.fdl"
        assert tokens[2].type == TokenType.SEMI


class TestParserImport:
    """Tests for parser import statement support."""

    def test_parse_single_import(self):
        """Test parsing a single import statement."""
        source = '''
        import "common.fdl";

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.imports) == 1
        assert schema.imports[0].path == "common.fdl"

    def test_parse_multiple_imports(self):
        """Test parsing multiple import statements."""
        source = '''
        import "common.fdl";
        import "types/address.fdl";
        import "types/contact.fdl";

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.imports) == 3
        assert schema.imports[0].path == "common.fdl"
        assert schema.imports[1].path == "types/address.fdl"
        assert schema.imports[2].path == "types/contact.fdl"

    def test_imports_after_package(self):
        """Test imports can appear after package declaration."""
        source = '''
        package myapp;
        import "common.fdl";

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.package == "myapp"
        assert len(schema.imports) == 1
        assert schema.imports[0].path == "common.fdl"


class TestImportResolution:
    """Tests for import resolution in CLI."""

    def test_simple_import(self):
        """Test resolving a simple import."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create common.fdl
            common_fdl = tmpdir / "common.fdl"
            common_fdl.write_text('''
            package common;

            message Address [id=100] {
                string street = 1;
                string city = 2;
            }
            ''')

            # Create main.fdl that imports common.fdl
            main_fdl = tmpdir / "main.fdl"
            main_fdl.write_text('''
            package main;
            import "common.fdl";

            message User [id=101] {
                string name = 1;
                Address address = 2;
            }
            ''')

            # Resolve imports
            schema = resolve_imports(main_fdl)

            # Should have both Address and User
            assert len(schema.messages) == 2
            type_names = {m.name for m in schema.messages}
            assert "Address" in type_names
            assert "User" in type_names

    def test_nested_imports(self):
        """Test resolving nested imports (A imports B, B imports C)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create base.fdl
            base_fdl = tmpdir / "base.fdl"
            base_fdl.write_text('''
            package base;

            enum Status [id=100] {
                ACTIVE = 0;
                INACTIVE = 1;
            }
            ''')

            # Create common.fdl that imports base.fdl
            common_fdl = tmpdir / "common.fdl"
            common_fdl.write_text('''
            package common;
            import "base.fdl";

            message BaseEntity [id=101] {
                Status status = 1;
            }
            ''')

            # Create main.fdl that imports common.fdl
            main_fdl = tmpdir / "main.fdl"
            main_fdl.write_text('''
            package main;
            import "common.fdl";

            message User [id=102] {
                string name = 1;
                Status status = 2;
            }
            ''')

            # Resolve imports
            schema = resolve_imports(main_fdl)

            # Should have Status enum and both messages
            assert len(schema.enums) == 1
            assert schema.enums[0].name == "Status"
            assert len(schema.messages) == 2

    def test_subdirectory_import(self):
        """Test importing from a subdirectory."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create types subdirectory
            types_dir = tmpdir / "types"
            types_dir.mkdir()

            # Create types/address.fdl
            address_fdl = types_dir / "address.fdl"
            address_fdl.write_text('''
            package types;

            message Address [id=100] {
                string street = 1;
            }
            ''')

            # Create main.fdl
            main_fdl = tmpdir / "main.fdl"
            main_fdl.write_text('''
            package main;
            import "types/address.fdl";

            message User [id=101] {
                Address home = 1;
            }
            ''')

            # Resolve imports
            schema = resolve_imports(main_fdl)

            assert len(schema.messages) == 2
            type_names = {m.name for m in schema.messages}
            assert "Address" in type_names
            assert "User" in type_names

    def test_circular_import_detection(self):
        """Test that circular imports are detected."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create a.fdl that imports b.fdl
            a_fdl = tmpdir / "a.fdl"
            a_fdl.write_text('''
            package a;
            import "b.fdl";

            message A [id=100] {
                string name = 1;
            }
            ''')

            # Create b.fdl that imports a.fdl (circular!)
            b_fdl = tmpdir / "b.fdl"
            b_fdl.write_text('''
            package b;
            import "a.fdl";

            message B [id=101] {
                string name = 1;
            }
            ''')

            # Should raise ImportError
            with pytest.raises(ImportError) as exc_info:
                resolve_imports(a_fdl)

            assert "Circular import" in str(exc_info.value)

    def test_missing_import(self):
        """Test error when imported file doesn't exist."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create main.fdl that imports non-existent file
            main_fdl = tmpdir / "main.fdl"
            main_fdl.write_text('''
            package main;
            import "nonexistent.fdl";

            message User [id=100] {
                string name = 1;
            }
            ''')

            # Should raise ImportError
            with pytest.raises(ImportError) as exc_info:
                resolve_imports(main_fdl)

            assert "Import not found" in str(exc_info.value)

    def test_diamond_import(self):
        """Test diamond dependency pattern (A imports B and C, both import D)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create d.fdl (base)
            d_fdl = tmpdir / "d.fdl"
            d_fdl.write_text('''
            package d;

            message Base [id=100] {
                string id = 1;
            }
            ''')

            # Create b.fdl (imports d)
            b_fdl = tmpdir / "b.fdl"
            b_fdl.write_text('''
            package b;
            import "d.fdl";

            message B [id=101] {
                Base base = 1;
            }
            ''')

            # Create c.fdl (imports d)
            c_fdl = tmpdir / "c.fdl"
            c_fdl.write_text('''
            package c;
            import "d.fdl";

            message C [id=102] {
                Base base = 1;
            }
            ''')

            # Create a.fdl (imports b and c)
            a_fdl = tmpdir / "a.fdl"
            a_fdl.write_text('''
            package a;
            import "b.fdl";
            import "c.fdl";

            message A [id=103] {
                B b = 1;
                C c = 2;
            }
            ''')

            # Resolve imports - should handle diamond without error
            schema = resolve_imports(a_fdl)

            # Note: Base will appear twice due to diamond, but validation will catch duplicates
            type_names = [m.name for m in schema.messages]
            assert "A" in type_names
            assert "B" in type_names
            assert "C" in type_names

    def test_relative_path_resolution(self):
        """Test that relative paths are resolved correctly."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create directory structure
            src_dir = tmpdir / "src"
            src_dir.mkdir()
            common_dir = tmpdir / "common"
            common_dir.mkdir()

            # Create common/types.fdl
            types_fdl = common_dir / "types.fdl"
            types_fdl.write_text('''
            package common;

            message CommonType [id=100] {
                string value = 1;
            }
            ''')

            # Create src/main.fdl with relative path
            main_fdl = src_dir / "main.fdl"
            main_fdl.write_text('''
            package src;
            import "../common/types.fdl";

            message User [id=101] {
                CommonType data = 1;
            }
            ''')

            # Resolve imports
            schema = resolve_imports(main_fdl)

            assert len(schema.messages) == 2
            type_names = {m.name for m in schema.messages}
            assert "CommonType" in type_names
            assert "User" in type_names


class TestValidationWithImports:
    """Tests for schema validation with imports."""

    def test_valid_type_reference_from_import(self):
        """Test that types from imports can be referenced."""
        with tempfile.TemporaryDirectory() as tmpdir:
            tmpdir = Path(tmpdir)

            # Create common.fdl
            common_fdl = tmpdir / "common.fdl"
            common_fdl.write_text('''
            package common;

            message Address [id=100] {
                string street = 1;
            }
            ''')

            # Create main.fdl that uses Address
            main_fdl = tmpdir / "main.fdl"
            main_fdl.write_text('''
            package main;
            import "common.fdl";

            message User [id=101] {
                string name = 1;
                Address address = 2;
            }
            ''')

            schema = resolve_imports(main_fdl)
            errors = schema.validate()

            # Should have no errors - Address is imported
            assert len(errors) == 0

    def test_invalid_type_reference_without_import(self):
        """Test that missing types are detected."""
        source = '''
        package main;

        message User [id=100] {
            string name = 1;
            Address address = 2;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()
        errors = schema.validate()

        # Should have error - Address is not defined
        assert len(errors) == 1
        assert "Unknown type 'Address'" in errors[0]


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
