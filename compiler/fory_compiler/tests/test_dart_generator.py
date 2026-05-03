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

from fory_compiler.cli import resolve_imports
from fory_compiler.frontend.fdl.lexer import Lexer
from fory_compiler.frontend.fdl.parser import Parser
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators.dart import DartGenerator
from fory_compiler.ir.validator import SchemaValidator


def parse_schema(source: str):
    schema = Parser(Lexer(source).tokenize()).parse()
    validator = SchemaValidator(schema)
    assert validator.validate(), validator.errors
    return schema


def generate_dart(source: str):
    schema = parse_schema(source)
    generator = DartGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    files = generator.generate()
    assert len(files) == 1
    return files[0]


def test_dart_generator_emits_annotated_structs_and_generated_part_registration():
    file = generate_dart(
        """
        package demo;

        message Scalar [id=100] {
            fixed int32 fixed_value = 1;
            int32 varint_value = 2;
            tagged uint64 tagged_value = 3;
        }
        """
    )

    assert "part 'demo.fory.dart';" in file.content
    assert "@ForyStruct()" in file.content
    assert "final class Scalar {" in file.content
    assert (
        "@ForyField(type: Int32Type(encoding: Encoding.fixed), id: 1)" in file.content
    )
    assert (
        "@ForyField(type: Int32Type(encoding: Encoding.varint), id: 2)" in file.content
    )
    assert (
        "@ForyField(type: Uint64Type(encoding: Encoding.tagged), id: 3)" in file.content
    )
    assert "int fixedValue = 0;" in file.content
    assert "Uint64 taggedValue = Uint64(0);" in file.content
    assert (
        "DemoFory.register(fory, Scalar, id: registrationMode.id, namespace: registrationMode.namespace, typeName: registrationMode.typeName);"
        in file.content
    )
    assert "GeneratedStructRegistration<Scalar>" not in file.content
    assert "_ScalarForySerializer" not in file.content


def test_dart_generator_keeps_enum_helpers_in_source_and_uses_generated_enum_registration():
    file = generate_dart(
        """
        package demo;

        enum Status [id=101] {
            STATUS_UNKNOWN = 0;
            STATUS_OK = 7;
        }
        """
    )

    assert "enum Status {" in file.content
    assert "int get rawValue => switch (this) {" in file.content
    assert "Status.ok => 7," in file.content
    assert "static Status fromRawValue(int value) => switch (value) {" in file.content
    assert "_StatusForySerializer" not in file.content
    assert (
        "DemoFory.register(fory, Status, id: registrationMode.id, namespace: registrationMode.namespace, typeName: registrationMode.typeName);"
        in file.content
    )


def test_dart_generator_keeps_union_serializers_direct_and_marks_union_types():
    file = generate_dart(
        """
        package demo;

        message Node [id=100] {
            string id = 1;
        }

        union Animal [id=101] {
            Node node = 3;
            string note = 7;
        }
        """
    )

    assert "@ForyUnion()" in file.content
    assert "final class Animal {" in file.content
    assert "factory Animal.node(Node value)" in file.content
    assert (
        "final class _AnimalForySerializer extends UnionSerializer<Animal>"
        in file.content
    )
    assert "int caseId(Animal value) => value.caseId;" in file.content
    assert "Object? caseValue(Animal value) => value.value;" in file.content
    assert "Animal buildValue(int caseId, Object? value) {" in file.content
    assert "if (caseId == 3) return Animal.node(value as Node);" in file.content
    assert "if (caseId == 7) return Animal.note(value as String);" in file.content
    assert "void write(" not in file.content
    assert "Animal read(" not in file.content
    assert (
        "fory.registerSerializer(Animal, const _AnimalForySerializer(), id: registrationMode.id, namespace: registrationMode.namespace, typeName: registrationMode.typeName);"
        in file.content
    )


def test_dart_generator_distinguishes_lists_from_dense_arrays():
    file = generate_dart(
        """
        package demo;

        message Holder {
            list<int32> ints = 1;
            list<optional int32> nullable_ints = 2;
            optional list<int32> maybe_ints = 3;
            array<int32> dense_ints = 4;
        }

        union ValueUnion {
            array<uint32> values = 1;
        }
        """
    )

    assert "List<int> ints = <int>[];" in file.content
    assert (
        "@ForyField(type: ListType(element: Int32Type(encoding: Encoding.varint)), id: 2)"
        in file.content
    )
    assert "List<int?> nullableInts = <int?>[];" in file.content
    assert "List<int>? maybeInts = null;" in file.content
    assert "Int32List denseInts = Int32List(0);" in file.content
    assert "@ForyField(type: ArrayType(element: Int32Type()), id: 4)" in file.content
    assert "factory ValueUnion.values(Uint32List value)" in file.content


