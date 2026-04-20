/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import 'dart:io';
import 'dart:typed_data';

import 'package:fory/fory.dart';
import 'package:test/test.dart';

import 'package:idl_dart_tests/generated/addressbook/addressbook.dart' as addressbook;
import 'package:idl_dart_tests/generated/auto_id/auto_id.dart' as auto_id;
import 'package:idl_dart_tests/generated/collection/collection.dart' as collection;
import 'package:idl_dart_tests/generated/graph/graph.dart' as graph;
import 'package:idl_dart_tests/generated/optional_types/optional_types.dart' as optional_types;
import 'package:idl_dart_tests/generated/root/root.dart' as root;
import 'package:idl_dart_tests/generated/tree/tree.dart' as tree;

Fory _newFory({bool compatible = false}) {
  return Fory(
    compatible: compatible,
    checkStructVersion: !compatible,
  );
}

void _registerCommon(Fory fory) {
  addressbook.ForyRegistration.register(fory, addressbook.Person, id: 100);
  addressbook.ForyRegistration.register(
    fory,
    addressbook.Person_PhoneType,
    id: 101,
  );
  addressbook.ForyRegistration.register(
    fory,
    addressbook.Person_PhoneNumber,
    id: 102,
  );
  addressbook.ForyRegistration.register(
    fory,
    addressbook.AddressBook,
    id: 103,
  );
  addressbook.ForyRegistration.register(fory, addressbook.Dog, id: 104);
  addressbook.ForyRegistration.register(fory, addressbook.Cat, id: 105);
  addressbook.ForyRegistration.register(fory, addressbook.Animal, id: 106);

  auto_id.ForyRegistration.register(fory, auto_id.Status);
  auto_id.ForyRegistration.register(fory, auto_id.Envelope_Payload);
  auto_id.ForyRegistration.register(fory, auto_id.Envelope_Detail);
  auto_id.ForyRegistration.register(fory, auto_id.Envelope);
  auto_id.ForyRegistration.register(fory, auto_id.Wrapper);

  collection.ForyRegistration.register(
    fory,
    collection.NumericCollections,
    id: 210,
  );
  collection.ForyRegistration.register(
    fory,
    collection.NumericCollectionUnion,
    id: 211,
  );
  collection.ForyRegistration.register(
    fory,
    collection.NumericCollectionsArray,
    id: 212,
  );
  collection.ForyRegistration.register(
    fory,
    collection.NumericCollectionArrayUnion,
    id: 213,
  );

  optional_types.ForyRegistration.register(
    fory,
    optional_types.AllOptionalTypes,
    id: 120,
  );
  optional_types.ForyRegistration.register(
    fory,
    optional_types.OptionalUnion,
    id: 121,
  );
  optional_types.ForyRegistration.register(
    fory,
    optional_types.OptionalHolder,
    id: 122,
  );
}

void _registerRefs(Fory fory) {
  tree.ForyRegistration.register(fory, tree.TreeNode);
  graph.ForyRegistration.register(fory, graph.Node);
  graph.ForyRegistration.register(fory, graph.Edge);
  graph.ForyRegistration.register(fory, graph.Graph);
  root.ForyRegistration.register(fory, root.MultiHolder, id: 300);
}

addressbook.AddressBook buildAddressBook() {
  final mobile = addressbook.Person_PhoneNumber()
    ..number = '555-0100'
    ..phoneType = addressbook.Person_PhoneType.mobile;
  final work = addressbook.Person_PhoneNumber()
    ..number = '555-0111'
    ..phoneType = addressbook.Person_PhoneType.work;

  final pet = addressbook.Animal.cat(
    addressbook.Cat()
      ..name = 'Mimi'
      ..lives = Int32(9),
  );

  final person = addressbook.Person()
    ..name = 'Alice'
    ..id = Int32(123)
    ..email = 'alice@example.com'
    ..tags = <String>['friend', 'colleague']
    ..scores = <String, Int32>{
      'math': Int32(100),
      'science': Int32(98),
    }
    ..salary = 120000.5
    ..phones = <addressbook.Person_PhoneNumber>[mobile, work]
    ..pet = pet;

  return addressbook.AddressBook()
    ..people = <addressbook.Person>[person]
    ..peopleByName = <String, addressbook.Person>{person.name: person};
}

