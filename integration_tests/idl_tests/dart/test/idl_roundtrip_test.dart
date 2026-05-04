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

import 'package:idl_dart_tests/generated/addressbook/addressbook.dart'
    as addressbook;
import 'package:idl_dart_tests/generated/auto_id/auto_id.dart' as auto_id;
import 'package:idl_dart_tests/generated/collection/collection.dart'
    as collection;
import 'package:idl_dart_tests/generated/complex_fbs/complex_fbs.dart'
    as complex_fbs;
import 'package:idl_dart_tests/generated/complex_pb/complex_pb.dart'
    as complex_pb;
import 'package:idl_dart_tests/generated/example/example.dart' as example;
import 'package:idl_dart_tests/generated/graph/graph.dart' as graph;
import 'package:idl_dart_tests/generated/monster/monster.dart' as monster;
import 'package:idl_dart_tests/generated/optional_types/optional_types.dart'
    as optional_types;
import 'package:idl_dart_tests/generated/root/root.dart' as root;
import 'package:idl_dart_tests/generated/tree/tree.dart' as tree;

Fory _newFory({bool compatible = false}) {
  return Fory(compatible: compatible, checkStructVersion: !compatible);
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
  addressbook.ForyRegistration.register(fory, addressbook.AddressBook, id: 103);
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

  complex_pb.ForyRegistration.register(fory, complex_pb.PrimitiveTypes);
  complex_pb.ForyRegistration.register(fory, complex_pb.PrimitiveTypes_Contact);

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

  example.ForyRegistration.register(fory, example.ExampleState, id: 1504);
  example.ForyRegistration.register(fory, example.ExampleLeaf, id: 1502);
  example.ForyRegistration.register(fory, example.ExampleLeafUnion, id: 1503);
  example.ForyRegistration.register(
    fory,
    example.ExampleMessageUnion,
    id: 1501,
  );
  example.ForyRegistration.register(fory, example.ExampleMessage, id: 1500);

  monster.ForyRegistration.register(fory, monster.Color);
  monster.ForyRegistration.register(fory, monster.Vec3);
  monster.ForyRegistration.register(fory, monster.Monster);

  complex_fbs.ForyRegistration.register(fory, complex_fbs.Status);
  complex_fbs.ForyRegistration.register(fory, complex_fbs.Payload);
  complex_fbs.ForyRegistration.register(fory, complex_fbs.ScalarPack);
  complex_fbs.ForyRegistration.register(fory, complex_fbs.Note);
  complex_fbs.ForyRegistration.register(fory, complex_fbs.Metric);
  complex_fbs.ForyRegistration.register(fory, complex_fbs.Container);
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
    ..phoneType = addressbook.Person_PhoneType.phoneTypeMobile;
  final work = addressbook.Person_PhoneNumber()
    ..number = '555-0111'
    ..phoneType = addressbook.Person_PhoneType.phoneTypeWork;

  final pet = addressbook.Animal.cat(
    addressbook.Cat()
      ..name = 'Mimi'
      ..lives = 9,
  );

  final person = addressbook.Person()
    ..name = 'Alice'
    ..id = 123
    ..email = 'alice@example.com'
    ..tags = <String>['friend', 'colleague']
    ..scores = <String, int>{'math': 100, 'science': 98}
    ..salary = 120000.5
    ..phones = <addressbook.Person_PhoneNumber>[mobile, work]
    ..pet = pet;

  return addressbook.AddressBook()
    ..people = <addressbook.Person>[person]
    ..peopleByName = <String, addressbook.Person>{person.name: person};
}

auto_id.Envelope buildAutoIdEnvelope() {
  final payload = auto_id.Envelope_Payload()..value = 42;
  return auto_id.Envelope()
    ..id = 'env-1'
    ..payload = payload
    ..detail = auto_id.Envelope_Detail.payload(payload)
    ..status = auto_id.Status.ok;
}

complex_pb.PrimitiveTypes buildPrimitiveTypes() {
  return complex_pb.PrimitiveTypes()
    ..boolValue = true
    ..int8Value = 12
    ..int16Value = 1234
    ..int32Value = -123456
    ..varintI32Value = -12345
    ..int64Value = Int64(-123456789)
    ..varintI64Value = Int64(-987654321)
    ..taggedI64Value = Int64(123456789)
    ..uint8Value = 200
    ..uint16Value = 60000
    ..uint32Value = 1234567890
    ..varintU32Value = 1234567890
    ..uint64Value = Uint64(9876543210)
    ..varintU64Value = Uint64(12345678901)
    ..taggedU64Value = Uint64(2222222222)
    ..float32Value = Float32(2.5)
    ..float64Value = 3.5
    ..contact = complex_pb.PrimitiveTypes_Contact.phone(12345);
}

collection.NumericCollections buildNumericCollections() {
  return collection.NumericCollections()
    ..int8Values = <int>[1, -2, 3]
    ..int16Values = <int>[100, -200, 300]
    ..int32Values = <int>[1000, -2000, 3000]
    ..int64Values = <Int64>[Int64(10000), Int64(-20000), Int64(30000)]
    ..uint8Values = <int>[200, 250]
    ..uint16Values = <int>[50000, 60000]
    ..uint32Values = <int>[2000000000, 2100000000]
    ..uint64Values = <Uint64>[Uint64(9000000000), Uint64(12000000000)]
    ..float32Values = <Float32>[Float32(1.5), Float32(2.5)]
    ..float64Values = <double>[3.5, 4.5];
}

