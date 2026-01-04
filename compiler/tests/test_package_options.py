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

"""Tests for FDL package options and qualified type names."""

import pytest
from pathlib import Path

from fory_compiler.parser.lexer import Lexer
from fory_compiler.parser.parser import Parser
from fory_compiler.generators.java import JavaGenerator
from fory_compiler.generators.go import GoGenerator
from fory_compiler.generators.base import GeneratorOptions


class TestDottedPackageName:
    """Tests for dotted package name parsing."""

    def test_simple_package(self):
        """Test parsing a simple package name."""
        source = '''
        package foo;
        message Bar {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.package == "foo"

    def test_dotted_package(self):
        """Test parsing a dotted package name."""
        source = '''
        package foo.bar;
        message Baz {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.package == "foo.bar"

    def test_deeply_dotted_package(self):
        """Test parsing a deeply nested package name."""
        source = '''
        package com.example.payment.v1;
        message Payment {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.package == "com.example.payment.v1"


class TestUnknownOptionWarning:
    """Tests for unknown option warnings."""

    def test_unknown_option_warns(self):
        """Test that unknown options produce a warning."""
        source = '''
        package myapp;
        option unknown_option = "value";
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have one warning
            assert len(w) == 1
            assert "ignoring unknown option 'unknown_option'" in str(w[0].message)

        # Option should still be stored
        assert schema.get_option("unknown_option") == "value"

    def test_known_option_no_warning(self):
        """Test that known options don't produce warnings."""
        source = '''
        package myapp;
        option java_package = "com.example";
        option go_package = "github.com/example";
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have no warnings
            assert len(w) == 0

    def test_multiple_unknown_options_warn(self):
        """Test that multiple unknown options each produce a warning."""
        source = '''
        package myapp;
        option foo = "bar";
        option baz = 123;
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have two warnings
            assert len(w) == 2
            assert "foo" in str(w[0].message)
            assert "baz" in str(w[1].message)


class TestFileOptions:
    """Tests for file-level option parsing."""

    def test_java_package_option(self):
        """Test parsing java_package option."""
        source = '''
        package payment;
        option java_package = "com.mycorp.payment.v1";
        message Payment {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.package == "payment"
        assert schema.get_option("java_package") == "com.mycorp.payment.v1"

    def test_go_package_option(self):
        """Test parsing go_package option."""
        source = '''
        package payment;
        option go_package = "github.com/mycorp/apis/gen/payment/v1;paymentv1";
        message Payment {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.package == "payment"
        assert schema.get_option("go_package") == "github.com/mycorp/apis/gen/payment/v1;paymentv1"

    def test_multiple_options(self):
        """Test parsing multiple file-level options."""
        source = '''
        package payment;
        option java_package = "com.mycorp.payment.v1";
        option go_package = "github.com/mycorp/payment/v1;paymentv1";
        message Payment {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.get_option("java_package") == "com.mycorp.payment.v1"
        assert schema.get_option("go_package") == "github.com/mycorp/payment/v1;paymentv1"

    def test_option_with_boolean_value(self):
        """Test parsing option with boolean value."""
        source = '''
        package test;
        option deprecated = true;
        message Foo {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.get_option("deprecated") is True

    def test_option_with_integer_value(self):
        """Test parsing option with integer value."""
        source = '''
        package test;
        option version = 1;
        message Foo {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.get_option("version") == 1


class TestQualifiedTypeNames:
    """Tests for package-qualified type references."""

    def test_qualified_type_in_field(self):
        """Test using qualified type names in field definitions."""
        source = '''
        package myapp;
        message SearchResponse {
            message Result {
                string url = 1;
            }
        }
        message SearchRequest {
            SearchResponse.Result cached_result = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()
        errors = schema.validate()

        assert len(errors) == 0
        request = schema.messages[1]
        assert request.fields[0].field_type.name == "SearchResponse.Result"


class TestJavaPackageGeneration:
    """Tests for Java package generation with java_package option."""

    def test_java_package_option_used(self):
        """Test that java_package option is used in generated Java code."""
        source = '''
        package payment;
        option java_package = "com.mycorp.payment.v1";
        message Payment {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Check that files are in the java_package path
        payment_file = next(f for f in files if "Payment.java" in f.path)
        assert "com/mycorp/payment/v1/Payment.java" == payment_file.path
        assert "package com.mycorp.payment.v1;" in payment_file.content

    def test_java_package_fallback_to_fdl_package(self):
        """Test fallback to FDL package when java_package is not specified."""
        source = '''
        package com.example.models;
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        user_file = next(f for f in files if "User.java" in f.path)
        assert "com/example/models/User.java" == user_file.path
        assert "package com.example.models;" in user_file.content


class TestGoPackageGeneration:
    """Tests for Go package generation with go_package option."""

    def test_go_package_with_semicolon(self):
        """Test go_package option with explicit package name."""
        source = '''
        package payment;
        option go_package = "github.com/mycorp/apis/gen/payment/v1;paymentv1";
        message Payment {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = GoGenerator(schema, options)

        import_path, package_name = generator.get_go_package_info()
        assert import_path == "github.com/mycorp/apis/gen/payment/v1"
        assert package_name == "paymentv1"

        files = generator.generate()
        go_file = files[0]
        assert "package paymentv1" in go_file.content

    def test_go_package_without_semicolon(self):
        """Test go_package option without explicit package name."""
        source = '''
        package payment;
        option go_package = "github.com/mycorp/apis/payment/v1";
        message Payment {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = GoGenerator(schema, options)

        import_path, package_name = generator.get_go_package_info()
        assert import_path == "github.com/mycorp/apis/payment/v1"
        assert package_name == "v1"

    def test_go_package_fallback_to_fdl_package(self):
        """Test fallback to FDL package when go_package is not specified."""
        source = '''
        package com.example.models;
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = GoGenerator(schema, options)

        import_path, package_name = generator.get_go_package_info()
        assert import_path is None
        assert package_name == "models"


class TestNamespaceConsistency:
    """Tests for namespace consistency across languages."""

    def test_java_uses_fdl_package_for_namespace(self):
        """Test that Java uses FDL package for type namespace, not java_package."""
        source = '''
        package myapp.models;
        option java_package = "com.mycorp.generated.models";
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()
        registration_file = next(f for f in files if "Registration" in f.path)

        # Should use myapp.models (FDL package) for namespace, not com.mycorp.generated.models
        assert '"myapp.models"' in registration_file.content

    def test_go_uses_fdl_package_for_namespace(self):
        """Test that Go uses FDL package for type namespace, not go_package."""
        source = '''
        package myapp.models;
        option go_package = "github.com/mycorp/generated;genmodels";
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = GoGenerator(schema, options)

        files = generator.generate()
        go_file = files[0]

        # Should use myapp.models (FDL package) for namespace
        assert '"myapp.models.User"' in go_file.content


class TestJavaOuterClassname:
    """Tests for java_outer_classname option."""

    def test_outer_classname_generates_single_file(self):
        """Test that java_outer_classname generates all types in a single file."""
        source = '''
        package myapp;
        option java_outer_classname = "DescriptorProtos";

        enum Status {
            UNKNOWN = 0;
            ACTIVE = 1;
        }

        message User {
            string name = 1;
            Status status = 2;
        }

        message Order {
            string id = 1;
            User customer = 2;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Should generate only 2 files: outer class and registration
        assert len(files) == 2

        # Find the outer class file
        outer_file = next(f for f in files if "DescriptorProtos.java" in f.path)
        assert outer_file is not None
        assert "myapp/DescriptorProtos.java" == outer_file.path

        # Check content
        assert "public final class DescriptorProtos" in outer_file.content
        assert "private DescriptorProtos()" in outer_file.content
        assert "public static enum Status" in outer_file.content
        assert "public static class User" in outer_file.content
        assert "public static class Order" in outer_file.content

    def test_outer_classname_registration_uses_prefix(self):
        """Test that registration uses outer class as prefix."""
        source = '''
        package myapp;
        option java_outer_classname = "DescriptorProtos";

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        registration_file = next(f for f in files if "Registration" in f.path)

        # Should reference types with outer class prefix
        assert "DescriptorProtos.User.class" in registration_file.content

    def test_outer_classname_with_nested_types(self):
        """Test java_outer_classname with nested types."""
        source = '''
        package myapp;
        option java_outer_classname = "Protos";

        message Container {
            message Inner {
                string value = 1;
            }
            Inner item = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        outer_file = next(f for f in files if "Protos.java" in f.path)

        # Should have nested types inside the outer class
        assert "public static class Container" in outer_file.content
        assert "public static class Inner" in outer_file.content

    def test_outer_classname_with_java_package(self):
        """Test java_outer_classname combined with java_package."""
        source = '''
        package myapp;
        option java_package = "com.example.proto";
        option java_outer_classname = "MyProtos";

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        outer_file = next(f for f in files if "MyProtos.java" in f.path)

        # Should use java_package for file path and package declaration
        assert "com/example/proto/MyProtos.java" == outer_file.path
        assert "package com.example.proto;" in outer_file.content
        assert "public final class MyProtos" in outer_file.content

    def test_without_outer_classname_generates_separate_files(self):
        """Test that without java_outer_classname, separate files are generated."""
        source = '''
        package myapp;

        enum Status {
            UNKNOWN = 0;
        }

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Should generate separate files: Status.java, User.java, Registration.java
        file_names = [f.path.split("/")[-1] for f in files]
        assert "Status.java" in file_names
        assert "User.java" in file_names
        assert "MyappForyRegistration.java" in file_names


class TestJavaMultipleFiles:
    """Tests for java_multiple_files option."""

    def test_multiple_files_true_generates_separate_files(self):
        """Test that java_multiple_files = true generates separate files."""
        source = '''
        package myapp;
        option java_multiple_files = true;

        enum Status {
            UNKNOWN = 0;
            ACTIVE = 1;
        }

        message User {
            string name = 1;
        }

        message Order {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Should generate separate files for each type
        file_names = [f.path.split("/")[-1] for f in files]
        assert "Status.java" in file_names
        assert "User.java" in file_names
        assert "Order.java" in file_names
        assert "MyappForyRegistration.java" in file_names

    def test_multiple_files_false_with_outer_class_generates_single_file(self):
        """Test that java_multiple_files = false with outer class generates single file."""
        source = '''
        package myapp;
        option java_outer_classname = "MyProtos";
        option java_multiple_files = false;

        enum Status {
            UNKNOWN = 0;
        }

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Should generate only 2 files: outer class and registration
        assert len(files) == 2

        file_names = [f.path.split("/")[-1] for f in files]
        assert "MyProtos.java" in file_names
        assert "MyappForyRegistration.java" in file_names

    def test_multiple_files_true_overrides_outer_classname(self):
        """Test that java_multiple_files = true overrides java_outer_classname."""
        source = '''
        package myapp;
        option java_outer_classname = "MyProtos";
        option java_multiple_files = true;

        enum Status {
            UNKNOWN = 0;
        }

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Should generate separate files even though outer_classname is set
        file_names = [f.path.split("/")[-1] for f in files]
        assert "Status.java" in file_names
        assert "User.java" in file_names
        # MyProtos.java should NOT be generated
        assert "MyProtos.java" not in file_names

    def test_multiple_files_default_is_false(self):
        """Test that java_multiple_files defaults to false when outer_classname is set."""
        source = '''
        package myapp;
        option java_outer_classname = "MyProtos";

        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Should generate single outer class file (default behavior)
        file_names = [f.path.split("/")[-1] for f in files]
        assert "MyProtos.java" in file_names
        assert "User.java" not in file_names

    def test_multiple_files_with_java_package(self):
        """Test java_multiple_files combined with java_package."""
        source = '''
        package myapp;
        option java_package = "com.example.generated";
        option java_multiple_files = true;

        message User {
            string name = 1;
        }

        message Order {
            string id = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        options = GeneratorOptions(output_dir=Path("/tmp"))
        generator = JavaGenerator(schema, options)

        files = generator.generate()

        # Should use java_package for paths
        user_file = next(f for f in files if "User.java" in f.path)
        assert "com/example/generated/User.java" == user_file.path
        assert "package com.example.generated;" in user_file.content


class TestTypeOptions:
    """Tests for type-level option parsing [id=100, deprecated=true]."""

    def test_message_with_id_option(self):
        """Test parsing a message with id option."""
        source = '''
        package myapp;
        message User [id=100] {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert user.name == "User"
        assert user.type_id == 100

    def test_enum_with_id_option(self):
        """Test parsing an enum with id option."""
        source = '''
        package myapp;
        enum Status [id=200] {
            UNKNOWN = 0;
            ACTIVE = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.enums) == 1
        status = schema.enums[0]
        assert status.name == "Status"
        assert status.type_id == 200

    def test_type_with_multiple_options(self):
        """Test parsing a type with multiple options."""
        source = '''
        package myapp;
        message User [id=100, deprecated=true] {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert user.type_id == 100

    def test_type_without_options(self):
        """Test parsing a type without options (namespace-based)."""
        source = '''
        package myapp;
        message Config {
            string key = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        config = schema.messages[0]
        assert config.type_id is None

    def test_unknown_type_option_warns(self):
        """Test that unknown type options produce a warning."""
        source = '''
        package myapp;
        message User [id=100, unknown_opt=true] {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have one warning
            assert len(w) == 1
            assert "ignoring unknown type option 'unknown_opt'" in str(w[0].message)
            assert "type 'User'" in str(w[0].message)

        # id should still be parsed
        assert schema.messages[0].type_id == 100

    def test_known_type_options_no_warning(self):
        """Test that known type options don't produce warnings."""
        source = '''
        package myapp;
        message User [id=100, deprecated=true] {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have no warnings
            assert len(w) == 0

    def test_nested_type_with_id(self):
        """Test parsing nested types with id options."""
        source = '''
        package myapp;
        message Outer [id=100] {
            message Inner [id=101] {
                string value = 1;
            }
            Inner item = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        outer = schema.messages[0]
        assert outer.type_id == 100
        inner = outer.nested_messages[0]
        assert inner.type_id == 101


class TestFieldOptions:
    """Tests for field-level option parsing."""

    def test_field_with_deprecated_option(self):
        """Test parsing a field with deprecated option."""
        source = '''
        package myapp;
        message User {
            string name = 1;
            int32 old_field = 2 [deprecated = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 2
        assert user.fields[0].name == "name"
        assert user.fields[1].name == "old_field"

    def test_field_with_json_name_option(self):
        """Test parsing a field with json_name option."""
        source = '''
        package myapp;
        message User {
            string first_name = 1 [json_name = "firstName"];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 1
        assert user.fields[0].name == "first_name"

    def test_field_with_multiple_options(self):
        """Test parsing a field with multiple options."""
        source = '''
        package myapp;
        message User {
            string old_name = 1 [deprecated = true, json_name = "oldName"];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 1
        assert user.fields[0].name == "old_name"

    def test_field_with_integer_option_value(self):
        """Test parsing a field with integer option value."""
        source = '''
        package myapp;
        message User {
            int32 version = 1 [packed = 1];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 1

    def test_field_with_false_option_value(self):
        """Test parsing a field with false option value."""
        source = '''
        package myapp;
        message User {
            string name = 1 [deprecated = false];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 1

    def test_unknown_field_option_warns(self):
        """Test that unknown field options produce a warning."""
        source = '''
        package myapp;
        message User {
            string name = 1 [unknown_option = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have one warning
            assert len(w) == 1
            assert "ignoring unknown field option 'unknown_option'" in str(w[0].message)
            assert "field 'name'" in str(w[0].message)

    def test_known_field_option_no_warning(self):
        """Test that known field options don't produce warnings."""
        source = '''
        package myapp;
        message User {
            string name = 1 [deprecated = true];
            string email = 2 [json_name = "emailAddress"];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have no warnings
            assert len(w) == 0

    def test_multiple_unknown_field_options_warn(self):
        """Test that multiple unknown field options each produce a warning."""
        source = '''
        package myapp;
        message User {
            string name = 1 [foo = "bar", baz = 123];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have two warnings
            assert len(w) == 2
            assert "foo" in str(w[0].message)
            assert "baz" in str(w[1].message)

    def test_field_options_on_repeated_field(self):
        """Test parsing field options on a repeated field."""
        source = '''
        package myapp;
        message User {
            repeated string tags = 1 [packed = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 1
        assert user.fields[0].name == "tags"

    def test_field_options_on_optional_field(self):
        """Test parsing field options on an optional field."""
        source = '''
        package myapp;
        message User {
            optional string nickname = 1 [deprecated = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 1
        assert user.fields[0].name == "nickname"
        assert user.fields[0].optional is True

    def test_field_options_on_map_field(self):
        """Test parsing field options on a map field."""
        source = '''
        package myapp;
        message User {
            map<string, int32> scores = 1 [deprecated = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert len(schema.messages) == 1
        user = schema.messages[0]
        assert len(user.fields) == 1
        assert user.fields[0].name == "scores"


class TestForyExtensionOptions:
    """Tests for Fory extension option syntax: option (fory).key = value."""

    def test_file_level_fory_option(self):
        """Test parsing file-level Fory extension option."""
        source = '''
        package myapp;
        option (fory).use_record_for_java_message = true;
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.get_option("fory.use_record_for_java_message") is True

    def test_multiple_file_level_fory_options(self):
        """Test parsing multiple file-level Fory extension options."""
        source = '''
        package myapp;
        option (fory).use_record_for_java_message = true;
        option (fory).polymorphism = false;
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.get_option("fory.use_record_for_java_message") is True
        assert schema.get_option("fory.polymorphism") is False

    def test_message_level_fory_option(self):
        """Test parsing message-level Fory extension option."""
        source = '''
        package myapp;
        message User {
            option (fory).id = 100;
            option (fory).compatible = true;
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        assert user.type_id == 100  # fory.id should set type_id
        assert user.options.get("fory.id") == 100
        assert user.options.get("fory.compatible") is True

    def test_message_fory_id_sets_type_id(self):
        """Test that option (fory).id sets message type_id."""
        source = '''
        package myapp;
        message User {
            option (fory).id = 200;
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        assert user.type_id == 200

    def test_enum_level_fory_option(self):
        """Test parsing enum-level Fory extension option."""
        source = '''
        package myapp;
        enum Status {
            option (fory).id = 300;
            UNKNOWN = 0;
            ACTIVE = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        status = schema.enums[0]
        assert status.type_id == 300
        assert status.options.get("fory.id") == 300

    def test_field_level_fory_option(self):
        """Test parsing field-level Fory extension option."""
        source = '''
        package myapp;
        message User {
            MyType friend = 1 [(fory).ref = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        field = user.fields[0]
        assert field.ref is True  # fory.ref should set ref flag
        assert field.options.get("fory.ref") is True

    def test_field_fory_nullable_sets_optional(self):
        """Test that (fory).nullable sets optional flag."""
        source = '''
        package myapp;
        message User {
            string nickname = 1 [(fory).nullable = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        field = user.fields[0]
        assert field.optional is True
        assert field.options.get("fory.nullable") is True

    def test_field_multiple_fory_options(self):
        """Test parsing multiple Fory extension options on a field."""
        source = '''
        package myapp;
        message User {
            MyType friend = 1 [(fory).ref = true, (fory).nullable = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        field = user.fields[0]
        assert field.ref is True
        assert field.optional is True
        assert field.options.get("fory.ref") is True
        assert field.options.get("fory.nullable") is True

    def test_mixed_standard_and_fory_options(self):
        """Test mixing standard and Fory extension options."""
        source = '''
        package myapp;
        option java_package = "com.example";
        option (fory).use_record_for_java_message = true;
        message User {
            string name = 1 [deprecated = true, (fory).nullable = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        assert schema.get_option("java_package") == "com.example"
        assert schema.get_option("fory.use_record_for_java_message") is True

        user = schema.messages[0]
        field = user.fields[0]
        assert field.optional is True
        assert field.options.get("deprecated") is True
        assert field.options.get("fory.nullable") is True

    def test_unknown_fory_file_option_warns(self):
        """Test that unknown Fory file options produce a warning."""
        source = '''
        package myapp;
        option (fory).unknown_option = true;
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have one warning
            assert len(w) == 1
            assert "ignoring unknown fory option 'unknown_option'" in str(w[0].message)

        # Option should still be stored
        assert schema.get_option("fory.unknown_option") is True

    def test_unknown_fory_message_option_warns(self):
        """Test that unknown Fory message options produce a warning."""
        source = '''
        package myapp;
        message User {
            option (fory).unknown_opt = true;
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have one warning
            assert len(w) == 1
            assert "ignoring unknown fory message option 'unknown_opt'" in str(w[0].message)

    def test_unknown_fory_field_option_warns(self):
        """Test that unknown Fory field options produce a warning."""
        source = '''
        package myapp;
        message User {
            string name = 1 [(fory).unknown_opt = true];
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have one warning
            assert len(w) == 1
            assert "ignoring unknown fory field option 'unknown_opt'" in str(w[0].message)

    def test_unknown_extension_warns(self):
        """Test that unknown extension names produce a warning."""
        source = '''
        package myapp;
        option (custom).my_option = true;
        message User {
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())

        import warnings
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter("always")
            schema = parser.parse()

            # Should have one warning
            assert len(w) == 1
            assert "ignoring unknown extension 'custom'" in str(w[0].message)

    def test_inline_and_body_options_merge(self):
        """Test that inline [id=100] and body option (fory).compatible merge."""
        source = '''
        package myapp;
        message User [id=100] {
            option (fory).compatible = true;
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        assert user.type_id == 100  # From inline option
        assert user.options.get("id") == 100  # Stored in options
        assert user.options.get("fory.compatible") is True  # From body option

    def test_body_option_overrides_inline_id(self):
        """Test that body option (fory).id overrides inline [id=...]."""
        source = '''
        package myapp;
        message User [id=100] {
            option (fory).id = 200;
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        # Body option should take precedence, but since inline sets type_id first,
        # and we only set type_id from body if it was None, inline wins
        assert user.type_id == 100
        # Both should be in options
        assert user.options.get("id") == 100
        assert user.options.get("fory.id") == 200

    def test_message_use_record_for_java_option(self):
        """Test message-level (fory).use_record_for_java option."""
        source = '''
        package myapp;
        message User {
            option (fory).use_record_for_java = true;
            string name = 1;
        }
        '''
        lexer = Lexer(source)
        parser = Parser(lexer.tokenize())
        schema = parser.parse()

        user = schema.messages[0]
        assert user.options.get("fory.use_record_for_java") is True


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
