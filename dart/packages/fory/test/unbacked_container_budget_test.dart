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

import 'package:fory/fory.dart';
import 'package:fory/src/codegen/generated_registry.dart';
import 'package:test/test.dart';

part 'unbacked_container_budget_test.fory.dart';

final class EmptyStub {
  const EmptyStub();
}

final class EmptyStubSerializer extends Serializer<EmptyStub> {
  const EmptyStubSerializer();

  @override
  void write(WriteContext context, EmptyStub value) {}

  @override
  EmptyStub read(ReadContext context) => EmptyStub();
}

final class SparseStub {
  const SparseStub();
}

final class SparseStubSerializer extends Serializer<SparseStub> {
  var _writeIndex = 0;
  var _readIndex = 0;

  @override
  void write(WriteContext context, SparseStub value) {
    if ((_writeIndex++ & 1) == 0) {
      context.buffer.writeUint8(0);
    }
  }

  @override
  SparseStub read(ReadContext context) {
    if ((_readIndex++ & 1) == 0) {
      context.buffer.readUint8();
    }
    return const SparseStub();
  }
}

@ForyStruct()
final class BudgetEmpty {
  BudgetEmpty();
}

@ForyStruct()
final class BudgetScalar {
  BudgetScalar();

  int value = 0;
}