collection.NumericCollectionUnion buildNumericCollectionUnion() {
  return collection.NumericCollectionUnion.int32Values(<int>[7, 8, 9]);
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
    ..int8Value = 12
    ..int16Value = 1234
    ..int32Value = -123456
    ..fixedI32Value = -123456
    ..varintI32Value = -12345
    ..int64Value = Int64(-123456789)
    ..fixedI64Value = Int64(-123456789)
    ..varintI64Value = Int64(-987654321)
    ..taggedI64Value = Int64(123456789)
    ..uint8Value = 200
    ..uint16Value = 60000
    ..uint32Value = 1234567890
    ..fixedU32Value = 1234567890
    ..varintU32Value = 1234567890
    ..uint64Value = Uint64(9876543210)
    ..fixedU64Value = Uint64(9876543210)
    ..varintU64Value = Uint64(12345678901)
    ..taggedU64Value = Uint64(2222222222)
    ..float32Value = Float32(2.5)
    ..float64Value = 3.5
    ..stringValue = 'optional'
    ..bytesValue = Uint8List.fromList(<int>[1, 2, 3])
    ..dateValue = const LocalDate(2024, 1, 2)
    ..timestampValue = Timestamp.fromDateTime(DateTime.utc(2024, 1, 2, 3, 4, 5))
    ..int32List = <int>[1, 2, 3]
    ..stringList = <String>['alpha', 'beta']
    ..int64Map = <String, Int64>{'alpha': Int64(10), 'beta': Int64(20)};

  return optional_types.OptionalHolder()
    ..allTypes = allTypes
    ..choice = optional_types.OptionalUnion.note('optional');
}

example.ExampleLeaf buildExampleLeaf([String label = 'leaf', int count = 7]) {
  return example.ExampleLeaf()
    ..label = label
    ..count = count;
}

