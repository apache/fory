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

final class PlainCustomValue {
  PlainCustomValue(this.value);

  final String value;
}

final class PlainCustomValueSerializer extends Serializer<PlainCustomValue> {
  const PlainCustomValueSerializer();

  @override
  void write(WriteContext context, PlainCustomValue value) {
    context.writeString(value.value);
  }

  @override
  PlainCustomValue read(ReadContext context) {
    return PlainCustomValue(context.readString());
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
class IgnoreEnvelope {
  IgnoreEnvelope();

  String visible = '';

  @ForyField(ignore: true)
  String ignored = 'local-default';
}

@ForyStruct()
class IgnoreCompatibleV1 {
  IgnoreCompatibleV1();

  @ForyField(id: 1)
  String visible = '';

  @ForyField(id: 2)
  String ignored = 'writer-default';
}

@ForyStruct()
class IgnoreCompatibleV2 {
  IgnoreCompatibleV2();

  @ForyField(id: 1)
  String visible = '';

  @ForyField(ignore: true)
  String ignored = 'reader-default';
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
    IgnoreEnvelope,
    name: 'validation.IgnoreEnvelope',
  );
}

void _registerIgnoreV1(Fory fory) {
  RuntimeValidationTestForyModule.register(
    fory,
    IgnoreCompatibleV1,
    name: 'validation.IgnoreCompatible',
  );
}

void _registerIgnoreV2(Fory fory) {
  RuntimeValidationTestForyModule.register(
    fory,
    IgnoreCompatibleV2,
    name: 'validation.IgnoreCompatible',
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
    test('ignored fields stay local only after round trip', () {
      final fory = Fory();
      _registerValidationTypes(fory);

      final roundTrip = fory.deserialize<IgnoreEnvelope>(
        fory.serialize(
          IgnoreEnvelope()
            ..visible = 'kept'
            ..ignored = 'discarded',
        ),
      );

      expect(roundTrip.visible, equals('kept'));
      expect(roundTrip.ignored, equals('local-default'));
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

    test('compatible mode ignores fields from older writers', () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerIgnoreV1(writer);
      _registerIgnoreV2(reader);

      final migrated = reader.deserialize<IgnoreCompatibleV2>(
        writer.serialize(
          IgnoreCompatibleV1()
            ..visible = 'seen'
            ..ignored = 'legacy',
        ),
      );

      expect(migrated.visible, equals('seen'));
      expect(migrated.ignored, equals('reader-default'));
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

    test('rejects unregistered generated and custom values', () {
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
        () => fory.serialize(PlainCustomValue('custom')),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Type PlainCustomValue is not registered.'),
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
            PlainCustomValue,
            const PlainCustomValueSerializer(),
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
            PlainCustomValue,
            const PlainCustomValueSerializer(),
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
            PlainCustomValue,
            const PlainCustomValueSerializer(),
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
            PlainCustomValue,
            const PlainCustomValueSerializer(),
            id: 1,
            name: 'validation.PlainCustomValue',
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