void main() {
  test('validates config range', () {
    expect(
      Config().maxUnbackedContainerItems,
      Config.defaultMaxUnbackedContainerItems,
    );
    expect(() => Config(maxUnbackedContainerItems: -1), throwsArgumentError);
    expect(Config(maxUnbackedContainerItems: 0).maxUnbackedContainerItems, 0);
  });

  test('bounds custom collection work', () {
    final writer = _emptyFory(8192);
    final bytes = writer.serialize(
      List<EmptyStub>.filled(1025, const EmptyStub()),
    );

    expect(
      () => _emptyFory(1024).deserialize<Object?>(bytes),
      throwsA(anything),
    );
    expect((_emptyFory(1025).deserialize<Object?>(bytes) as List).length, 1025);
  });

  test('charges final set window', () {
    final values = <EmptyStub>{EmptyStub(), EmptyStub(), EmptyStub()};
    final bytes = _emptyFory(8192).serialize(values);

    expect(() => _emptyFory(2).deserialize<Object?>(bytes), throwsA(anything));
    expect((_emptyFory(3).deserialize<Object?>(bytes) as Set).length, 3);
  });

  test('charges existing map chunks', () {
    final values = <EmptyStub, EmptyStub>{
      EmptyStub(): EmptyStub(),
      EmptyStub(): EmptyStub(),
      EmptyStub(): EmptyStub(),
    };
    final bytes = _emptyFory(8192).serialize(values);

    expect(() => _emptyFory(2).deserialize<Object?>(bytes), throwsA(anything));
    expect((_emptyFory(3).deserialize<Object?>(bytes) as Map).length, 3);
  });

  test('shares budget across nested collections', () {
    final values = <List<EmptyStub>>[
      List<EmptyStub>.filled(3, const EmptyStub()),
      List<EmptyStub>.filled(3, const EmptyStub()),
    ];
    final bytes = _emptyFory(8192).serialize(values);

    expect(() => _emptyFory(5).deserialize<Object?>(bytes), throwsA(anything));
    expect((_emptyFory(6).deserialize<Object?>(bytes) as List).length, 2);
  });

  test('offsets items with bytes read', () {
    final bytes = _sparseFory(
      8192,
    ).serialize(List<SparseStub>.filled(2048, const SparseStub()));

    expect(
      () => _sparseFory(1023).deserialize<Object?>(bytes),
      throwsA(anything),
    );
    expect(
      (_sparseFory(1024).deserialize<Object?>(bytes) as List).length,
      2048,
    );
  });

  test('resets budget after failed root', () {
    final writer = _emptyFory(8192);
    final rejected = writer.serialize(
      List<EmptyStub>.filled(2, const EmptyStub()),
    );
    final accepted = writer.serialize(<EmptyStub>[const EmptyStub()]);
    final reader = _emptyFory(1);

    expect(() => reader.deserialize<Object?>(rejected), throwsA(anything));
    expect((reader.deserialize<Object?>(accepted) as List).length, 1);
  });

  test('generated progress facts are compile-time metadata', () {
    final fory = Fory();
    UnbackedContainerBudgetTestForyModule.register(
      fory,
      BudgetEmpty,
      name: 'budget.GeneratedEmpty',
    );
    UnbackedContainerBudgetTestForyModule.register(
      fory,
      BudgetScalar,
      name: 'budget.GeneratedScalar',
    );

    expect(
      GeneratedTypeCatalog.lookup(BudgetEmpty)!.readDataAlwaysAdvances,
      isFalse,
    );
    expect(
      GeneratedTypeCatalog.lookup(BudgetScalar)!.readDataAlwaysAdvances,
      isTrue,
    );
  });

  test('generated empty struct uses budget', () {
    final writer = _generatedFory(8192);
    final bytes = writer.serialize(
      List<BudgetEmpty>.generate(3, (_) => BudgetEmpty()),
    );

    expect(
      () => _generatedFory(2).deserialize<Object?>(bytes),
      throwsA(anything),
    );
    expect((_generatedFory(3).deserialize<Object?>(bytes) as List).length, 3);
  });

  test('compatible collection uses remote progress', () {
    final positiveWriter = _generatedSchemaFory(BudgetScalar, 0);
    final emptyReader = _generatedSchemaFory(BudgetEmpty, 0);
    final positive = positiveWriter.serialize(
      List<BudgetScalar>.generate(3, (index) => BudgetScalar()..value = index),
    );
    expect((emptyReader.deserialize<Object?>(positive) as List).length, 3);

    final emptyWriter = _generatedSchemaFory(BudgetEmpty, 0);
    final positiveReader = _generatedSchemaFory(BudgetScalar, 0);
    final empty = emptyWriter.serialize(
      List<BudgetEmpty>.generate(3, (_) => BudgetEmpty()),
    );
    expect(() => positiveReader.deserialize<Object?>(empty), throwsA(anything));
  });

  test('compatible map uses remote progress', () {
    final positiveWriter = _generatedSchemaFory(BudgetScalar, 0);
    final emptyReader = _generatedSchemaFory(BudgetEmpty, 0);
    final positive = <BudgetScalar, BudgetScalar>{
      for (var index = 0; index < 3; index += 1)
        _budgetScalar(index): _budgetScalar(index + 10),
    };
    expect(
      (emptyReader.deserialize<Object?>(positiveWriter.serialize(positive))
              as Map)
          .length,
      3,
    );

    final emptyWriter = _generatedSchemaFory(BudgetEmpty, 0);
    final positiveReader = _generatedSchemaFory(BudgetScalar, 0);
    final empty = <BudgetEmpty, BudgetEmpty>{
      for (var index = 0; index < 3; index += 1) BudgetEmpty(): BudgetEmpty(),
    };
    expect(
      () => positiveReader.deserialize<Object?>(emptyWriter.serialize(empty)),
      throwsA(anything),
    );
  });

  test('positive bodies do not spend budget', () {
    final fory = Fory(maxUnbackedContainerItems: 0);
    final values = List<int>.generate(10000, (index) => index);

    expect(fory.deserialize<Object?>(fory.serialize(values)), values);
  });
}

Fory _emptyFory(int maxItems) {
  final fory = Fory(maxUnbackedContainerItems: maxItems);
  fory.registerSerializer(
    EmptyStub,
    const EmptyStubSerializer(),
    name: 'budget.EmptyStub',
  );
  return fory;
}

Fory _sparseFory(int maxItems) {
  final fory = Fory(maxUnbackedContainerItems: maxItems);
  fory.registerSerializer(
    SparseStub,
    SparseStubSerializer(),
    name: 'budget.SparseStub',
  );
  return fory;
}

Fory _generatedFory(int maxItems) {
  final fory = Fory(maxUnbackedContainerItems: maxItems);
  UnbackedContainerBudgetTestForyModule.register(
    fory,
    BudgetEmpty,
    name: 'budget.GeneratedEmpty',
  );
  return fory;
}

Fory _generatedSchemaFory(Type type, int maxItems) {
  final fory = Fory(maxUnbackedContainerItems: maxItems);
  UnbackedContainerBudgetTestForyModule.register(
    fory,
    type,
    name: 'budget.RemoteProgress',
  );
  return fory;
}

BudgetScalar _budgetScalar(int value) => BudgetScalar()..value = value;
