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

from pathlib import Path

from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.swift import SwiftGenerator
from fory_compiler.ir.validator import SchemaValidator


def parse_schema(source: str):
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    return schema


def generate_swift(source: str) -> str:
    schema = parse_schema(source)
    generator = SwiftGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    files = generator.generate()
    assert len(files) == 1
    return files[0].content


def test_swift_generator_emits_field_ids_and_encodings():
    source = """
    package demo;

    message Scalar [id=100] {
        fixed_int32 fixed_value = 1;
        int32 varint_value = 2;
        tagged_uint64 tagged_value = 3;
    }
    """
    content = generate_swift(source)
    assert "public enum Demo" in content
    assert "@ForyField(id: 1, encoding: .fixed)" in content
    assert "@ForyField(id: 2)" in content
    assert "@ForyField(id: 3, encoding: .tagged)" in content
    assert "fory.register(Demo.Scalar.self, id: 100)" in content


def test_swift_generator_emits_tagged_union_case_ids():
    source = """
    package demo;

    message Node [id=100] {
        string id = 1;
    }

    union Animal [id=101] {
        Node node = 3;
        string note = 7;
    }
    """
    content = generate_swift(source)
    assert "public enum Animal: Equatable" in content
    assert "@ForyField(id: 3)" in content
    assert "case node(Demo.Node)" in content
    assert "@ForyField(id: 7)" in content
    assert "case note(String)" in content
    assert "fory.register(Demo.Animal.self, id: 101)" in content


def test_swift_generator_uses_class_for_ref_targets_and_weak_fields():
    source = """
    package tree;

    message TreeNode [id=2251833438] {
        string id = 1;
        list<ref TreeNode> children = 2;
        ref(weak=true) TreeNode parent = 3;
    }

    message TreeRoot [id=2251833439] {
        TreeNode root = 1;
    }
    """
    content = generate_swift(source)
    assert "public final class TreeNode" in content
    assert "public weak var parent: Tree.TreeNode?" in content
    assert "public var children: [Tree.TreeNode] = []" in content
    assert "public struct TreeRoot" in content


def test_swift_generator_output_path_uses_package_segments():
    source = """
    package demo.foo;

    message User [id=1] {
        string name = 1;
    }
    """
    schema = parse_schema(source)
    generator = SwiftGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    generated = generator.generate()[0]
    assert generated.path == "demo/foo/demo_foo.swift"


def test_swift_generator_uses_package_leaf_when_source_stem_differs():
    source = """
    package any_example_pb;

    message AnyInner [id=300] {
        string name = 1;
    }
    """
    schema = parse_schema(source)
    schema.source_file = "/tmp/any_example.proto"
    generator = SwiftGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    generated = generator.generate()[0]
    assert generated.path == "any_example_pb/any_example_pb.swift"


def test_swift_generator_renames_namespace_when_colliding_with_type_name():
    source = """
    package graph;

    message Node [id=1] {
        string id = 1;
    }

    message Graph [id=2] {
        list<Node> nodes = 1;
    }
    """
    content = generate_swift(source)
    assert "public enum GraphNamespace" in content
    assert "public struct Graph" in content
    assert "public var nodes: [GraphNamespace.Node] = []" in content


def test_swift_generator_no_namespace_wrapper_when_package_empty():
    source = """
    message User [id=1] {
        string name = 1;
    }
    """
    content = generate_swift(source)
    assert "public enum Generated" not in content
    assert "public struct User" in content
    assert "try ForyRegistration.getFory().serialize(self)" in content
    assert "fory.register(User.self, id: 1)" in content


def test_swift_generator_supports_flatten_namespace_style_option():
    source = """
    package demo.foo;
    option swift_namespace_style = "flatten";

    message User [id=1] {
        string name = 1;
    }

    message Group [id=2] {
        User user = 1;
    }
    """
    content = generate_swift(source)
    assert "public enum Demo {" not in content
    assert "public struct Demo_Foo_User" in content
    assert "public struct Demo_Foo_Group" in content
    assert "public var user: Demo_Foo_User" in content
    assert "public enum Demo_Foo_ForyRegistration" in content
    assert "try Demo_Foo_ForyRegistration.getFory().serialize(self)" in content


def test_swift_generator_supports_explicit_enum_namespace_style_option():
    source = """
    package demo.foo;
    option swift_namespace_style = "enum";

    message User [id=1] {
        string name = 1;
    }
    """
    content = generate_swift(source)
    assert "public enum Demo {" in content
    assert "public struct User" in content
