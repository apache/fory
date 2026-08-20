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

import 'dart:typed_data';

import 'package:fory/fory.dart';
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/context/ref_reader.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/map_serializers.dart';
import 'package:fory/src/util/hash_util.dart';
import 'package:test/test.dart';

part 'graph_memory_budget_test.fory.dart';

const Matcher _throwsGraphBudget = ThrowsGraphBudget();
const int _defaultGraphMemoryBytes = 128 * 1024 * 1024;
const int _referenceBytes = 4;
const int _structObjectOwnerBytes = 6 * _referenceBytes;
const int _listOwnerBytes = 6 * _referenceBytes;
const int _mapOwnerBytes = 8 * _referenceBytes;

int _objectGraphBytes(int fields) =>
    _structObjectOwnerBytes + fields * _referenceBytes;
int _listGraphBytes(int count) => _listOwnerBytes + count * _referenceBytes;
int _mapGraphBytes(int count) => _mapOwnerBytes + count * 2 * _referenceBytes;

@ForyStruct()
class BudgetGeneratedEnvelope {
  BudgetGeneratedEnvelope();

  @ListField(element: Int32Type(encoding: Encoding.fixed))
  List<int> ids = <int>[];

  @SetField(element: StringType())
  Set<String> tags = <String>{};

  @MapField(key: StringType(), value: Int32Type(encoding: Encoding.fixed))
  Map<String, int> counts = <String, int>{};
}

@ForyStruct()
class BudgetIgnoredEnvelope {
  BudgetIgnoredEnvelope();

  int value = 0;

  @ForyField(ignore: true)
  Object? ignored;
}

@ForyStruct()
class BudgetSelfNode {
  BudgetSelfNode();

  int id = 0;

  @ForyField(ref: true)
  BudgetSelfNode? next;

  @ListField(element: DeclaredType(ref: true))
  List<BudgetSelfNode> children = <BudgetSelfNode>[];
}

@ForyStruct()
class BudgetCompatibleListEnvelope {
  BudgetCompatibleListEnvelope();

  @ListField(element: Int32Type(encoding: Encoding.fixed))
  List<int> values = <int>[];
}

@ForyStruct()
class BudgetCompatibleArrayEnvelope {
  BudgetCompatibleArrayEnvelope();

  @ArrayField(element: Int32Type())
  Int32List values = Int32List(0);
}

class BudgetHierarchyBase {
  BudgetHierarchyBase();

  @ForyField(id: 30, type: Int32Type())
  int baseValue = 0;

  int _ignoredBase = 0;
}

mixin BudgetHierarchyMixin {
  @ForyField(id: 10, type: Int32Type())
  int mixinValue = 0;

  int _ignoredMixin = 0;
}

@ForyStruct(ignoreInheritedPrivateFields: true)
class BudgetHierarchyChild extends BudgetHierarchyBase
    with BudgetHierarchyMixin {
  BudgetHierarchyChild();

  @ForyField(id: 20, type: Int32Type())
  int childValue = 0;
}

@ForyStruct()
class BudgetHierarchyFlat {
  BudgetHierarchyFlat();

  @ForyField(id: 20, type: Int32Type())
  int childValue = 0;

  @ForyField(ignore: true)
  int ignoredMixin = 0;

  @ForyField(id: 30, type: Int32Type())
  int baseValue = 0;

  @ForyField(ignore: true)
  int ignoredBase = 0;

  @ForyField(id: 10, type: Int32Type())
  int mixinValue = 0;
}

@ForyStruct()
class BudgetCompatibleRemote {
  BudgetCompatibleRemote();

  @ForyField(id: 20, type: Int32Type())
  int childValue = 0;
}

final class ThrowsGraphBudget extends Matcher {
  const ThrowsGraphBudget();

  @override
  Description describe(Description description) {
    return description.add('throws a maxGraphMemoryBytes StateError');
  }

  @override
  bool matches(Object? item, Map<Object?, Object?> matchState) {
    if (item is! Function) {
      return false;
    }
    try {
      item();
    } on StateError catch (error) {
      return error.message.contains('maxGraphMemoryBytes');
    }
    return false;
  }
}

void _registerGenerated(Fory fory) {
  GraphMemoryBudgetTestForyModule.register(
    fory,
    BudgetGeneratedEnvelope,
    name: 'test.BudgetGeneratedEnvelope',
  );
}

void _registerIgnored(Fory fory) {
  GraphMemoryBudgetTestForyModule.register(
    fory,
    BudgetIgnoredEnvelope,
    name: 'test.BudgetIgnoredEnvelope',
  );
}

