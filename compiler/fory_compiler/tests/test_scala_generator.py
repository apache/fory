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

import pytest

from fory_compiler.cli import resolve_imports
from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.scala import ScalaGenerator
from fory_compiler.ir.validator import SchemaValidator


def generate_scala(source: str):
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    generator = ScalaGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    return {item.path: item.content for item in generator.generate()}


def test_scala_generator_emits_case_classes_options_enums_and_unions():
    files = generate_scala(
        """
        package demo;

        enum Status [id=101] {
            STATUS_UNKNOWN = 0;
            STATUS_OK = 7;
        }

        message User [id=102] {
            string name = 1;
            optional int32 age = 2;
            list<string> tags = 3;
        }

        union SearchTarget [id=103] {
            User user = 0;
            string note = 1;
        }
        """
    )

    user = files["demo/User.scala"]
    assert "final case class User(" in user
    assert "@ForyField(id = 1) name: String" in user
    assert "@ForyField(id = 2) age: Option[Int]" in user
    assert "@ForyField(id = 3) tags: List[String]" in user
    assert "derives ForySerializer" in user

    status = files["demo/Status.scala"]
    assert "enum Status {" in status
    assert "@ForyEnumId(0)" in status
    assert "case Unknown" in status
    assert "@ForyEnumId(7)" in status
    assert "case Ok" in status
    assert "ForyScalaEnum" not in status

    union = files["demo/SearchTarget.scala"]
    assert "@ForyUnion" in union
    assert "import org.apache.fory.`type`.union.UnknownCase" in union
    assert "enum SearchTarget derives ForySerializer" in union
    assert "@ForyUnknownCase" in union
    assert "case Unknown(value: UnknownCase)" in union
    assert "@ForyCase(id = 0)" in union
    assert "case User(value: _root_.demo.User)" in union
    assert "@ForyCase(id = 1)" in union
    assert "case Note(value: String)" in union


def test_default_package_union_conflict_uses_case_suffix():
    files = generate_scala(
        """
        message Dog {
            string name = 1;
        }

        union Animal {
            Dog dog = 1;
        }
        """
    )

    union = files["Animal.scala"]
    assert "case DogCase(value: Dog)" in union
    assert "case Dog(value: Dog)" not in union


def test_scala_generator_uses_mutable_normal_class_for_construction_cycles():
    files = generate_scala(
        """
        package graph;

        message Node [id=110] {
            string id = 1;
            ref Node parent = 2;
        }
        """
    )

    node = files["graph/Node.scala"]
    assert "final class Node() derives ForySerializer" in node
    assert 'var id: String = ""' in node
    assert "@Ref\n    @ForyField(id = 2)\n    var parent: Option[Node] = None" in node
    assert "Option[Node @Ref]" not in node


def test_scala_generator_uses_mutable_normal_class_for_nested_construction_cycles():
    files = generate_scala(
        """
        package graph;

        message Envelope [id=120] {
            message Node [id=121] {
                string id = 1;
                ref Node parent = 2;
            }

            Node root = 1;
        }
        """
    )

    envelope = files["graph/Envelope.scala"]
    assert "final case class Envelope(" in envelope
    assert "object Envelope {" in envelope
    assert "final class Node() derives ForySerializer" in envelope
    assert 'var id: String = ""' in envelope
    assert (
        "@Ref\n        @ForyField(id = 2)\n        var parent: Option[Envelope.Node] = None"
        in envelope
    )
    assert "Option[Envelope.Node @Ref]" not in envelope


def test_scala_generator_keeps_container_recursive_messages_as_case_classes():
    files = generate_scala(
        """
        package graph;

        message Node [id=125] {
            string id = 1;
            list<ref Node> children = 2;
            map<string, Node> lookup = 3;
        }
        """
    )

    node = files["graph/Node.scala"]
    assert "final case class Node(" in node
    assert "children: List[Node @Ref]" in node
    assert "lookup: Map[String, Node]" in node
    assert "final class Node() derives ForySerializer" not in node