def test_dart_generator_supports_decimal_fields_and_unions():
    file = generate_dart(
        """
        package demo;

        message Money [id=100] {
            decimal amount = 1;
        }

        union ValueUnion [id=101] {
            decimal amount = 1;
            Money money = 2;
        }
        """
    )

    assert "Decimal amount = Decimal.zero();" in file.content
    assert "factory ValueUnion.amount(Decimal value)" in file.content


def test_dart_generator_emits_container_ref_annotations_for_builder_metadata():
    file = generate_dart(
        """
        package demo;

        message Node {
            list<ref Node> children = 1;
            map<string, ref Node> by_name = 2;
            ref Node parent = 3;
        }
        """
    )

    assert (
        "@ForyField(type: ListType(element: DeclaredType(ref: true)), id: 1)"
        in file.content
    )
    assert (
        "@ForyField(type: MapType(value: DeclaredType(ref: true)), id: 2)"
        in file.content
    )
    assert "@ForyField(id: 3, ref: true)" in file.content


def test_dart_generator_marks_map_value_ref_messages_as_ref_capable():
    file = generate_dart(
        """
        package demo;

        message Node {
            map<string, ref Node> by_name = 1;
        }
        """
    )

    assert (
        "@ForyField(type: MapType(value: DeclaredType(ref: true)), id: 1)"
        in file.content
    )
    assert "Map<String, Node> byName = <String, Node>{};" in file.content


def test_dart_generator_preserves_multi_word_enum_value_suffixes():
    file = generate_dart(
        """
        package demo;

        enum Status [id=101] {
            STATUS_SOME_MULTI_WORD = 0;
            STATUS_OK = 1;
        }
        """
    )

    assert "someMultiWord," in file.content
    assert "ok;" in file.content
    assert " word," not in file.content
    assert " word;" not in file.content


def test_dart_generator_flattens_nested_type_references_and_keeps_classes_final():
    file = generate_dart(
        """
        package demo;

        message Envelope {
            enum Status {
                STATUS_UNKNOWN = 0;
                STATUS_OK = 1;
            }

            message Payload {
                Status status = 1;
            }

            union Detail {
                Payload payload = 1;
                string note = 2;
            }

            Payload payload = 1;
            Detail detail = 2;
        }
        """
    )

    assert "enum Envelope_Status {" in file.content
    assert "final class Envelope_Payload {" in file.content
    assert "Envelope_Status status = Envelope_Status.unknown;" in file.content
    assert "@ForyUnion()" in file.content
    assert "final class Envelope_Detail {" in file.content
    assert "final class Envelope {" in file.content


def test_dart_generator_uses_name_registration_when_auto_id_disabled():
    file = generate_dart(
        """
        option enable_auto_type_id = false;
        package demo;

        message Envelope {
            message Payload {
                string note = 1;
            }
        }
        """
    )

    assert "defaultNamespace: 'demo'," in file.content
    assert "defaultTypeName: 'Envelope'," in file.content
    assert "defaultTypeName: 'Envelope.Payload'," in file.content
    assert (
        "DemoFory.register(fory, Envelope_Payload, id: registrationMode.id, namespace: registrationMode.namespace, typeName: registrationMode.typeName);"
        in file.content
    )


def test_dart_generator_output_path_uses_package_segments_and_package_leaf():
    file = generate_dart(
        """
        package demo.foo;

        message User [id=1] {
            string name = 1;
        }
        """
    )
    assert file.path == "demo/foo/demo_foo.dart"

    schema = parse_schema(
        """
        package any_example_pb;

        message AnyInner [id=300] {
            string name = 1;
        }
        """
    )
    schema.source_file = "/tmp/any_example.proto"
    generator = DartGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    file = generator.generate()[0]
    assert file.path == "any_example_pb/any_example_pb.dart"
    assert "part 'any_example_pb.fory.dart';" in file.content


def test_dart_generator_supports_imported_registration_calls_without_fallthrough_throw():
    repo_root = Path(__file__).resolve().parents[3]
    idl_dir = repo_root / "integration_tests" / "idl_tests" / "idl"
    schema = resolve_imports(idl_dir / "root.idl", [idl_dir])
    generator = DartGenerator(schema, GeneratorOptions(output_dir=Path("/tmp")))
    file = generator.generate()[0]
    assert "import '../addressbook/addressbook.dart' as addressbook;" in file.content
    assert "try {" in file.content
    assert (
        "addressbook.ForyRegistration.register(fory, type, id: id, namespace: namespace, typeName: typeName);"
        in file.content
    )
    assert "return;" in file.content
    assert "} on ArgumentError {" in file.content
    assert "addressbook.AddressBook" in file.content