void _registerSelfNode(Fory fory) {
  GraphMemoryBudgetTestForyModule.register(
    fory,
    BudgetSelfNode,
    name: 'test.BudgetSelfNode',
  );
}

void _registerCompatibleList(Fory fory) {
  GraphMemoryBudgetTestForyModule.register(
    fory,
    BudgetCompatibleListEnvelope,
    name: 'test.BudgetCompatibleEnvelope',
  );
}

void _registerCompatibleArray(Fory fory) {
  GraphMemoryBudgetTestForyModule.register(
    fory,
    BudgetCompatibleArrayEnvelope,
    name: 'test.BudgetCompatibleEnvelope',
  );
}

void _registerHierarchy(Fory fory, Type type) {
  GraphMemoryBudgetTestForyModule.register(fory, type, id: 410);
}

void _registerCompatibleRemote(Fory fory) {
  GraphMemoryBudgetTestForyModule.register(
    fory,
    BudgetCompatibleRemote,
    name: 'test.BudgetHierarchy',
  );
}

void _registerCompatibleHierarchy(Fory fory) {
  GraphMemoryBudgetTestForyModule.register(
    fory,
    BudgetHierarchyChild,
    name: 'test.BudgetHierarchy',
  );
}

TypeDef _hierarchyTypeDef(Type type) {
  _registerHierarchy(Fory(), type);
  final resolver = TypeResolver(Config());
  resolver.registerGenerated(type, id: 410, namespace: null, typeName: null);
  return resolver.typeDefForResolved(resolver.resolvedRegisteredType(type));
}

ReadContext _readContext(
  Buffer buffer, {
  int maxGraphMemoryBytes = _defaultGraphMemoryBytes,
}) {
  final config = Config(maxGraphMemoryBytes: maxGraphMemoryBytes);
  final resolver = TypeResolver(config);
  return ReadContext(config, resolver, RefReader(), MetaStringReader(resolver))
    ..prepare(buffer);
}

Uint8List _serialize(Object? value) => Fory().serialize(value);

Object? _readWithBudget(Object? value, int budget) {
  return Fory(
    maxGraphMemoryBytes: budget,
  ).deserialize<Object?>(_serialize(value));
}