def test_scala_generator_marks_container_cycle_with_constructor_edge_mutable():
    files = generate_scala(
        """
        package graph;

        message Node [id=126] {
            string id = 1;
            list<ref Edge> edges = 2;
        }

        message Edge [id=127] {
            string id = 1;
            ref Node owner = 2;
        }
        """
    )

    node = files["graph/Node.scala"]
    edge = files["graph/Edge.scala"]
    assert "final class Node() derives ForySerializer" in node
    assert "var edges: List[Edge @Ref] = List.empty" in node
    assert "final class Edge() derives ForySerializer" in edge
    assert "@Ref\n    @ForyField(id = 2)\n    var owner: Option[Node] = None" in edge
    assert "Option[Node @Ref]" not in edge


def test_scala_generator_marks_nested_owner_child_cycles_mutable():
    files = generate_scala(
        """
        package graph;

        message Envelope [id=130] {
            message Node [id=131] {
                string id = 1;
                ref Envelope owner = 2;
            }

            Node root = 1;
        }
        """
    )

    envelope = files["graph/Envelope.scala"]
    assert "final class Envelope() derives ForySerializer" in envelope
    assert "var root: Option[Envelope.Node] = None" in envelope
    assert "final class Node() derives ForySerializer" in envelope
    assert (
        "@Ref\n        @ForyField(id = 2)\n        var owner: Option[Envelope] = None"
        in envelope
    )
    assert "Option[Envelope @Ref]" not in envelope


def test_scala_generator_marks_union_mediated_cycles_mutable():
    files = generate_scala(
        """
        package graph;

        message Node [id=140] {
            string id = 1;
            ref Choice choice = 2;
        }

        union Choice [id=141] {
            Node node = 1;
        }
        """
    )

    node = files["graph/Node.scala"]
    assert "final class Node() derives ForySerializer" in node
    assert 'var id: String = ""' in node
    assert "@Ref\n    @ForyField(id = 2)\n    var choice: Choice = null" in node
    assert "Choice @Ref" not in node


def test_scala_generator_collects_nested_union_payload_imports():
    files = generate_scala(
        """
        package demo;

        message Envelope [id=150] {
            message User [id=151] {
                string name = 1;
            }

            union Target [id=152] {
                fixed int32 fixed_id = 1;
                list<ref User> users = 2;
            }

            Target target = 1;
        }
        """
    )

    envelope = files["demo/Envelope.scala"]
    assert "import org.apache.fory.annotation.Int32Type" in envelope
    assert "import org.apache.fory.annotation.Ref" in envelope
    assert "import org.apache.fory.config.Int32Encoding" in envelope
    assert (
        "case FixedId(value: Int @Int32Type(encoding = Int32Encoding.FIXED))"
        in envelope
    )
    assert "case Users(value: List[Envelope.User @Ref])" in envelope


def test_scala_generator_marks_nested_union_mediated_cycles_mutable():
    files = generate_scala(
        """
        package graph;

        message Envelope [id=150] {
            union Choice [id=151] {
                Node node = 1;
            }

            message Node [id=152] {
                string id = 1;
                ref Choice choice = 2;
            }

            Node root = 1;
        }
        """
    )

    envelope = files["graph/Envelope.scala"]
    assert "final case class Envelope(" in envelope
    assert "@ForyField(id = 1) root: Option[Envelope.Node]" in envelope
    assert "enum Choice derives ForySerializer" in envelope
    assert "case Node(value: Envelope.Node)" in envelope
    assert "final class Node() derives ForySerializer" in envelope
    assert 'var id: String = ""' in envelope
    assert (
        "@Ref\n        @ForyField(id = 2)\n        var choice: Envelope.Choice = null"
        in envelope
    )
    assert "Envelope.Choice @Ref" not in envelope