auto_id.Envelope buildAutoIdEnvelope() {
  final payload = auto_id.Envelope_Payload()..value = Int32(42);
  return auto_id.Envelope()
    ..id = 'env-1'
    ..payload = payload
    ..detail = auto_id.Envelope_Detail.payload(payload)
    ..status = auto_id.Status.ok;
}

collection.NumericCollections buildNumericCollections() {
  return collection.NumericCollections()
    ..int8Values = Int8List.fromList(<int>[1, -2, 3])
    ..int16Values = Int16List.fromList(<int>[100, -200, 300])
    ..int32Values = Int32List.fromList(<int>[1000, -2000, 3000])
    ..int64Values = Int64List.fromList(<int>[10000, -20000, 30000])
    ..uint8Values = Uint8List.fromList(<int>[200, 250])
    ..uint16Values = Uint16List.fromList(<int>[50000, 60000])
    ..uint32Values = Uint32List.fromList(<int>[2000000000, 2100000000])
    ..uint64Values = Uint64List.fromList(<int>[9000000000, 12000000000])
    ..float32Values = Float32List.fromList(<double>[1.5, 2.5])
    ..float64Values = Float64List.fromList(<double>[3.5, 4.5]);
}

collection.NumericCollectionUnion buildNumericCollectionUnion() {
  return collection.NumericCollectionUnion.int32Values(
    Int32List.fromList(<int>[7, 8, 9]),
  );
}

collection.NumericCollectionsArray buildNumericCollectionsArray() {
  return collection.NumericCollectionsArray()
    ..int8Values = Int8List.fromList(<int>[1, -2, 3])
    ..int16Values = Int16List.fromList(<int>[100, -200, 300])
    ..int32Values = Int32List.fromList(<int>[1000, -2000, 3000])
    ..int64Values = Int64List.fromList(<int>[10000, -20000, 30000])
    ..uint8Values = Uint8List.fromList(<int>[200, 250])
    ..uint16Values = Uint16List.fromList(<int>[50000, 60000])
    ..uint32Values = Uint32List.fromList(<int>[2000000000, 2100000000])
    ..uint64Values = Uint64List.fromList(<int>[9000000000, 12000000000])
    ..float32Values = Float32List.fromList(<double>[1.5, 2.5])
    ..float64Values = Float64List.fromList(<double>[3.5, 4.5]);
}

collection.NumericCollectionArrayUnion buildNumericCollectionArrayUnion() {
  return collection.NumericCollectionArrayUnion.uint16Values(
    Uint16List.fromList(<int>[1000, 2000, 3000]),
  );
}

optional_types.OptionalHolder buildOptionalHolder() {
  final allTypes = optional_types.AllOptionalTypes()
    ..boolValue = true
    ..int8Value = Int8(12)
    ..int16Value = Int16(1234)
    ..int32Value = Int32(-123456)
    ..fixedInt32Value = Int32(-123456)
    ..varint32Value = Int32(-12345)
    ..int64Value = -123456789
    ..fixedInt64Value = -123456789
    ..varint64Value = -987654321
    ..taggedInt64Value = 123456789
    ..uint8Value = UInt8(200)
    ..uint16Value = UInt16(60000)
    ..uint32Value = UInt32(1234567890)
    ..fixedUint32Value = UInt32(1234567890)
    ..varUint32Value = UInt32(1234567890)
    ..uint64Value = 9876543210
    ..fixedUint64Value = 9876543210
    ..varUint64Value = 12345678901
    ..taggedUint64Value = 2222222222
    ..float32Value = Float32(2.5)
    ..float64Value = 3.5
    ..stringValue = 'optional'
    ..bytesValue = Uint8List.fromList(<int>[1, 2, 3])
    ..dateValue = const LocalDate(2024, 1, 2)
    ..timestampValue = Timestamp.fromDateTime(DateTime.utc(2024, 1, 2, 3, 4, 5))
    ..int32List = Int32List.fromList(<int>[1, 2, 3])
    ..stringList = <String>['alpha', 'beta']
    ..int64Map = <String, int>{'alpha': 10, 'beta': 20};

  return optional_types.OptionalHolder()
    ..allTypes = allTypes
    ..choice = optional_types.OptionalUnion.note('optional');
}

