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
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/map_serializers.dart';
import 'package:test/test.dart';

part 'graph_memory_budget_test.fory.dart';

const Matcher _throwsGraphBudget = ThrowsGraphBudget();
const int _defaultGraphMemoryBytes = 128 * 1024 * 1024;
const int _objectBytes = 1;
const int _referenceBytes = 4;

int _objectGraphBytes(int fields) => _objectBytes + fields * _referenceBytes;
int _listGraphBytes(int count) => _objectBytes + count * _referenceBytes;
int _mapGraphBytes(int count) => _objectBytes + count * 2 * _referenceBytes;

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
        context.effectiveGraphMemoryBytes,
        equals(_defaultGraphMemoryBytes),
      );
      expect(
        () => context.reserveGraphMemory(_defaultGraphMemoryBytes),
        returnsNormally,
      );
      expect(() => context.reserveGraphMemory(1), _throwsGraphBudget);
    });

    test('explicit config overrides default and non-positive disables', () {
      final buffer = Buffer.wrap(Uint8List(4096));
      final context = _readContext(buffer, maxGraphMemoryBytes: 31);

      expect(context.effectiveGraphMemoryBytes, equals(31));
      expect(() => context.reserveGraphMemory(31), returnsNormally);
      expect(() => context.reserveGraphMemory(1), _throwsGraphBudget);

      final disabled = _readContext(buffer, maxGraphMemoryBytes: 0);
      expect(disabled.effectiveGraphMemoryBytes, equals(0));
      expect(
        () => disabled.reserveGraphMemory(_defaultGraphMemoryBytes + 1),
        returnsNormally,
      );
      expect(() => Fory(maxGraphMemoryBytes: -2), returnsNormally);
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

    test('reserves map entries', () {
      final value = <Object?, Object?>{'a': 1};

      expect(
        () => _readWithBudget(value, _mapGraphBytes(1) - 1),
        _throwsGraphBudget,
      );
      expect(_readWithBudget(value, _mapGraphBytes(1)), equals(value));
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

    test('reserves compatible list array materialization', () {
      final listWriter = Fory();
      _registerCompatibleList(listWriter);
      final listBytes = listWriter.serialize(
        BudgetCompatibleListEnvelope()..values = <int>[1, 2, 3],
      );

      final required = _objectGraphBytes(1) + _objectBytes + 3 * 4;
      final arrayFail = Fory(maxGraphMemoryBytes: required - 1);
      _registerCompatibleArray(arrayFail);
      expect(
        () => arrayFail.deserialize<BudgetCompatibleArrayEnvelope>(listBytes),
        _throwsGraphBudget,
      );

      final arrayPass = Fory(maxGraphMemoryBytes: required);
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

      final listFail = Fory(maxGraphMemoryBytes: required - 1);
      _registerCompatibleList(listFail);
      expect(
        () => listFail.deserialize<BudgetCompatibleListEnvelope>(arrayBytes),
        _throwsGraphBudget,
      );

      final listPass = Fory(maxGraphMemoryBytes: required);
      _registerCompatibleList(listPass);
      expect(
        listPass.deserialize<BudgetCompatibleListEnvelope>(arrayBytes).values,
        equals(<int>[1, 2, 3]),
      );
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
  });
}
