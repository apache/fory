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
import 'package:test/test.dart';

part 'enum_union_serializer_test.fory.dart';

@ForyStruct()
enum SimpleColor {
  red,
  green,
  blue,
}

@ForyStruct()
enum StableCodeV1 {
  alpha(7),
  beta(11);

  const StableCodeV1(this.rawValue);

  final int rawValue;

  static StableCodeV1 fromRawValue(int rawValue) {
    return switch (rawValue) {
      7 => StableCodeV1.alpha,
      11 => StableCodeV1.beta,
      _ => throw StateError('Unknown StableCodeV1 raw value $rawValue.'),
    };
  }
}

@ForyStruct()
enum StableCodeV2 {
  betaRenamed(11),
  alphaRenamed(7);

  const StableCodeV2(this.rawValue);

  final int rawValue;

  static StableCodeV2 fromRawValue(int rawValue) {
    return switch (rawValue) {
      7 => StableCodeV2.alphaRenamed,
      11 => StableCodeV2.betaRenamed,
      _ => throw StateError('Unknown StableCodeV2 raw value $rawValue.'),
    };
  }
}

@ForyStruct()
class EnumEnvelope {
  EnumEnvelope();

  SimpleColor color = SimpleColor.red;
}

@ForyStruct()
class UnionLeaf {
  UnionLeaf();

  String label = '';

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is UnionLeaf && other.label == label;

  @override
  int get hashCode => label.hashCode;
}

@ForyUnion()
final class TestUnion {
  const TestUnion._(this.index, this.value);

  final int index;
  final Object value;

  factory TestUnion.ofString(String value) => TestUnion._(0, value);

  factory TestUnion.ofInt(Int64 value) => TestUnion._(1, value);

  factory TestUnion.ofLeaf(UnionLeaf value) => TestUnion._(2, value);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is TestUnion && other.index == index && other.value == value;

  @override
  int get hashCode => Object.hash(index, value);
}

final class TestUnionSerializer extends UnionSerializer<TestUnion> {
  const TestUnionSerializer();

  @override
  int caseId(TestUnion value) => value.index;

  @override
  Object caseValue(TestUnion value) => value.value;

  @override
  TestUnion buildValue(int index, Object? value) {
    if (index == 0 && value is String) {
      return TestUnion.ofString(value);
    }
    if (index == 1 && value is Int64) {
      return TestUnion.ofInt(value);
    }
    if (index == 1 && value is int) {
      return TestUnion.ofInt(Int64(value));
    }
    if (index == 2 && value is UnionLeaf) {
      return TestUnion.ofLeaf(value);
    }
    throw StateError('Unsupported TestUnion case $index with value $value.');
  }
}

@ForyStruct()
class UnionEnvelope {
  UnionEnvelope();

  TestUnion? payload;
}

void _registerEnumAndUnionTypes(Fory fory) {
  EnumUnionSerializerTestFory.register(
    fory,
    SimpleColor,
    namespace: 'test',
    typeName: 'SimpleColor',
  );
  EnumUnionSerializerTestFory.register(
    fory,
    EnumEnvelope,
    namespace: 'test',
    typeName: 'EnumEnvelope',
  );
  EnumUnionSerializerTestFory.register(
    fory,
    UnionLeaf,
    namespace: 'test',
    typeName: 'UnionLeaf',
  );
  EnumUnionSerializerTestFory.register(
    fory,
    UnionEnvelope,
    namespace: 'test',
    typeName: 'UnionEnvelope',
  );
  fory.registerSerializer(
    TestUnion,
    const TestUnionSerializer(),
    namespace: 'test',
    typeName: 'TestUnion',
  );
}

void _registerRawEnumV1(Fory fory) {
  EnumUnionSerializerTestFory.register(
    fory,
    StableCodeV1,
    namespace: 'enum',
    typeName: 'StableCode',
  );
}

void _registerRawEnumV2(Fory fory) {
  EnumUnionSerializerTestFory.register(
    fory,
    StableCodeV2,
    namespace: 'enum',
    typeName: 'StableCode',
  );
}

void main() {
  group('enum serializer', () {
    test('round-trips root enums and generated enum fields', () {
      final fory = Fory();
      _registerEnumAndUnionTypes(fory);

      final rootRoundTrip =
          fory.deserialize<SimpleColor>(fory.serialize(SimpleColor.blue));
      final envelopeRoundTrip = fory.deserialize<EnumEnvelope>(
        fory.serialize(EnumEnvelope()..color = SimpleColor.green),
      );

      expect(rootRoundTrip, equals(SimpleColor.blue));
      expect(envelopeRoundTrip.color, equals(SimpleColor.green));
    });

    test('uses raw enum values across reorder and rename', () {
      final writer = Fory();
      final reader = Fory();
      _registerRawEnumV1(writer);
      _registerRawEnumV2(reader);

      expect(
        reader.deserialize<StableCodeV2>(writer.serialize(StableCodeV1.alpha)),
        equals(StableCodeV2.alphaRenamed),
      );
      expect(
        reader.deserialize<StableCodeV2>(writer.serialize(StableCodeV1.beta)),
        equals(StableCodeV2.betaRenamed),
      );
    });
  });

  group('union serializer', () {
    test('round-trips manual union roots for every arm', () {
      final fory = Fory();
      _registerEnumAndUnionTypes(fory);

      final cases = <TestUnion>[
        TestUnion.ofString('alpha'),
        TestUnion.ofInt(Int64(1234)),
        TestUnion.ofLeaf(UnionLeaf()..label = 'leaf'),
      ];

      for (final value in cases) {
        final roundTrip = fory.deserialize<TestUnion>(fory.serialize(value));
        expect(roundTrip, equals(value));
      }
    });

    test('round-trips nullable union fields in schema-consistent mode', () {
      final fory = Fory();
      _registerEnumAndUnionTypes(fory);

      final leafEnvelope = UnionEnvelope()
        ..payload = TestUnion.ofLeaf(UnionLeaf()..label = 'branch');
      final nullEnvelope = UnionEnvelope();

      final leafRoundTrip = fory.deserialize<UnionEnvelope>(
        fory.serialize(leafEnvelope),
      );
      final nullRoundTrip = fory.deserialize<UnionEnvelope>(
        fory.serialize(nullEnvelope),
      );

      expect(leafRoundTrip.payload, equals(leafEnvelope.payload));
      expect(nullRoundTrip.payload, isNull);
    });

    test('round-trips union fields in compatible mode', () {
      final fory = Fory(compatible: true);
      _registerEnumAndUnionTypes(fory);

      final roundTrip = fory.deserialize<UnionEnvelope>(
        fory.serialize(
          UnionEnvelope()..payload = TestUnion.ofString('compatible'),
        ),
      );

      expect(roundTrip.payload, equals(TestUnion.ofString('compatible')));
    });
  });
}