example.ExampleMessage buildExampleMessage() {
  final leaf = buildExampleLeaf();
  final otherLeaf = buildExampleLeaf('other', 8);
  final leafUnion = example.ExampleLeafUnion.leaf(otherLeaf);
  return example.ExampleMessage()
    ..boolValue = true
    ..int8Value = -12
    ..int16Value = -1234
    ..fixedI32Value = -123456
    ..varintI32Value = -12345
    ..fixedI64Value = Int64(-123456789)
    ..varintI64Value = Int64(-987654321)
    ..taggedI64Value = Int64(123456789)
    ..uint8Value = 200
    ..uint16Value = 60000
    ..fixedU32Value = 1234567890
    ..varintU32Value = 1234567890
    ..fixedU64Value = Uint64(9876543210)
    ..varintU64Value = Uint64(12345678901)
    ..taggedU64Value = Uint64(2222222222)
    ..float16Value = Float16(1.5)
    ..bfloat16Value = Bfloat16(2.5)
    ..float32Value = Float32(3.5)
    ..float64Value = 4.5
    ..stringValue = 'example'
    ..bytesValue = Uint8List.fromList(<int>[1, 2, 3])
    ..dateValue = const LocalDate(2024, 2, 3)
    ..timestampValue = Timestamp.fromDateTime(DateTime.utc(2024, 2, 3, 4, 5, 6))
    ..durationValue = const Duration(seconds: 42, microseconds: 7)
    ..decimalValue = Decimal.fromInt(12345, scale: 2)
    ..enumValue = example.ExampleState.ready
    ..messageValue = leaf
    ..unionValue = leafUnion
    ..boolList = <bool>[true, false, true]
    ..int8List = <int>[1, -2, 3]
    ..int16List = <int>[100, -200, 300]
    ..fixedI32List = <int>[1000, -2000, 3000]
    ..varintI32List = <int>[-10, 20, -30]
    ..fixedI64List = <Int64>[Int64(10000), Int64(-20000)]
    ..varintI64List = <Int64>[Int64(-40), Int64(50)]
    ..taggedI64List = <Int64>[Int64(60), Int64(70)]
    ..uint8List = <int>[200, 250]
    ..uint16List = <int>[50000, 60000]
    ..fixedU32List = <int>[2000000000, 2100000000]
    ..varintU32List = <int>[100, 200]
    ..fixedU64List = <Uint64>[Uint64(9000000000)]
    ..varintU64List = <Uint64>[Uint64(12000000000)]
    ..taggedU64List = <Uint64>[Uint64(13000000000)]
    ..float16List = <Float16>[Float16(1.0), Float16(2.0)]
    ..bfloat16List = <Bfloat16>[Bfloat16(1.0), Bfloat16(2.0)]
    ..maybeFloat16List = <Float16?>[Float16(1.0), null, Float16(2.0)]
    ..maybeBfloat16List = <Bfloat16?>[Bfloat16(1.0), null, Bfloat16(3.0)]
    ..float32List = <Float32>[Float32(1.5), Float32(2.5)]
    ..float64List = <double>[3.5, 4.5]
    ..stringList = <String>['alpha', 'beta']
    ..bytesList = <Uint8List>[
      Uint8List.fromList(<int>[4, 5]),
      Uint8List.fromList(<int>[6, 7]),
    ]
    ..dateList = <LocalDate>[
      const LocalDate(2024, 1, 1),
      const LocalDate(2024, 1, 2),
    ]
    ..timestampList = <Timestamp>[
      Timestamp.fromDateTime(DateTime.utc(2024, 1, 1)),
      Timestamp.fromDateTime(DateTime.utc(2024, 1, 2)),
    ]
    ..durationList = <Duration>[
      const Duration(milliseconds: 1),
      const Duration(seconds: 2),
    ]
    ..decimalList = <Decimal>[
      Decimal.fromInt(125, scale: 2),
      Decimal.fromInt(250, scale: 2),
    ]
    ..enumList = <example.ExampleState>[
      example.ExampleState.unknown,
      example.ExampleState.failed,
    ]
    ..messageList = <example.ExampleLeaf>[leaf, otherLeaf]
    ..unionList = <example.ExampleLeafUnion>[
      example.ExampleLeafUnion.note('note'),
      leafUnion,
    ]
    ..maybeFixedI32List = <int?>[1, null, 3]
    ..maybeUint64List = <Uint64?>[Uint64(10), null, Uint64(30)]
    ..boolArray = BoolList.fromList(<bool>[true, false])
    ..int8Array = Int8List.fromList(<int>[1, -2])
    ..int16Array = Int16List.fromList(<int>[100, -200])
    ..int32Array = Int32List.fromList(<int>[1000, -2000])
    ..int64Array = Int64List.fromList(<int>[10000, -20000])
    ..uint8Array = Uint8List.fromList(<int>[200, 250])
    ..uint16Array = Uint16List.fromList(<int>[50000, 60000])
    ..uint32Array = Uint32List.fromList(<int>[2000000000, 2100000000])
    ..uint64Array = Uint64List.fromList(<int>[9000000000, 12000000000])
    ..float16Array = Float16List.fromList(<Float16>[Float16(1.0), Float16(2.0)])
    ..bfloat16Array =
        Bfloat16List.fromList(<Bfloat16>[Bfloat16(1.0), Bfloat16(2.0)])
    ..float32Array = Float32List.fromList(<double>[1.5, 2.5])
    ..float64Array = Float64List.fromList(<double>[3.5, 4.5])
    ..int32ArrayList = <Int32List>[
      Int32List.fromList(<int>[1, 2]),
      Int32List.fromList(<int>[3, 4]),
    ]
    ..uint8ArrayList = <Uint8List>[
      Uint8List.fromList(<int>[201, 202]),
      Uint8List.fromList(<int>[203]),
    ]
    ..stringValuesByBool = <bool, String>{true: 'bool'}
    ..stringValuesByInt8 = <int, String>{-1: 'int8'}
    ..stringValuesByInt16 = <int, String>{-2: 'int16'}
    ..stringValuesByFixedI32 = <int, String>{-3: 'fixed-i32'}
    ..stringValuesByVarintI32 = <int, String>{4: 'varint_i32'}
    ..stringValuesByFixedI64 = <Int64, String>{Int64(-5): 'fixed-i64'}
    ..stringValuesByVarintI64 = <Int64, String>{Int64(6): 'varint_i64'}
    ..stringValuesByTaggedI64 = <Int64, String>{Int64(7): 'tagged-i64'}
    ..stringValuesByUint8 = <int, String>{200: 'uint8'}
    ..stringValuesByUint16 = <int, String>{60000: 'uint16'}
    ..stringValuesByFixedU32 = <int, String>{1234567890: 'fixed-u32'}
    ..stringValuesByVarintU32 = <int, String>{1234567891: 'varint-u32'}
    ..stringValuesByFixedU64 = <Uint64, String>{Uint64(9876543210): 'fixed-u64'}
    ..stringValuesByVarintU64 = <Uint64, String>{
      Uint64(9876543211): 'varint-u64'
    }
    ..stringValuesByTaggedU64 = <Uint64, String>{
      Uint64(9876543212): 'tagged-u64'
    }
    ..stringValuesByString = <String, String>{'name': 'value'}
    ..stringValuesByTimestamp = <Timestamp, String>{
      Timestamp.fromDateTime(DateTime.utc(2024, 3, 4, 5, 6, 7)): 'time',
    }
    ..stringValuesByDuration = <Duration, String>{
      const Duration(seconds: 9): 'duration',
    }
    ..stringValuesByEnum = <example.ExampleState, String>{
      example.ExampleState.ready: 'ready',
    }
    ..float16ValuesByName = <String, Float16>{'f16': Float16(1.25)}
    ..maybeFloat16ValuesByName = <String, Float16?>{'maybe-f16': Float16(1.5)}
    ..bfloat16ValuesByName = <String, Bfloat16>{'bf16': Bfloat16(1.75)}
    ..maybeBfloat16ValuesByName = <String, Bfloat16?>{
      'maybe-bf16': Bfloat16(2.25),
    }
    ..bytesValuesByName = <String, Uint8List>{
      'bytes': Uint8List.fromList(<int>[8, 9]),
    }
    ..dateValuesByName = <String, LocalDate>{
      'date': const LocalDate(2024, 5, 6),
    }
    ..decimalValuesByName = <String, Decimal>{
      'decimal': Decimal.fromInt(9901, scale: 2),
    }
    ..messageValuesByName = <String, example.ExampleLeaf>{'leaf': leaf}
    ..unionValuesByName = <String, example.ExampleLeafUnion>{
      'union': example.ExampleLeafUnion.code(42),
    }
    ..uint8ArrayValuesByName = <String, Uint8List>{
      'u8': Uint8List.fromList(<int>[201, 202]),
    }
    ..float32ArrayValuesByName = <String, Float32List>{
      'f32': Float32List.fromList(<double>[1.25, 2.5]),
    }
    ..int32ArrayValuesByName = <String, Int32List>{
      'i32': Int32List.fromList(<int>[101, 202]),
    };
}