tree.TreeNode buildTree() {
  final childA = tree.TreeNode()
    ..id = 'child-a'
    ..name = 'child-a'
    ..children = <tree.TreeNode>[];
  final childB = tree.TreeNode()
    ..id = 'child-b'
    ..name = 'child-b'
    ..children = <tree.TreeNode>[];
  childA.parent = childB;
  childB.parent = childA;

  return tree.TreeNode()
    ..id = 'root'
    ..name = 'root'
    ..children = <tree.TreeNode>[childA, childA, childB];
}

graph.Graph buildGraph() {
  final nodeA = graph.Node()..id = 'node-a';
  final nodeB = graph.Node()..id = 'node-b';
  final edge = graph.Edge()
    ..id = 'edge-1'
    ..weight = Float32(1.5)
    ..from = nodeA
    ..to = nodeB;
  nodeA.outEdges = <graph.Edge>[edge];
  nodeA.inEdges = <graph.Edge>[edge];
  nodeB.inEdges = <graph.Edge>[edge];
  nodeB.outEdges = <graph.Edge>[];

  return graph.Graph()
    ..nodes = <graph.Node>[nodeA, nodeB]
    ..edges = <graph.Edge>[edge];
}

root.MultiHolder buildRootHolder() {
  final book = buildAddressBook();
  final rootNode = tree.TreeNode()
    ..id = 'root'
    ..name = 'root'
    ..children = <tree.TreeNode>[];
  return root.MultiHolder()
    ..book = book
    ..root = rootNode
    ..owner = book.people.first;
}

T _roundTrip<T>(Fory fory, T value, {bool trackRef = false}) {
  final bytes = fory.serialize(value, trackRef: trackRef);
  return fory.deserialize<T>(bytes);
}

void _roundTripFile<T>(
  String? env,
  Fory fory,
  T value,
  void Function(T actual) check, {
  bool trackRef = false,
}) {
  if (env == null || env.isEmpty) {
    return;
  }
  final file = File(env);
  if (!file.existsSync() || file.lengthSync() == 0) {
    file.createSync(recursive: true);
    file.writeAsBytesSync(
      fory.serialize(value, trackRef: trackRef),
      flush: true,
    );
  }
  final bytes = file.readAsBytesSync();
  final actual = fory.deserialize<T>(Uint8List.fromList(bytes));
  check(actual);
  file.writeAsBytesSync(
    fory.serialize(actual, trackRef: trackRef),
    flush: true,
  );
}

void _expectMapEquals<K, V>(Map<K, V> actual, Map<K, V> expected) {
  expect(actual.length, expected.length);
  for (final entry in expected.entries) {
    expect(actual[entry.key], equals(entry.value));
  }
}

void _expectPhoneNumberEquals(
  addressbook.Person_PhoneNumber actual,
  addressbook.Person_PhoneNumber expected,
) {
  expect(actual.number, equals(expected.number));
  expect(actual.phoneType, equals(expected.phoneType));
}

void _expectAnimalEquals(
  addressbook.Animal actual,
  addressbook.Animal expected,
) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case addressbook.AnimalCase.dog:
      expect(actual.dogValue.name, equals(expected.dogValue.name));
      expect(actual.dogValue.barkVolume, equals(expected.dogValue.barkVolume));
    case addressbook.AnimalCase.cat:
      expect(actual.catValue.name, equals(expected.catValue.name));
      expect(actual.catValue.lives, equals(expected.catValue.lives));
  }
}