def test_scala_generator_resolves_shadowed_nested_types_before_top_level_types():
    files = generate_scala(
        """
        package graph;

        message Node {
            string label = 1;
        }

        message Envelope {
            message Node {
                string id = 1;
                ref Node parent = 2;
            }

            Node root = 1;
        }
        """
    )

    envelope = files["graph/Envelope.scala"]
    assert "final class Node() derives ForySerializer" in envelope
    assert (
        "@Ref\n        @ForyField(id = 2)\n        var parent: Option[Envelope.Node] = None"
        in envelope
    )
    assert "Option[Envelope.Node @Ref]" not in envelope
    assert "@ForyField(id = 1) root: Option[Envelope.Node]" in envelope


def test_scala_generator_does_not_make_cycles_from_shadowed_nested_enums():
    files = generate_scala(
        """
        package graph;

        message Node [id=160] {
            Envelope owner = 1;
        }

        message Envelope [id=161] {
            enum Node {
                UNKNOWN = 0;
                ACTIVE = 1;
            }

            Node kind = 1;
            list<Node> kinds = 2;
        }
        """
    )

    node = files["graph/Node.scala"]
    envelope = files["graph/Envelope.scala"]
    assert "final case class Node(" in node
    assert "final case class Envelope(" in envelope
    assert "@ForyField(id = 1) kind: Envelope.Node" in envelope
    assert "@ForyField(id = 2) kinds: List[Envelope.Node]" in envelope
    assert "List[Envelope.Node @Ref]" not in envelope


def test_scala_generator_uses_jvm_nested_names_for_name_registration():
    files = generate_scala(
        """
        option enable_auto_type_id = false;
        package demo;

        message Envelope {
            message Payload {
                int32 value = 1;
            }

            enum Kind {
                UNKNOWN = 0;
                ACTIVE = 1;
            }

            union Choice {
                Payload payload = 1;
                string note = 2;
            }

            Payload payload = 1;
            Kind kind = 2;
            Choice choice = 3;
        }
        """
    )

    envelope = files["demo/Envelope.scala"]
    assert "def toBytes(): Array[Byte] =" in envelope
    assert "DemoForyModule.getFory.serialize(this)" in envelope
    assert "def fromBytes(bytes: Array[Byte]): Envelope =" in envelope
    assert (
        "DemoForyModule.getFory.deserialize(bytes).asInstanceOf[Envelope]" in envelope
    )

    registration = files["demo/DemoForyModule.scala"]
    assert "object DemoForyModule extends org.apache.fory.ForyModule" in registration
    assert "ForyScala.builder()" in registration
    assert ".withModule(this)" in registration
    assert "private[demo] def getFory: ThreadSafeFory = fory" in registration
    assert "override def install(fory: Fory): Unit" in registration
    assert (
        'ForySerializer.registerType(fory, classOf[Envelope], "demo", "Envelope")'
        in registration
    )
    assert (
        'ForySerializer.registerType(fory, classOf[Envelope.Payload], "demo.Envelope", "Payload")'
        in registration
    )
    assert (
        "ForySerializer.registerSerializer(fory, classOf[Envelope.Payload])"
        in registration
    )
    assert (
        'ScalaSerializers.registerEnum(fory, classOf[Envelope.Kind], "demo.Envelope", "Kind")'
        in registration
    )
    assert (
        'ForySerializer.register(fory, classOf[Envelope.Choice], "demo.Envelope", "Choice")'
        in registration
    )
    assert "ForySerializer.registerSerializer(fory, classOf[Envelope])" in registration


def test_scala_default_package_helper_runtime():
    files = generate_scala(
        """
        message User [id=200] {
            string name = 1;
        }
        """
    )

    user = files["User.scala"]
    assert "ForyModule.getFory.serialize(this)" in user
    registration = files["ForyModule.scala"]
    assert "def getFory: ThreadSafeFory = fory" in registration
    assert "private def getFory" not in registration