example.ExampleMessageUnion buildExampleMessageUnion() {
  return example.ExampleMessageUnion.int32ArrayList(<Int32List>[
    Int32List.fromList(<int>[11, 12]),
    Int32List.fromList(<int>[13, 14]),
  ]);
}

monster.Monster buildMonster() {
  return monster.Monster()
    ..pos = (monster.Vec3()
      ..x = Float32(1.0)
      ..y = Float32(2.0)
      ..z = Float32(3.0))
    ..mana = 200
    ..hp = 80
    ..name = 'Orc'
    ..friendly = true
    ..inventory = Uint8List.fromList(<int>[1, 2, 3])
    ..color = monster.Color.blue;
}

complex_fbs.Container buildContainer() {
  return complex_fbs.Container()
    ..id = Uint64(9876543210)
    ..status = complex_fbs.Status.started
    ..bytes = Int8List.fromList(<int>[1, 2, 3])
    ..numbers = Int32List.fromList(<int>[10, 20, 30])
    ..scalars = (complex_fbs.ScalarPack()
      ..b = -8
      ..ub = 200
      ..s = -1234
      ..us = 40000
      ..i = -123456
      ..ui = 123456
      ..l = Int64(-123456789)
      ..ul = Uint64(987654321)
      ..f = Float32(1.5)
      ..d = 2.5
      ..ok = true)
    ..names = <String>['alpha', 'beta']
    ..flags = BoolList.fromList(<bool>[true, false])
    ..payload = complex_fbs.Payload.metric(complex_fbs.Metric()..value = 42.0);
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

void _expectListOfListsEquals<T, V extends List<T>>(
  List<V> actual,
  List<V> expected,
) {
  expect(actual.length, expected.length);
  for (var index = 0; index < expected.length; index += 1) {
    expect(actual[index], orderedEquals(expected[index]));
  }
}

void _expectMapOfListsEquals<K, T, V extends List<T>>(
  Map<K, V> actual,
  Map<K, V> expected,
) {
  expect(actual.length, expected.length);
  for (final entry in expected.entries) {
    final actualValue = actual[entry.key];
    expect(actualValue, isNotNull);
    expect(actualValue!, orderedEquals(entry.value));
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

void _expectPersonEquals(
  addressbook.Person actual,
  addressbook.Person expected,
) {
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
      expect(
        actual.detail.payloadValue.value,
        equals(expected.detail.payloadValue.value),
      );
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

void _expectPrimitiveTypesEquals(
  complex_pb.PrimitiveTypes actual,
  complex_pb.PrimitiveTypes expected,
) {
  expect(actual.boolValue, equals(expected.boolValue));
  expect(actual.int8Value, equals(expected.int8Value));
  expect(actual.int16Value, equals(expected.int16Value));
  expect(actual.int32Value, equals(expected.int32Value));
  expect(actual.varintI32Value, equals(expected.varintI32Value));
  expect(actual.int64Value, equals(expected.int64Value));
  expect(actual.varintI64Value, equals(expected.varintI64Value));
  expect(actual.taggedI64Value, equals(expected.taggedI64Value));
  expect(actual.uint8Value, equals(expected.uint8Value));
  expect(actual.uint16Value, equals(expected.uint16Value));
  expect(actual.uint32Value, equals(expected.uint32Value));
  expect(actual.varintU32Value, equals(expected.varintU32Value));
  expect(actual.uint64Value, equals(expected.uint64Value));
  expect(actual.varintU64Value, equals(expected.varintU64Value));
  expect(actual.taggedU64Value, equals(expected.taggedU64Value));
  expect(actual.float32Value, equals(expected.float32Value));
  expect(actual.float64Value, equals(expected.float64Value));
  expect(actual.contact, isNotNull);
  expect(expected.contact, isNotNull);
  expect(actual.contact!.caseValue, equals(expected.contact!.caseValue));
  switch (expected.contact!.caseValue) {
    case complex_pb.PrimitiveTypes_ContactCase.email:
      expect(actual.contact!.emailValue, equals(expected.contact!.emailValue));
    case complex_pb.PrimitiveTypes_ContactCase.phone:
      expect(actual.contact!.phoneValue, equals(expected.contact!.phoneValue));
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
      expect(
        actual.uint16ValuesValue,
        orderedEquals(expected.uint16ValuesValue),
      );
    case collection.NumericCollectionUnionCase.uint32Values:
      expect(
        actual.uint32ValuesValue,
        orderedEquals(expected.uint32ValuesValue),
      );
    case collection.NumericCollectionUnionCase.uint64Values:
      expect(
        actual.uint64ValuesValue,
        orderedEquals(expected.uint64ValuesValue),
      );
    case collection.NumericCollectionUnionCase.float32Values:
      expect(
        actual.float32ValuesValue,
        orderedEquals(expected.float32ValuesValue),
      );
    case collection.NumericCollectionUnionCase.float64Values:
      expect(
        actual.float64ValuesValue,
        orderedEquals(expected.float64ValuesValue),
      );
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
      expect(
        actual.uint16ValuesValue,
        orderedEquals(expected.uint16ValuesValue),
      );
    case collection.NumericCollectionArrayUnionCase.uint32Values:
      expect(
        actual.uint32ValuesValue,
        orderedEquals(expected.uint32ValuesValue),
      );
    case collection.NumericCollectionArrayUnionCase.uint64Values:
      expect(
        actual.uint64ValuesValue,
        orderedEquals(expected.uint64ValuesValue),
      );
    case collection.NumericCollectionArrayUnionCase.float32Values:
      expect(
        actual.float32ValuesValue,
        orderedEquals(expected.float32ValuesValue),
      );
    case collection.NumericCollectionArrayUnionCase.float64Values:
      expect(
        actual.float64ValuesValue,
        orderedEquals(expected.float64ValuesValue),
      );
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
  expect(actual.fixedI32Value, equals(expected.fixedI32Value));
  expect(actual.varintI32Value, equals(expected.varintI32Value));
  expect(actual.int64Value, equals(expected.int64Value));
  expect(actual.fixedI64Value, equals(expected.fixedI64Value));
  expect(actual.varintI64Value, equals(expected.varintI64Value));
  expect(actual.taggedI64Value, equals(expected.taggedI64Value));
  expect(actual.uint8Value, equals(expected.uint8Value));
  expect(actual.uint16Value, equals(expected.uint16Value));
  expect(actual.uint32Value, equals(expected.uint32Value));
  expect(actual.fixedU32Value, equals(expected.fixedU32Value));
  expect(actual.varintU32Value, equals(expected.varintU32Value));
  expect(actual.uint64Value, equals(expected.uint64Value));
  expect(actual.fixedU64Value, equals(expected.fixedU64Value));
  expect(actual.varintU64Value, equals(expected.varintU64Value));
  expect(actual.taggedU64Value, equals(expected.taggedU64Value));
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

void _expectExampleLeafEquals(
  example.ExampleLeaf actual,
  example.ExampleLeaf expected,
) {
  expect(actual.label, equals(expected.label));
  expect(actual.count, equals(expected.count));
}

void _expectExampleLeafUnionEquals(
  example.ExampleLeafUnion actual,
  example.ExampleLeafUnion expected,
) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case example.ExampleLeafUnionCase.note:
      expect(actual.noteValue, equals(expected.noteValue));
    case example.ExampleLeafUnionCase.code:
      expect(actual.codeValue, equals(expected.codeValue));
    case example.ExampleLeafUnionCase.leaf:
      _expectExampleLeafEquals(actual.leafValue, expected.leafValue);
  }
}

void _expectExampleMessageEquals(
  example.ExampleMessage actual,
  example.ExampleMessage expected,
) {
  expect(actual.boolValue, equals(expected.boolValue));
  expect(actual.int8Value, equals(expected.int8Value));
  expect(actual.int16Value, equals(expected.int16Value));
  expect(actual.fixedI32Value, equals(expected.fixedI32Value));
  expect(actual.varintI32Value, equals(expected.varintI32Value));
  expect(actual.fixedI64Value, equals(expected.fixedI64Value));
  expect(actual.varintI64Value, equals(expected.varintI64Value));
  expect(actual.taggedI64Value, equals(expected.taggedI64Value));
  expect(actual.uint8Value, equals(expected.uint8Value));
  expect(actual.uint16Value, equals(expected.uint16Value));
  expect(actual.fixedU32Value, equals(expected.fixedU32Value));
  expect(actual.varintU32Value, equals(expected.varintU32Value));
  expect(actual.fixedU64Value, equals(expected.fixedU64Value));
  expect(actual.varintU64Value, equals(expected.varintU64Value));
  expect(actual.taggedU64Value, equals(expected.taggedU64Value));
  expect(actual.float16Value, equals(expected.float16Value));
  expect(actual.bfloat16Value, equals(expected.bfloat16Value));
  expect(actual.float32Value, equals(expected.float32Value));
  expect(actual.float64Value, equals(expected.float64Value));
  expect(actual.stringValue, equals(expected.stringValue));
  expect(actual.bytesValue, orderedEquals(expected.bytesValue));
  expect(actual.dateValue, equals(expected.dateValue));
  expect(actual.timestampValue, equals(expected.timestampValue));
  expect(actual.durationValue, equals(expected.durationValue));
  expect(actual.decimalValue, equals(expected.decimalValue));
  expect(actual.enumValue, equals(expected.enumValue));
  expect(actual.messageValue, isNotNull);
  expect(expected.messageValue, isNotNull);
  _expectExampleLeafEquals(actual.messageValue!, expected.messageValue!);
  _expectExampleLeafUnionEquals(actual.unionValue, expected.unionValue);
  expect(actual.boolList, orderedEquals(expected.boolList));
  expect(actual.int8List, orderedEquals(expected.int8List));
  expect(actual.int16List, orderedEquals(expected.int16List));
  expect(actual.fixedI32List, orderedEquals(expected.fixedI32List));
  expect(actual.varintI32List, orderedEquals(expected.varintI32List));
  expect(actual.fixedI64List, orderedEquals(expected.fixedI64List));
  expect(actual.varintI64List, orderedEquals(expected.varintI64List));
  expect(actual.taggedI64List, orderedEquals(expected.taggedI64List));
  expect(actual.uint8List, orderedEquals(expected.uint8List));
  expect(actual.uint16List, orderedEquals(expected.uint16List));
  expect(actual.fixedU32List, orderedEquals(expected.fixedU32List));
  expect(actual.varintU32List, orderedEquals(expected.varintU32List));
  expect(actual.fixedU64List, orderedEquals(expected.fixedU64List));
  expect(actual.varintU64List, orderedEquals(expected.varintU64List));
  expect(actual.taggedU64List, orderedEquals(expected.taggedU64List));
  expect(actual.float16List, orderedEquals(expected.float16List));
  expect(actual.bfloat16List, orderedEquals(expected.bfloat16List));
  expect(actual.maybeFloat16List, orderedEquals(expected.maybeFloat16List));
  expect(actual.maybeBfloat16List, orderedEquals(expected.maybeBfloat16List));
  expect(actual.float32List, orderedEquals(expected.float32List));
  expect(actual.float64List, orderedEquals(expected.float64List));
  expect(actual.stringList, orderedEquals(expected.stringList));
  _expectListOfListsEquals<int, Uint8List>(
    actual.bytesList,
    expected.bytesList,
  );
  expect(actual.dateList, orderedEquals(expected.dateList));
  expect(actual.timestampList, orderedEquals(expected.timestampList));
  expect(actual.durationList, orderedEquals(expected.durationList));
  expect(actual.decimalList, orderedEquals(expected.decimalList));
  expect(actual.enumList, orderedEquals(expected.enumList));
  expect(actual.messageList.length, expected.messageList.length);
  for (var index = 0; index < expected.messageList.length; index += 1) {
    _expectExampleLeafEquals(
      actual.messageList[index],
      expected.messageList[index],
    );
  }
  expect(actual.unionList.length, expected.unionList.length);
  for (var index = 0; index < expected.unionList.length; index += 1) {
    _expectExampleLeafUnionEquals(
      actual.unionList[index],
      expected.unionList[index],
    );
  }
  expect(actual.maybeFixedI32List, orderedEquals(expected.maybeFixedI32List));
  expect(actual.maybeUint64List, orderedEquals(expected.maybeUint64List));
  expect(actual.boolArray, orderedEquals(expected.boolArray));
  expect(actual.int8Array, orderedEquals(expected.int8Array));
  expect(actual.int16Array, orderedEquals(expected.int16Array));
  expect(actual.int32Array, orderedEquals(expected.int32Array));
  expect(actual.int64Array, orderedEquals(expected.int64Array));
  expect(actual.uint8Array, orderedEquals(expected.uint8Array));
  expect(actual.uint16Array, orderedEquals(expected.uint16Array));
  expect(actual.uint32Array, orderedEquals(expected.uint32Array));
  expect(actual.uint64Array, orderedEquals(expected.uint64Array));
  expect(actual.float16Array, orderedEquals(expected.float16Array));
  expect(actual.bfloat16Array, orderedEquals(expected.bfloat16Array));
  expect(actual.float32Array, orderedEquals(expected.float32Array));
  expect(actual.float64Array, orderedEquals(expected.float64Array));
  _expectListOfListsEquals<int, Int32List>(
    actual.int32ArrayList,
    expected.int32ArrayList,
  );
  _expectListOfListsEquals<int, Uint8List>(
    actual.uint8ArrayList,
    expected.uint8ArrayList,
  );
  _expectMapEquals(actual.stringValuesByBool, expected.stringValuesByBool);
  _expectMapEquals(actual.stringValuesByInt8, expected.stringValuesByInt8);
  _expectMapEquals(actual.stringValuesByInt16, expected.stringValuesByInt16);
  _expectMapEquals(
      actual.stringValuesByFixedI32, expected.stringValuesByFixedI32);
  _expectMapEquals(
      actual.stringValuesByVarintI32, expected.stringValuesByVarintI32);
  _expectMapEquals(
      actual.stringValuesByFixedI64, expected.stringValuesByFixedI64);
  _expectMapEquals(
      actual.stringValuesByVarintI64, expected.stringValuesByVarintI64);
  _expectMapEquals(
      actual.stringValuesByTaggedI64, expected.stringValuesByTaggedI64);
  _expectMapEquals(actual.stringValuesByUint8, expected.stringValuesByUint8);
  _expectMapEquals(actual.stringValuesByUint16, expected.stringValuesByUint16);
  _expectMapEquals(
      actual.stringValuesByFixedU32, expected.stringValuesByFixedU32);
  _expectMapEquals(
      actual.stringValuesByVarintU32, expected.stringValuesByVarintU32);
  _expectMapEquals(
      actual.stringValuesByFixedU64, expected.stringValuesByFixedU64);
  _expectMapEquals(
      actual.stringValuesByVarintU64, expected.stringValuesByVarintU64);
  _expectMapEquals(
      actual.stringValuesByTaggedU64, expected.stringValuesByTaggedU64);
  _expectMapEquals(actual.stringValuesByString, expected.stringValuesByString);
  _expectMapEquals(
      actual.stringValuesByTimestamp, expected.stringValuesByTimestamp);
  _expectMapEquals(
      actual.stringValuesByDuration, expected.stringValuesByDuration);
  _expectMapEquals(actual.stringValuesByEnum, expected.stringValuesByEnum);
  _expectMapEquals(actual.float16ValuesByName, expected.float16ValuesByName);
  _expectMapEquals(
    actual.maybeFloat16ValuesByName,
    expected.maybeFloat16ValuesByName,
  );
  _expectMapEquals(actual.bfloat16ValuesByName, expected.bfloat16ValuesByName);
  _expectMapEquals(
    actual.maybeBfloat16ValuesByName,
    expected.maybeBfloat16ValuesByName,
  );
  _expectMapOfListsEquals<String, int, Uint8List>(
    actual.bytesValuesByName,
    expected.bytesValuesByName,
  );
  _expectMapEquals(actual.dateValuesByName, expected.dateValuesByName);
  _expectMapEquals(actual.decimalValuesByName, expected.decimalValuesByName);
  expect(
      actual.messageValuesByName.length, expected.messageValuesByName.length);
  for (final entry in expected.messageValuesByName.entries) {
    final actualValue = actual.messageValuesByName[entry.key];
    expect(actualValue, isNotNull);
    _expectExampleLeafEquals(actualValue!, entry.value);
  }
  expect(actual.unionValuesByName.length, expected.unionValuesByName.length);
  for (final entry in expected.unionValuesByName.entries) {
    final actualValue = actual.unionValuesByName[entry.key];
    expect(actualValue, isNotNull);
    _expectExampleLeafUnionEquals(actualValue!, entry.value);
  }
  _expectMapOfListsEquals<String, int, Uint8List>(
    actual.uint8ArrayValuesByName,
    expected.uint8ArrayValuesByName,
  );
  _expectMapOfListsEquals<String, double, Float32List>(
    actual.float32ArrayValuesByName,
    expected.float32ArrayValuesByName,
  );
  _expectMapOfListsEquals<String, int, Int32List>(
    actual.int32ArrayValuesByName,
    expected.int32ArrayValuesByName,
  );
}

void _expectExampleMessageUnionEquals(
  example.ExampleMessageUnion actual,
  example.ExampleMessageUnion expected,
) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case example.ExampleMessageUnionCase.int32Array:
      expect(actual.int32ArrayValue, orderedEquals(expected.int32ArrayValue));
    case example.ExampleMessageUnionCase.int32ArrayList:
      _expectListOfListsEquals<int, Int32List>(
        actual.int32ArrayListValue,
        expected.int32ArrayListValue,
      );
    case example.ExampleMessageUnionCase.uint8ArrayList:
      _expectListOfListsEquals<int, Uint8List>(
        actual.uint8ArrayListValue,
        expected.uint8ArrayListValue,
      );
    case example.ExampleMessageUnionCase.int32ArrayValuesByName:
      _expectMapOfListsEquals<String, int, Int32List>(
        actual.int32ArrayValuesByNameValue,
        expected.int32ArrayValuesByNameValue,
      );
    default:
      fail('Unexpected example union case ${expected.caseValue}');
  }
}

void _expectMonsterEquals(monster.Monster actual, monster.Monster expected) {
  expect(actual.pos, isNotNull);
  expect(expected.pos, isNotNull);
  expect(actual.pos!.x, equals(expected.pos!.x));
  expect(actual.pos!.y, equals(expected.pos!.y));
  expect(actual.pos!.z, equals(expected.pos!.z));
  expect(actual.mana, equals(expected.mana));
  expect(actual.hp, equals(expected.hp));
  expect(actual.name, equals(expected.name));
  expect(actual.friendly, equals(expected.friendly));
  expect(actual.inventory, orderedEquals(expected.inventory));
  expect(actual.color, equals(expected.color));
}

void _expectScalarPackEquals(
  complex_fbs.ScalarPack actual,
  complex_fbs.ScalarPack expected,
) {
  expect(actual.b, equals(expected.b));
  expect(actual.ub, equals(expected.ub));
  expect(actual.s, equals(expected.s));
  expect(actual.us, equals(expected.us));
  expect(actual.i, equals(expected.i));
  expect(actual.ui, equals(expected.ui));
  expect(actual.l, equals(expected.l));
  expect(actual.ul, equals(expected.ul));
  expect(actual.f, equals(expected.f));
  expect(actual.d, equals(expected.d));
  expect(actual.ok, equals(expected.ok));
}

void _expectPayloadEquals(
  complex_fbs.Payload actual,
  complex_fbs.Payload expected,
) {
  expect(actual.caseValue, equals(expected.caseValue));
  switch (expected.caseValue) {
    case complex_fbs.PayloadCase.note:
      expect(actual.noteValue.text, equals(expected.noteValue.text));
    case complex_fbs.PayloadCase.metric:
      expect(actual.metricValue.value, equals(expected.metricValue.value));
  }
}

void _expectContainerEquals(
  complex_fbs.Container actual,
  complex_fbs.Container expected,
) {
  expect(actual.id, equals(expected.id));
  expect(actual.status, equals(expected.status));
  expect(actual.bytes, orderedEquals(expected.bytes));
  expect(actual.numbers, orderedEquals(expected.numbers));
  expect(actual.scalars, isNotNull);
  expect(expected.scalars, isNotNull);
  _expectScalarPackEquals(actual.scalars!, expected.scalars!);
  expect(actual.names, orderedEquals(expected.names));
  expect(actual.flags, orderedEquals(expected.flags));
  _expectPayloadEquals(actual.payload, expected.payload);
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

void _expectRootHolderEquals(
  root.MultiHolder actual,
  root.MultiHolder expected,
) {
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
    test(
      'addressbook and auto-id roundtrip in compatible and schema-consistent modes',
      () {
        for (final compatible in <bool>[true, false]) {
          final fory = _newFory(compatible: compatible);
          _registerCommon(fory);

          final book = buildAddressBook();
          final bookRoundTrip = _roundTrip<addressbook.AddressBook>(fory, book);
          _expectAddressBookEquals(bookRoundTrip, book);

          final envelope = buildAutoIdEnvelope();
          final envelopeRoundTrip = _roundTrip<auto_id.Envelope>(
            fory,
            envelope,
          );
          _expectEnvelopeEquals(envelopeRoundTrip, envelope);

          final wrapper = auto_id.Wrapper.envelope(envelope);
          final wrapperRoundTrip = _roundTrip<auto_id.Wrapper>(fory, wrapper);
          _expectWrapperEquals(wrapperRoundTrip, wrapper);

          final primitiveTypes = buildPrimitiveTypes();
          final primitiveRoundTrip = _roundTrip<complex_pb.PrimitiveTypes>(
            fory,
            primitiveTypes,
          );
          _expectPrimitiveTypesEquals(primitiveRoundTrip, primitiveTypes);
        }
      },
    );

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
        _roundTrip<collection.NumericCollectionArrayUnion>(
          fory,
          collectionArrayUnion,
        ),
        collectionArrayUnion,
      );

      final holder = buildOptionalHolder();
      _expectOptionalHolderEquals(
        _roundTrip<optional_types.OptionalHolder>(fory, holder),
        holder,
      );

      final exampleMessage = buildExampleMessage();
      _expectExampleMessageEquals(
        _roundTrip<example.ExampleMessage>(fory, exampleMessage),
        exampleMessage,
      );

      final exampleUnion = buildExampleMessageUnion();
      _expectExampleMessageUnionEquals(
        _roundTrip<example.ExampleMessageUnion>(fory, exampleUnion),
        exampleUnion,
      );

      final monsterValue = buildMonster();
      _expectMonsterEquals(
        _roundTrip<monster.Monster>(fory, monsterValue),
        monsterValue,
      );

      final container = buildContainer();
      _expectContainerEquals(
        _roundTrip<complex_fbs.Container>(fory, container),
        container,
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
      final compatible =
          Platform.environment['IDL_COMPATIBLE']?.toLowerCase() != 'false';
      final fory = _newFory(compatible: compatible);
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
        Platform.environment['DATA_FILE_PRIMITIVES'],
        fory,
        buildPrimitiveTypes(),
        (actual) => _expectPrimitiveTypesEquals(actual, buildPrimitiveTypes()),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_COLLECTION'],
        fory,
        buildNumericCollections(),
        (actual) =>
            _expectNumericCollectionsEquals(actual, buildNumericCollections()),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_COLLECTION_UNION'],
        fory,
        buildNumericCollectionUnion(),
        (actual) => _expectNumericCollectionUnionEquals(
          actual,
          buildNumericCollectionUnion(),
        ),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_COLLECTION_ARRAY'],
        fory,
        buildNumericCollectionsArray(),
        (actual) => _expectNumericCollectionsArrayEquals(
          actual,
          buildNumericCollectionsArray(),
        ),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_COLLECTION_ARRAY_UNION'],
        fory,
        buildNumericCollectionArrayUnion(),
        (actual) => _expectNumericCollectionArrayUnionEquals(
          actual,
          buildNumericCollectionArrayUnion(),
        ),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_OPTIONAL_TYPES'],
        fory,
        buildOptionalHolder(),
        (actual) => _expectOptionalHolderEquals(actual, buildOptionalHolder()),
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
      _roundTripFile(
        Platform.environment['DATA_FILE_EXAMPLE'],
        fory,
        buildExampleMessage(),
        (actual) => _expectExampleMessageEquals(actual, buildExampleMessage()),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_EXAMPLE_UNION'],
        fory,
        buildExampleMessageUnion(),
        (actual) => _expectExampleMessageUnionEquals(
          actual,
          buildExampleMessageUnion(),
        ),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_FLATBUFFERS_MONSTER'],
        fory,
        buildMonster(),
        (actual) => _expectMonsterEquals(actual, buildMonster()),
      );
      _roundTripFile(
        Platform.environment['DATA_FILE_FLATBUFFERS_TEST2'],
        fory,
        buildContainer(),
        (actual) => _expectContainerEquals(actual, buildContainer()),
      );
    });
  });
}
