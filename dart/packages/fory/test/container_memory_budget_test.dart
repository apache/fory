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

part 'container_memory_budget_test.fory.dart';

const Matcher _throwsContainerBudget = ThrowsContainerBudget();

@ForyStruct()
class BudgetGeneratedEnvelope {
  BudgetGeneratedEnvelope();

  @ListField(element: Int32Type(encoding: Encoding.fixed))
  List<int> ids = <int>[];

  @SetField(element: StringType())
  Set<String> tags = <String>{};

  @MapField(
    key: StringType(),
    value: Int32Type(encoding: Encoding.fixed),
  )
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

final class ThrowsContainerBudget extends Matcher {
  const ThrowsContainerBudget();

  @override
  Description describe(Description description) {
    return description.add('throws a maxContainerMemoryBytes StateError');
  }

  @override
  bool matches(Object? item, Map<Object?, Object?> matchState) {
    if (item is! Function) {
      return false;
    }
    try {
      item();
    } on StateError catch (error) {
      return error.message.contains('maxContainerMemoryBytes');
    }
    return false;
  }
}

void _registerGenerated(Fory fory) {
  ContainerMemoryBudgetTestForyModule.register(
    fory,
    BudgetGeneratedEnvelope,
    name: 'test.BudgetGeneratedEnvelope',
  );
}

void _registerCompatibleList(Fory fory) {
  ContainerMemoryBudgetTestForyModule.register(
    fory,
    BudgetCompatibleListEnvelope,
    name: 'test.BudgetCompatibleEnvelope',
  );
}

void _registerCompatibleArray(Fory fory) {
  ContainerMemoryBudgetTestForyModule.register(
    fory,
    BudgetCompatibleArrayEnvelope,
    name: 'test.BudgetCompatibleEnvelope',
  );
}

ReadContext _readContext(Buffer buffer, {int maxContainerMemoryBytes = -1}) {
  final config = Config(maxContainerMemoryBytes: maxContainerMemoryBytes);
  final resolver = TypeResolver(config);
  return ReadContext(config, resolver, RefReader(), MetaStringReader(resolver))
    ..prepare(buffer);
}

Uint8List _serialize(Object? value) => Fory().serialize(value);

Object? _readWithBudget(Object? value, int budget) {
  return Fory(
    maxContainerMemoryBytes: budget,
  ).deserialize<Object?>(_serialize(value));
}

void main() {
  group('container memory budget', () {
    test('known length auto derives from input bytes', () {
      final buffer = Buffer.wrap(Uint8List(17));
      final context = _readContext(buffer);

      expect(context.effectiveContainerMemoryBytes, equals(17 * 8 + 64 * 1024));
      expect(
        () => context.reserveContainerMemory(17 * 8 + 64 * 1024),
        returnsNormally,
      );
      expect(() => context.reserveContainerMemory(1), _throwsContainerBudget);
    });

    test('explicit config overrides auto', () {
      final buffer = Buffer.wrap(Uint8List(4096));
      final context = _readContext(buffer, maxContainerMemoryBytes: 31);

      expect(context.effectiveContainerMemoryBytes, equals(31));
      expect(() => context.reserveContainerMemory(31), returnsNormally);
      expect(() => context.reserveContainerMemory(1), _throwsContainerBudget);
      expect(() => Fory(maxContainerMemoryBytes: 0), throwsArgumentError);
      expect(() => Fory(maxContainerMemoryBytes: -2), throwsArgumentError);
    });

    test('charges nested empty containers', () {
      final value = <Object?>[<Object?>[]];

      expect(() => _readWithBudget(value, 51), _throwsContainerBudget);
      expect(_readWithBudget(value, 52), equals(value));
    });

    test('charges sibling containers cumulatively', () {
      final value = <Object?>[<Object?>[], <Object?>[], <Object?>[]];

      expect(() => _readWithBudget(value, 107), _throwsContainerBudget);
      expect(_readWithBudget(value, 108), equals(value));
    });

    test('charges map table and entries', () {
      final value = <Object?, Object?>{'a': 1};

      expect(() => _readWithBudget(value, 99), _throwsContainerBudget);
      expect(_readWithBudget(value, 100), equals(value));
    });

    test('charges generated list set and map reads', () {
      final writer = Fory();
      _registerGenerated(writer);
      final bytes = writer.serialize(
        BudgetGeneratedEnvelope()
          ..ids = <int>[1]
          ..tags = <String>{'x'}
          ..counts = <String, int>{'one': 1},
      );

      final failingReader = Fory(maxContainerMemoryBytes: 183);
      _registerGenerated(failingReader);
      expect(
        () => failingReader.deserialize<BudgetGeneratedEnvelope>(bytes),
        _throwsContainerBudget,
      );

      final passingReader = Fory(maxContainerMemoryBytes: 184);
      _registerGenerated(passingReader);
      final roundTrip = passingReader.deserialize<BudgetGeneratedEnvelope>(
        bytes,
      );
      expect(roundTrip.ids, equals(<int>[1]));
      expect(roundTrip.tags, equals(<String>{'x'}));
      expect(roundTrip.counts, equals(<String, int>{'one': 1}));
    });

    test('charges compatible list array materialization', () {
      final listWriter = Fory();
      _registerCompatibleList(listWriter);
      final listBytes = listWriter.serialize(
        BudgetCompatibleListEnvelope()..values = <int>[1, 2, 3],
      );

      final arrayFail = Fory(maxContainerMemoryBytes: 27);
      _registerCompatibleArray(arrayFail);
      expect(
        () => arrayFail.deserialize<BudgetCompatibleArrayEnvelope>(listBytes),
        _throwsContainerBudget,
      );

      final arrayPass = Fory(maxContainerMemoryBytes: 28);
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

      final listFail = Fory(maxContainerMemoryBytes: 35);
      _registerCompatibleList(listFail);
      expect(
        () => listFail.deserialize<BudgetCompatibleListEnvelope>(arrayBytes),
        _throwsContainerBudget,
      );

      final listPass = Fory(maxContainerMemoryBytes: 36);
      _registerCompatibleList(listPass);
      expect(
        listPass.deserialize<BudgetCompatibleListEnvelope>(arrayBytes).values,
        equals(<int>[1, 2, 3]),
      );
    });

    test('skips strings binary and dense typed arrays', () {
      final fory = Fory(maxContainerMemoryBytes: 1);
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
      final listBuffer = Buffer()
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