void main() {
  group('graph memory budget', () {
    test('fixed default applies to roots', () {
      final buffer = Buffer.wrap(Uint8List(17));
      final context = _readContext(buffer);

      expect(
        () => context.reserveGraphMemory(_defaultGraphMemoryBytes),
        returnsNormally,
      );
      expect(() => context.reserveGraphMemory(1), _throwsGraphBudget);
    });

    test('explicit config overrides default and invalid config fails', () {
      final buffer = Buffer.wrap(Uint8List(4096));
      final context = _readContext(buffer, maxGraphMemoryBytes: 31);

      expect(() => context.reserveGraphMemory(31), returnsNormally);
      expect(() => context.reserveGraphMemory(1), _throwsGraphBudget);

      expect(() => Fory(maxGraphMemoryBytes: 0), throwsA(isA<ArgumentError>()));
      expect(
        () => Fory(maxGraphMemoryBytes: -2),
        throwsA(isA<ArgumentError>()),
      );
    });

    test('uses parent storage for nested empty containers', () {
      final value = <Object?>[<Object?>[]];

      expect(
        () =>
            _readWithBudget(value, _listGraphBytes(1) + _listGraphBytes(0) - 1),
        _throwsGraphBudget,
      );
      expect(
        _readWithBudget(value, _listGraphBytes(1) + _listGraphBytes(0)),
        equals(value),
      );
    });

    test('reserves sibling containers cumulatively', () {
      final value = <Object?>[<Object?>[], <Object?>[], <Object?>[]];

      expect(
        () => _readWithBudget(
          value,
          _listGraphBytes(3) + 3 * _listGraphBytes(0) - 1,
        ),
        _throwsGraphBudget,
      );
      expect(
        _readWithBudget(value, _listGraphBytes(3) + 3 * _listGraphBytes(0)),
        equals(value),
      );
    });

    test('reserves generated self reference once', () {
      final node =
          BudgetSelfNode()
            ..id = 7
            ..next = null;
      node.next = node;
      node.children = <BudgetSelfNode>[node];

      final writer = Fory();
      _registerSelfNode(writer);
      final bytes = writer.serialize(node);
      final required = _objectGraphBytes(3) + _listGraphBytes(1);

      final failingReader = Fory(maxGraphMemoryBytes: required - 1);
      _registerSelfNode(failingReader);
      expect(
        () => failingReader.deserialize<BudgetSelfNode>(bytes),
        _throwsGraphBudget,
      );

      final passingReader = Fory(maxGraphMemoryBytes: required);
      _registerSelfNode(passingReader);
      final roundTrip = passingReader.deserialize<BudgetSelfNode>(bytes);
      expect(identical(roundTrip, roundTrip.next), isTrue);
      expect(roundTrip.children, hasLength(1));
      expect(identical(roundTrip, roundTrip.children.single), isTrue);
    });

    test('reserves ignored field storage', () {
      final writer = Fory();
      _registerIgnored(writer);
      final bytes = writer.serialize(
        BudgetIgnoredEnvelope()
          ..value = 7
          ..ignored = Object(),
      );
      final required = _objectGraphBytes(2);

      final failingReader = Fory(maxGraphMemoryBytes: required - 1);
      _registerIgnored(failingReader);
      expect(
        () => failingReader.deserialize<BudgetIgnoredEnvelope>(bytes),
        _throwsGraphBudget,
      );

      final passingReader = Fory(maxGraphMemoryBytes: required);
      _registerIgnored(passingReader);
      final roundTrip = passingReader.deserialize<BudgetIgnoredEnvelope>(bytes);
      expect(roundTrip.value, equals(7));
      expect(roundTrip.ignored, isNull);
    });

    test('reserves one flattened hierarchy owner', () {
      final writer = Fory();
      _registerHierarchy(writer, BudgetHierarchyChild);
      final bytes = writer.serialize(
        BudgetHierarchyChild()
          ..baseValue = 30
          .._ignoredBase = 31
          ..mixinValue = 10
          .._ignoredMixin = 11
          ..childValue = 20,
      );
      final required = _objectGraphBytes(5);

      final failingReader = Fory(maxGraphMemoryBytes: required - 1);
      _registerHierarchy(failingReader, BudgetHierarchyChild);
      expect(
        () => failingReader.deserialize<BudgetHierarchyChild>(bytes),
        _throwsGraphBudget,
      );

      final passingReader = Fory(maxGraphMemoryBytes: required);
      _registerHierarchy(passingReader, BudgetHierarchyChild);
      final roundTrip = passingReader.deserialize<BudgetHierarchyChild>(bytes);
      expect(roundTrip.baseValue, equals(30));
      expect(roundTrip.mixinValue, equals(10));
      expect(roundTrip.childValue, equals(20));
      expect(roundTrip._ignoredBase, isZero);
      expect(roundTrip._ignoredMixin, isZero);
    });

    test('reserves map entries', () {
      final value = <Object?, Object?>{'a': 1};

      expect(
        () => _readWithBudget(value, _mapGraphBytes(1) - 1),
        _throwsGraphBudget,
      );
      expect(_readWithBudget(value, _mapGraphBytes(1)), equals(value));
    });

    test('reserves generic set owner once', () {
      final value = <Object?>{'x', 7};
      final required = _listGraphBytes(value.length);

      expect(() => _readWithBudget(value, required - 1), _throwsGraphBudget);
      expect(_readWithBudget(value, required), equals(value));
    });

    test('reserves generated list set and map reads', () {
      final writer = Fory();
      _registerGenerated(writer);
      final bytes = writer.serialize(
        BudgetGeneratedEnvelope()
          ..ids = <int>[1]
          ..tags = <String>{'x'}
          ..counts = <String, int>{'one': 1},
      );

      final required =
          _objectGraphBytes(3) +
          _listGraphBytes(1) +
          _listGraphBytes(1) +
          _mapGraphBytes(1);
      final failingReader = Fory(maxGraphMemoryBytes: required - 1);
      _registerGenerated(failingReader);
      expect(
        () => failingReader.deserialize<BudgetGeneratedEnvelope>(bytes),
        _throwsGraphBudget,
      );

      final passingReader = Fory(maxGraphMemoryBytes: required);
      _registerGenerated(passingReader);
      final roundTrip = passingReader.deserialize<BudgetGeneratedEnvelope>(
        bytes,
      );
      expect(roundTrip.ids, equals(<int>[1]));
      expect(roundTrip.tags, equals(<String>{'x'}));
      expect(roundTrip.counts, equals(<String, int>{'one': 1}));
    });

    test('skips compatible list to typed array leaf', () {
      final listWriter = Fory();
      _registerCompatibleList(listWriter);
      final listBytes = listWriter.serialize(
        BudgetCompatibleListEnvelope()..values = <int>[1, 2, 3],
      );

      final arrayRequired = _objectGraphBytes(1);
      final arrayFail = Fory(maxGraphMemoryBytes: arrayRequired - 1);
      _registerCompatibleArray(arrayFail);
      expect(
        () => arrayFail.deserialize<BudgetCompatibleArrayEnvelope>(listBytes),
        _throwsGraphBudget,
      );

      final arrayPass = Fory(maxGraphMemoryBytes: arrayRequired);
      _registerCompatibleArray(arrayPass);
      expect(
        arrayPass
            .deserialize<BudgetCompatibleArrayEnvelope>(listBytes)
            .values
            .toList(),
        equals(<int>[1, 2, 3]),
      );

      final arrayWriter = Fory();
      _registerCompatibleArray(arrayWriter);
      final arrayBytes = arrayWriter.serialize(
        BudgetCompatibleArrayEnvelope()
          ..values = Int32List.fromList(<int>[1, 2, 3]),
      );

      final listRequired = _objectGraphBytes(1) + _listGraphBytes(3);
      final listFail = Fory(maxGraphMemoryBytes: listRequired - 1);
      _registerCompatibleList(listFail);
      expect(
        () => listFail.deserialize<BudgetCompatibleListEnvelope>(arrayBytes),
        _throwsGraphBudget,
      );

      final listPass = Fory(maxGraphMemoryBytes: listRequired);
      _registerCompatibleList(listPass);
      expect(
        listPass.deserialize<BudgetCompatibleListEnvelope>(arrayBytes).values,
        equals(<int>[1, 2, 3]),
      );
    });

    test('compatible reads reserve the local flattened shape', () {
      final writer = Fory(compatible: true);
      _registerCompatibleRemote(writer);
      final bytes = writer.serialize(BudgetCompatibleRemote()..childValue = 20);
      final required = _objectGraphBytes(5);

      final failingReader = Fory(
        compatible: true,
        maxGraphMemoryBytes: required - 1,
      );
      _registerCompatibleHierarchy(failingReader);
      expect(
        () => failingReader.deserialize<BudgetHierarchyChild>(bytes),
        _throwsGraphBudget,
      );

      final passingReader = Fory(
        compatible: true,
        maxGraphMemoryBytes: required,
      );
      _registerCompatibleHierarchy(passingReader);
      final roundTrip = passingReader.deserialize<BudgetHierarchyChild>(bytes);
      expect(roundTrip.baseValue, isZero);
      expect(roundTrip.mixinValue, isZero);
      expect(roundTrip.childValue, equals(20));
      expect(roundTrip._ignoredBase, isZero);
      expect(roundTrip._ignoredMixin, isZero);
    });

    test('skips strings binary and dense typed arrays', () {
      final fory = Fory(maxGraphMemoryBytes: 1);
      final text = List<String>.filled(128, 'x').join();

      expect(fory.deserialize<String>(Fory().serialize(text)), hasLength(128));
      expect(
        fory.deserialize<Uint8List>(Fory().serialize(Uint8List(128))).length,
        equals(128),
      );
      expect(
        fory.deserialize<Int32List>(Fory().serialize(Int32List(32))).length,
        equals(32),
      );
    });

    test('keeps byte availability checks before allocation', () {
      final listBuffer =
          Buffer()
            ..writeVarUint32(64)
            ..writeUint8(0);
      final listContext = _readContext(listBuffer);
      expect(
        () => ListSerializer.readPayload(listContext, null),
        throwsStateError,
      );

      final mapBuffer = Buffer()..writeVarUint32(64);
      final mapContext = _readContext(mapBuffer);
      expect(
        () => MapSerializer.readPayload(mapContext, null, null),
        throwsStateError,
      );
    });

    test('rejects invalid map chunk sizes before type metadata', () {
      for (final chunkSize in <int>[0, 2]) {
        final buffer =
            Buffer()
              ..writeVarUint32(1)
              ..writeUint8(0)
              ..writeUint8(chunkSize);
        final context = _readContext(buffer);

        try {
          expect(
            () => MapSerializer.readPayload(context, null, null),
            throwsStateError,
            reason: 'chunkSize=$chunkSize',
          );
        } finally {
          context.reset();
        }
      }
    });
  });

  group('flattened hierarchy schema', () {
    test('matches flat TypeDef bytes hash and global order', () {
      final inherited = _hierarchyTypeDef(BudgetHierarchyChild);
      final flat = _hierarchyTypeDef(BudgetHierarchyFlat);

      expect(
        inherited.fields.map((field) => field.id),
        orderedEquals(<int>[10, 20, 30]),
      );
      expect(
        flat.fields.map((field) => field.id),
        orderedEquals(<int>[10, 20, 30]),
      );
      expect(inherited.encoded, orderedEquals(flat.encoded));
      expect(inherited.header, equals(flat.header));
      expect(schemaFingerprint(inherited), equals(schemaFingerprint(flat)));
      expect(schemaHash(inherited), equals(schemaHash(flat)));
    });
  });
}