void _expectPersonEquals(addressbook.Person actual, addressbook.Person expected) {
  expect(actual.name, equals(expected.name));
  expect(actual.id, equals(expected.id));
  expect(actual.email, equals(expected.email));
  expect(actual.tags, orderedEquals(expected.tags));
  _expectMapEquals(actual.scores, expected.scores);
  expect(actual.salary, equals(expected.salary));
  expect(actual.phones.length, expected.phones.length);
  for (var index = 0; index < expected.phones.length; index += 1) {
    _expectPhoneNumberEquals(actual.phones[index], expected.phones[index]);
  }
  _expectAnimalEquals(actual.pet, expected.pet);
}

void _expectAddressBookEquals(
  addressbook.AddressBook actual,
  addressbook.AddressBook expected,
) {
  expect(actual.people.length, expected.people.length);
  for (var index = 0; index < expected.people.length; index += 1) {
    _expectPersonEquals(actual.people[index], expected.people[index]);
  }
  expect(actual.peopleByName.length, expected.peopleByName.length);
  for (final entry in expected.peopleByName.entries) {
    final actualPerson = actual.peopleByName[entry.key];
    expect(actualPerson, isNotNull);
    _expectPersonEquals(actualPerson!, entry.value);
  }
}

void _expectEnvelopeEquals(auto_id.Envelope actual, auto_id.Envelope expected) {
  expect(actual.id, equals(expected.id));
  expect(actual.status, equals(expected.status));
  expect(actual.payload, isNotNull);
  expect(expected.payload, isNotNull);
  expect(actual.payload!.value, equals(expected.payload!.value));
  expect(actual.detail.caseValue, equals(expected.detail.caseValue));
  switch (expected.detail.caseValue) {
    case auto_id.Envelope_DetailCase.payload:
      expect(actual.detail.payloadValue.value, equals(expected.detail.payloadValue.value));
    case auto_id.Envelope_DetailCase.note:
      expect(actual.detail.noteValue, equals(expected.detail.noteValue));
  }
}

void _expectWrapperEquals(auto_id.Wrapper actual, auto_id.Wrapper expected) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case auto_id.WrapperCase.envelope:
      _expectEnvelopeEquals(actual.envelopeValue, expected.envelopeValue);
    case auto_id.WrapperCase.raw:
      expect(actual.rawValue, equals(expected.rawValue));
  }
}

void _expectNumericCollectionsEquals(
  collection.NumericCollections actual,
  collection.NumericCollections expected,
) {
  expect(actual.int8Values, orderedEquals(expected.int8Values));
  expect(actual.int16Values, orderedEquals(expected.int16Values));
  expect(actual.int32Values, orderedEquals(expected.int32Values));
  expect(actual.int64Values, orderedEquals(expected.int64Values));
  expect(actual.uint8Values, orderedEquals(expected.uint8Values));
  expect(actual.uint16Values, orderedEquals(expected.uint16Values));
  expect(actual.uint32Values, orderedEquals(expected.uint32Values));
  expect(actual.uint64Values, orderedEquals(expected.uint64Values));
  expect(actual.float32Values, orderedEquals(expected.float32Values));
  expect(actual.float64Values, orderedEquals(expected.float64Values));
}

void _expectNumericCollectionsArrayEquals(
  collection.NumericCollectionsArray actual,
  collection.NumericCollectionsArray expected,
) {
  expect(actual.int8Values, orderedEquals(expected.int8Values));
  expect(actual.int16Values, orderedEquals(expected.int16Values));
  expect(actual.int32Values, orderedEquals(expected.int32Values));
  expect(actual.int64Values, orderedEquals(expected.int64Values));
  expect(actual.uint8Values, orderedEquals(expected.uint8Values));
  expect(actual.uint16Values, orderedEquals(expected.uint16Values));
  expect(actual.uint32Values, orderedEquals(expected.uint32Values));
  expect(actual.uint64Values, orderedEquals(expected.uint64Values));
  expect(actual.float32Values, orderedEquals(expected.float32Values));
  expect(actual.float64Values, orderedEquals(expected.float64Values));
}

