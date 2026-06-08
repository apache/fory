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
import 'package:test/test.dart';

part 'runtime_validation_test.fory.dart';

final class PlainManualValue {
  PlainManualValue(this.value);

  final String value;
}

final class PlainManualValueSerializer extends Serializer<PlainManualValue> {
  const PlainManualValueSerializer();

  @override
  void write(WriteContext context, PlainManualValue value) {
    context.writeString(value.value);
  }

  @override
  PlainManualValue read(ReadContext context) {
    return PlainManualValue(context.readString());
  }
}

@ForyStruct()
class FreshGeneratedValue {
  FreshGeneratedValue();

  String value = '';
}

abstract interface class DynamicAnimal {
  String get name;
}

@ForyStruct()
class DynamicDog implements DynamicAnimal {
  DynamicDog();

  @override
  String name = '';
}

@ForyStruct()
class DynamicCat implements DynamicAnimal {
  DynamicCat();

  @override
  String name = '';

  int lives = 9;
}

@ForyStruct()
class DynamicAnimalEnvelope {
  DynamicAnimalEnvelope();

  @ForyField(dynamic: true)
  DynamicAnimal? animal;
}

@ForyStruct()
class ExplicitUnknownEnvelope {
  ExplicitUnknownEnvelope();

  @ForyField(dynamic: false)
  Object value = 'unset';
}

@ForyStruct()
class SkipEnvelope {
  SkipEnvelope();

  String visible = '';

  @ForyField(skip: true)
  String skipped = 'local-default';
}

@ForyStruct()
class SkipCompatibleV1 {
  SkipCompatibleV1();

  @ForyField(id: 1)
  String visible = '';

  @ForyField(id: 2)
  String skipped = 'writer-default';
}

@ForyStruct()
class SkipCompatibleV2 {
  SkipCompatibleV2();

  @ForyField(id: 1)
  String visible = '';

  @ForyField(id: 2, skip: true)
  String skipped = 'reader-default';
}

@ForyStruct()
class SchemaVersionV1 {
  SchemaVersionV1();

  String label = '';
}

@ForyStruct()
class SchemaVersionV2 {
  SchemaVersionV2();

  String label = '';
  int count = 0;
}

@ForyStruct()
class DuplicateFieldIdOrder {
  DuplicateFieldIdOrder();

  @ForyField(id: 1)
  String first = '';

  @ForyField(id: 1)
  String second = '';
}

void _registerValidationTypes(Fory fory) {
  RuntimeValidationTestForyModule.register(
    fory,
    DynamicDog,
    name: 'validation.DynamicDog',
  );
  RuntimeValidationTestForyModule.register(
    fory,
    DynamicCat,
    name: 'validation.DynamicCat',
  );
  RuntimeValidationTestForyModule.register(
    fory,
    DynamicAnimalEnvelope,
    name: 'validation.DynamicAnimalEnvelope',
  );
  RuntimeValidationTestForyModule.register(
    fory,
    ExplicitUnknownEnvelope,
    name: 'validation.ExplicitUnknownEnvelope',
  );
  RuntimeValidationTestForyModule.register(
    fory,
    SkipEnvelope,
    name: 'validation.SkipEnvelope',
  );
}

void _registerSkipV1(Fory fory) {
  RuntimeValidationTestForyModule.register(
    fory,
    SkipCompatibleV1,
    name: 'validation.SkipCompatible',
  );
}

void _registerSkipV2(Fory fory) {
  RuntimeValidationTestForyModule.register(
    fory,
    SkipCompatibleV2,
    name: 'validation.SkipCompatible',
  );
}

void _registerSchemaV1(Fory fory) {
  RuntimeValidationTestForyModule.register(
    fory,
    SchemaVersionV1,
    name: 'validation.SchemaVersion',
  );
}

void _registerSchemaV2(Fory fory) {
  RuntimeValidationTestForyModule.register(
    fory,
    SchemaVersionV2,
    name: 'validation.SchemaVersion',
  );
}

Object _nestedList(int depth) {
  Object value = 'leaf';
  for (var index = 0; index < depth; index += 1) {
    value = <Object?>[value];
  }
  return value;
}