def test_scala_generator_pre_registers_message_type_graph_before_serializers():
    files = generate_scala(
        """
        package graph;

        message Node {
            list<ref Edge> edges = 1;
        }

        message Edge {
            ref Node node = 1;
        }
        """
    )

    registration = files["graph/GraphForyModule.scala"]
    node_type = registration.index("ForySerializer.registerType(fory, classOf[Node]")
    edge_type = registration.index("ForySerializer.registerType(fory, classOf[Edge]")
    node_serializer = registration.index(
        "ForySerializer.registerSerializer(fory, classOf[Node])"
    )
    edge_serializer = registration.index(
        "ForySerializer.registerSerializer(fory, classOf[Edge])"
    )
    assert node_type < node_serializer
    assert edge_type < node_serializer
    assert node_type < edge_serializer
    assert edge_type < edge_serializer


def test_scala_generator_keeps_imported_types_in_owner_package():
    repo_root = Path(__file__).resolve().parents[3]
    idl_dir = repo_root / "integration_tests" / "idl_tests" / "idl"
    schema = resolve_imports(idl_dir / "root.idl", [idl_dir])
    generator = ScalaGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    files = {item.path: item.content for item in generator.generate()}

    assert "root/MultiHolder.scala" in files
    assert "root/PrimitiveTypes.scala" not in files
    assert "addressbook.AddressBook" in files["root/MultiHolder.scala"]
    assert "tree.TreeNode" in files["root/MultiHolder.scala"]

    registration = files["root/RootForyModule.scala"]
    assert "fory.register(addressbook.AddressbookForyModule)" in registration
    assert "fory.register(tree.TreeForyModule)" in registration
    assert "classOf[PrimitiveTypes]" not in registration


def test_scala_nested_union_imports_unknown_case():
    files = generate_scala(
        """
        package demo;

        message Envelope {
            union Detail {
                string note = 1;
            }
            Detail detail = 1;
        }
        """
    )

    envelope = files["demo/Envelope.scala"]
    assert "import org.apache.fory.`type`.union.UnknownCase" in envelope
    assert "case Unknown(value: UnknownCase)" in envelope


def test_scala_default_package_import_registers_dependency(tmp_path):
    common = tmp_path / "common.fdl"
    common.write_text(
        """
        message Common [id=200] {
            string name = 1;
        }
        """
    )
    main = tmp_path / "main.fdl"
    main.write_text(
        """
        import "common.fdl";

        message Holder [id=201] {
            Common common = 1;
        }
        """
    )

    schema = resolve_imports(main)
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    generator = ScalaGenerator(schema, GeneratorOptions(output_dir=tmp_path))
    files = {item.path: item.content for item in generator.generate()}

    module = files["MainForyModule.scala"]
    assert "object MainForyModule extends org.apache.fory.ForyModule" in module
    assert "fory.register(CommonForyModule)" in module
    assert "CommonForyModule.register(fory)" not in module


def test_scala_rejects_mixed_default_and_named_imports(tmp_path):
    default_dir = tmp_path / "default_in_named"
    default_dir.mkdir()
    (default_dir / "common.fdl").write_text(
        """
        message Common [id=200] {
            string name = 1;
        }
        """
    )
    default_main = default_dir / "main.fdl"
    default_main.write_text(
        """
        package app;
        import "common.fdl";

        message Holder [id=201] {
            Common common = 1;
        }
        """
    )

    with pytest.raises(ValueError, match="default-package"):
        ScalaGenerator(
            resolve_imports(default_main), GeneratorOptions(output_dir=tmp_path)
        )

    named_dir = tmp_path / "named_in_default"
    named_dir.mkdir()
    (named_dir / "common.fdl").write_text(
        """
        package shared;

        message Common [id=200] {
            string name = 1;
        }
        """
    )
    named_main = named_dir / "main.fdl"
    named_main.write_text(
        """
        import "common.fdl";

        message Holder [id=201] {
            optional Common common = 1;
        }
        """
    )

    with pytest.raises(ValueError, match="default-package"):
        ScalaGenerator(
            resolve_imports(named_main), GeneratorOptions(output_dir=tmp_path)
        )