void _expectNumericCollectionUnionEquals(
  collection.NumericCollectionUnion actual,
  collection.NumericCollectionUnion expected,
) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case collection.NumericCollectionUnionCase.int8Values:
      expect(actual.int8ValuesValue, orderedEquals(expected.int8ValuesValue));
    case collection.NumericCollectionUnionCase.int16Values:
      expect(actual.int16ValuesValue, orderedEquals(expected.int16ValuesValue));
    case collection.NumericCollectionUnionCase.int32Values:
      expect(actual.int32ValuesValue, orderedEquals(expected.int32ValuesValue));
    case collection.NumericCollectionUnionCase.int64Values:
      expect(actual.int64ValuesValue, orderedEquals(expected.int64ValuesValue));
    case collection.NumericCollectionUnionCase.uint8Values:
      expect(actual.uint8ValuesValue, orderedEquals(expected.uint8ValuesValue));
    case collection.NumericCollectionUnionCase.uint16Values:
      expect(actual.uint16ValuesValue, orderedEquals(expected.uint16ValuesValue));
    case collection.NumericCollectionUnionCase.uint32Values:
      expect(actual.uint32ValuesValue, orderedEquals(expected.uint32ValuesValue));
    case collection.NumericCollectionUnionCase.uint64Values:
      expect(actual.uint64ValuesValue, orderedEquals(expected.uint64ValuesValue));
    case collection.NumericCollectionUnionCase.float32Values:
      expect(actual.float32ValuesValue, orderedEquals(expected.float32ValuesValue));
    case collection.NumericCollectionUnionCase.float64Values:
      expect(actual.float64ValuesValue, orderedEquals(expected.float64ValuesValue));
  }
}

void _expectNumericCollectionArrayUnionEquals(
  collection.NumericCollectionArrayUnion actual,
  collection.NumericCollectionArrayUnion expected,
) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case collection.NumericCollectionArrayUnionCase.int8Values:
      expect(actual.int8ValuesValue, orderedEquals(expected.int8ValuesValue));
    case collection.NumericCollectionArrayUnionCase.int16Values:
      expect(actual.int16ValuesValue, orderedEquals(expected.int16ValuesValue));
    case collection.NumericCollectionArrayUnionCase.int32Values:
      expect(actual.int32ValuesValue, orderedEquals(expected.int32ValuesValue));
    case collection.NumericCollectionArrayUnionCase.int64Values:
      expect(actual.int64ValuesValue, orderedEquals(expected.int64ValuesValue));
    case collection.NumericCollectionArrayUnionCase.uint8Values:
      expect(actual.uint8ValuesValue, orderedEquals(expected.uint8ValuesValue));
    case collection.NumericCollectionArrayUnionCase.uint16Values:
      expect(actual.uint16ValuesValue, orderedEquals(expected.uint16ValuesValue));
    case collection.NumericCollectionArrayUnionCase.uint32Values:
      expect(actual.uint32ValuesValue, orderedEquals(expected.uint32ValuesValue));
    case collection.NumericCollectionArrayUnionCase.uint64Values:
      expect(actual.uint64ValuesValue, orderedEquals(expected.uint64ValuesValue));
    case collection.NumericCollectionArrayUnionCase.float32Values:
      expect(actual.float32ValuesValue, orderedEquals(expected.float32ValuesValue));
    case collection.NumericCollectionArrayUnionCase.float64Values:
      expect(actual.float64ValuesValue, orderedEquals(expected.float64ValuesValue));
  }
}