void main() {
  group('field options', () {
    test('duplicate generated field ids are rejected during registration', () {
      final fory = Fory();

      expect(
        () => RuntimeValidationTestForyModule.register(
          fory,
          DuplicateFieldIdOrder,
          name: 'validation.DuplicateFieldIdOrder',
        ),
        throwsA(
          isA<ArgumentError>().having(
            (error) => error.toString(),
            'message',
            contains('Duplicate field id 1'),
          ),
        ),
      );
    });

    test('skip fields stay local only after round trip', () {
      final fory = Fory();
      _registerValidationTypes(fory);

      final roundTrip = fory.deserialize<SkipEnvelope>(
        fory.serialize(
          SkipEnvelope()
            ..visible = 'kept'
            ..skipped = 'discarded',
        ),
      );

      expect(roundTrip.visible, equals('kept'));
      expect(roundTrip.skipped, equals('local-default'));
    });

    test('dynamic fields preserve concrete runtime payload types', () {
      final fory = Fory();
      _registerValidationTypes(fory);

      final dogEnvelope = fory.deserialize<DynamicAnimalEnvelope>(
        fory.serialize(
          DynamicAnimalEnvelope()..animal = (DynamicDog()..name = 'Rex'),
        ),
      );
      final catEnvelope = fory.deserialize<DynamicAnimalEnvelope>(
        fory.serialize(
          DynamicAnimalEnvelope()
            ..animal =
                (DynamicCat()
                  ..name = 'Misty'
                  ..lives = 7),
        ),
      );

      expect(dogEnvelope.animal, isA<DynamicDog>());
      expect((dogEnvelope.animal as DynamicDog).name, equals('Rex'));
      expect(catEnvelope.animal, isA<DynamicCat>());
      expect((catEnvelope.animal as DynamicCat).name, equals('Misty'));
      expect((catEnvelope.animal as DynamicCat).lives, equals(7));
    });

    test('unknown object fields use dynamic generated payloads', () {
      final fory = Fory();
      _registerValidationTypes(fory);

      final roundTrip = fory.deserialize<ExplicitUnknownEnvelope>(
        fory.serialize(ExplicitUnknownEnvelope()..value = 'dynamic-payload'),
      );

      expect(roundTrip.value, equals('dynamic-payload'));
    });

    test('compatible mode ignores skipped fields from older writers', () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerSkipV1(writer);
      _registerSkipV2(reader);

      final migrated = reader.deserialize<SkipCompatibleV2>(
        writer.serialize(
          SkipCompatibleV1()
            ..visible = 'seen'
            ..skipped = 'legacy',
        ),
      );

      expect(migrated.visible, equals('seen'));
      expect(migrated.skipped, equals('reader-default'));
    });
  });

  group('runtime validation', () {
    test('rejects non-xlang payload headers', () {
      final fory = Fory();

      expect(
        () => fory.deserialize<Object?>(Uint8List.fromList(<int>[0])),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Only xlang payloads are supported by the Dart runtime.'),
          ),
        ),
      );
    });

    test('rejects post-read type mismatches', () {
      final fory = Fory();
      final bytes = fory.serialize('value');

      expect(
        () => fory.deserialize<int>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('expected int'),
          ),
        ),
      );
    });

    test('rejects unregistered generated and manual values', () {
      final fory = Fory();

      expect(
        () => fory.serialize(FreshGeneratedValue()..value = 'generated'),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Type FreshGeneratedValue is not registered.'),
          ),
        ),
      );
      expect(
        () => fory.serialize(PlainManualValue('manual')),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Type PlainManualValue is not registered.'),
          ),
        ),
      );
    });

    test(
      'rejects missing generated metadata and invalid registration modes',
      () {
        final fory = Fory();

        expect(
          () => fory.register(
            FreshGeneratedValue,
            name: 'validation.FreshGeneratedValue',
          ),
          throwsA(
            isA<StateError>().having(
              (error) => error.toString(),
              'message',
              contains('has no generated type metadata'),
            ),
          ),
        );
        expect(
          () => fory.registerSerializer(
            PlainManualValue,
            const PlainManualValueSerializer(),
          ),
          throwsA(
            isA<ArgumentError>().having(
              (error) => error.toString(),
              'message',
              contains('Exactly one registration mode is required'),
            ),
          ),
        );
        expect(
          () => fory.registerSerializer(
            PlainManualValue,
            const PlainManualValueSerializer(),
            name: '',
          ),
          throwsA(
            isA<ArgumentError>().having(
              (error) => error.toString(),
              'message',
              contains('name must include a non-empty type name'),
            ),
          ),
        );
        expect(
          () => fory.registerSerializer(
            PlainManualValue,
            const PlainManualValueSerializer(),
            name: 'validation.',
          ),
          throwsA(
            isA<ArgumentError>().having(
              (error) => error.toString(),
              'message',
              contains('name must include a non-empty type name'),
            ),
          ),
        );
        expect(
          () => fory.registerSerializer(
            PlainManualValue,
            const PlainManualValueSerializer(),
            id: 1,
            name: 'validation.PlainManualValue',
          ),
          throwsA(
            isA<ArgumentError>().having(
              (error) => error.toString(),
              'message',
              contains('Exactly one registration mode is required'),
            ),
          ),
        );

        final generated = Fory();
        RuntimeValidationTestForyModule.register(
          generated,
          FreshGeneratedValue,
          name: 'FreshGeneratedValue',
        );
        expect(
          generated.deserialize<FreshGeneratedValue>(
            generated.serialize(FreshGeneratedValue()),
          ),
          isA<FreshGeneratedValue>(),
        );
      },
    );

    test('enforces maxDepth during write and read', () {
      final nested = _nestedList(4);
      final bytes = Fory().serialize(nested);

      expect(
        () => Fory(maxDepth: 3).serialize(nested),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Serialization depth exceeded 3.'),
          ),
        ),
      );
      expect(
        () => Fory(maxDepth: 3).deserialize<Object?>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Deserialization depth exceeded 3.'),
          ),
        ),
      );
    });

    test('rejects schema version mismatches in schema-consistent mode', () {
      final writer = Fory(compatible: false);
      final reader = Fory(compatible: false);
      _registerSchemaV1(writer);
      _registerSchemaV2(reader);

      final bytes = writer.serialize(SchemaVersionV1()..label = 'alpha');

      expect(
        () => reader.deserialize<SchemaVersionV2>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Struct schema version mismatch'),
          ),
        ),
      );
    });
  });
}