void _expectOptionalTypesEquals(
  optional_types.AllOptionalTypes actual,
  optional_types.AllOptionalTypes expected,
) {
  expect(actual.boolValue, equals(expected.boolValue));
  expect(actual.int8Value, equals(expected.int8Value));
  expect(actual.int16Value, equals(expected.int16Value));
  expect(actual.int32Value, equals(expected.int32Value));
  expect(actual.fixedInt32Value, equals(expected.fixedInt32Value));
  expect(actual.varint32Value, equals(expected.varint32Value));
  expect(actual.int64Value, equals(expected.int64Value));
  expect(actual.fixedInt64Value, equals(expected.fixedInt64Value));
  expect(actual.varint64Value, equals(expected.varint64Value));
  expect(actual.taggedInt64Value, equals(expected.taggedInt64Value));
  expect(actual.uint8Value, equals(expected.uint8Value));
  expect(actual.uint16Value, equals(expected.uint16Value));
  expect(actual.uint32Value, equals(expected.uint32Value));
  expect(actual.fixedUint32Value, equals(expected.fixedUint32Value));
  expect(actual.varUint32Value, equals(expected.varUint32Value));
  expect(actual.uint64Value, equals(expected.uint64Value));
  expect(actual.fixedUint64Value, equals(expected.fixedUint64Value));
  expect(actual.varUint64Value, equals(expected.varUint64Value));
  expect(actual.taggedUint64Value, equals(expected.taggedUint64Value));
  expect(actual.float32Value, equals(expected.float32Value));
  expect(actual.float64Value, equals(expected.float64Value));
  expect(actual.stringValue, equals(expected.stringValue));
  if (expected.bytesValue == null) {
    expect(actual.bytesValue, isNull);
  } else {
    expect(actual.bytesValue, isNotNull);
    expect(actual.bytesValue!, orderedEquals(expected.bytesValue!));
  }
  expect(actual.dateValue, equals(expected.dateValue));
  expect(actual.timestampValue, equals(expected.timestampValue));
  if (expected.int32List == null) {
    expect(actual.int32List, isNull);
  } else {
    expect(actual.int32List, isNotNull);
    expect(actual.int32List!, orderedEquals(expected.int32List!));
  }
  if (expected.stringList == null) {
    expect(actual.stringList, isNull);
  } else {
    expect(actual.stringList, isNotNull);
    expect(actual.stringList!, orderedEquals(expected.stringList!));
  }
  if (expected.int64Map == null) {
    expect(actual.int64Map, isNull);
  } else {
    expect(actual.int64Map, isNotNull);
    _expectMapEquals(actual.int64Map!, expected.int64Map!);
  }
}

void _expectOptionalUnionEquals(
  optional_types.OptionalUnion actual,
  optional_types.OptionalUnion expected,
) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case optional_types.OptionalUnionCase.note:
      expect(actual.noteValue, equals(expected.noteValue));
    case optional_types.OptionalUnionCase.code:
      expect(actual.codeValue, equals(expected.codeValue));
    case optional_types.OptionalUnionCase.payload:
      _expectOptionalTypesEquals(actual.payloadValue, expected.payloadValue);
  }
}

void _expectOptionalHolderEquals(
  optional_types.OptionalHolder actual,
  optional_types.OptionalHolder expected,
) {
  if (expected.allTypes == null) {
    expect(actual.allTypes, isNull);
  } else {
    expect(actual.allTypes, isNotNull);
    _expectOptionalTypesEquals(actual.allTypes!, expected.allTypes!);
  }
  if (expected.choice == null) {
    expect(actual.choice, isNull);
  } else {
    expect(actual.choice, isNotNull);
    _expectOptionalUnionEquals(actual.choice!, expected.choice!);
  }
}

void _expectTree(tree.TreeNode actual) {
  expect(actual.children.length, equals(3));
  expect(identical(actual.children[0], actual.children[1]), isTrue);
  expect(identical(actual.children[0], actual.children[2]), isFalse);
  expect(identical(actual.children[0].parent, actual.children[2]), isTrue);
  expect(identical(actual.children[2].parent, actual.children[0]), isTrue);
}

void _expectGraph(graph.Graph actual) {
  expect(actual.nodes.length, equals(2));
  expect(actual.edges.length, equals(1));
  final nodeA = actual.nodes[0];
  final nodeB = actual.nodes[1];
  final edge = actual.edges[0];
  expect(identical(nodeA.outEdges[0], nodeA.inEdges[0]), isTrue);
  expect(identical(edge, nodeA.outEdges[0]), isTrue);
  expect(identical(edge.from, nodeA), isTrue);
  expect(identical(edge.to, nodeB), isTrue);
}

void _expectRootHolderEquals(root.MultiHolder actual, root.MultiHolder expected) {
  expect(actual.book, isNotNull);
  expect(expected.book, isNotNull);
  _expectAddressBookEquals(actual.book!, expected.book!);
  expect(actual.root, isNotNull);
  expect(expected.root, isNotNull);
  expect(actual.root!.id, equals(expected.root!.id));
  expect(actual.root!.name, equals(expected.root!.name));
  expect(actual.root!.children, isEmpty);
  expect(actual.root!.parent, isNull);
  expect(actual.owner, isNotNull);
  expect(expected.owner, isNotNull);
  _expectPersonEquals(actual.owner!, expected.owner!);
}

void main() {
  group('dart schema idl integration', () {
    test('addressbook and auto-id roundtrip in compatible and schema-consistent modes', () {
      for (final compatible in <bool>[true, false]) {
        final fory = _newFory(compatible: compatible);
        _registerCommon(fory);

        final book = buildAddressBook();
        final bookRoundTrip = _roundTrip<addressbook.AddressBook>(fory, book);
        _expectAddressBookEquals(bookRoundTrip, book);

        final envelope = buildAutoIdEnvelope();
        final envelopeRoundTrip = _roundTrip<auto_id.Envelope>(fory, envelope);
        _expectEnvelopeEquals(envelopeRoundTrip, envelope);

        final wrapper = auto_id.Wrapper.envelope(envelope);
        final wrapperRoundTrip = _roundTrip<auto_id.Wrapper>(fory, wrapper);
        _expectWrapperEquals(wrapperRoundTrip, wrapper);
      }
    });

    test('collection and optional payloads roundtrip', () {
      final fory = _newFory(compatible: true);
      _registerCommon(fory);

      final collections = buildNumericCollections();
      _expectNumericCollectionsEquals(
        _roundTrip<collection.NumericCollections>(fory, collections),
        collections,
      );

      final collectionUnion = buildNumericCollectionUnion();
      _expectNumericCollectionUnionEquals(
        _roundTrip<collection.NumericCollectionUnion>(fory, collectionUnion),
        collectionUnion,
      );

      final collectionsArray = buildNumericCollectionsArray();
      _expectNumericCollectionsArrayEquals(
        _roundTrip<collection.NumericCollectionsArray>(fory, collectionsArray),
        collectionsArray,
      );

      final collectionArrayUnion = buildNumericCollectionArrayUnion();
      _expectNumericCollectionArrayUnionEquals(
        _roundTrip<collection.NumericCollectionArrayUnion>(fory, collectionArrayUnion),
        collectionArrayUnion,
      );

      final holder = buildOptionalHolder();
      _expectOptionalHolderEquals(
        _roundTrip<optional_types.OptionalHolder>(fory, holder),
        holder,
      );
    });

    test('reference graphs and root helpers roundtrip', () {
      final fory = _newFory(compatible: true);
      _registerCommon(fory);
      _registerRefs(fory);

      final treeValue = buildTree();
      final treeRoundTrip = _roundTrip<tree.TreeNode>(
        fory,
        treeValue,
        trackRef: true,
      );
      _expectTree(treeRoundTrip);

      final graphValue = buildGraph();
      final graphRoundTrip = _roundTrip<graph.Graph>(
        fory,
        graphValue,
        trackRef: true,
      );
      _expectGraph(graphRoundTrip);

      final holder = buildRootHolder();
      final holderBytes = holder.toBytes();
      final holderDecoded = root.MultiHolder.fromBytes(holderBytes);
      _expectRootHolderEquals(holderDecoded, holder);
    });

    test('interop file roundtrip hooks when env vars are set', () {
      final fory = _newFory(compatible: true);
      _registerCommon(fory);
      _registerRefs(fory);

      _roundTripFile(
        Platform.environment['DATA_FILE'],
        fory,
        buildAddressBook(),
        (actual) => _expectAddressBookEquals(actual, buildAddressBook()),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_AUTO_ID'],
        fory,
        buildAutoIdEnvelope(),
        (actual) => _expectEnvelopeEquals(actual, buildAutoIdEnvelope()),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_TREE'],
        fory,
        buildTree(),
        (actual) => _expectTree(actual),
        trackRef: true,
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_GRAPH'],
        fory,
        buildGraph(),
        (actual) => _expectGraph(actual),
        trackRef: true,
      );
    });
  });
}
