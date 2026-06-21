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

import 'dart:collection';
import 'dart:typed_data';

import 'package:fory/fory.dart';
import 'package:fory/src/codegen/generated_registry.dart';
import 'package:fory/src/context/meta_string_reader.dart';
import 'package:fory/src/context/meta_string_writer.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/type_def.dart';
import 'package:fory/src/meta/type_meta.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/util/hash_util.dart';
import 'package:test/test.dart';

final class _CacheTestSerializer extends Serializer<Object?> {
  const _CacheTestSerializer();

  @override
  bool get supportsRef => false;

  @override
  Object? read(ReadContext context) => null;

  @override
  void write(WriteContext context, Object? value) {}
}

final class _SchemaLocal {}

final class _SchemaRemoteA {}

final class _SchemaRemoteB {}

final class _SchemaRemoteC {}

final class _LateDartExt {}

final class _LateDartHolder {}

const _intFieldType = GeneratedFieldType(
  type: int,
  typeId: TypeIds.int32,
  nullable: false,
  ref: false,
  dynamic: false,
  arguments: <GeneratedFieldType>[],
);

const _mapFieldType = GeneratedFieldType(
  type: Map<String, int>,
  typeId: TypeIds.map,
  nullable: false,
  ref: false,
  dynamic: false,
  arguments: <GeneratedFieldType>[
    GeneratedFieldType(
      type: String,
      typeId: TypeIds.string,
      nullable: false,
      ref: false,
      dynamic: false,
      arguments: <GeneratedFieldType>[],
    ),
    GeneratedFieldType(
      type: int,
      typeId: TypeIds.int32,
      nullable: false,
      ref: false,
      dynamic: false,
      arguments: <GeneratedFieldType>[],
    ),
  ],
);

const _lateExtFieldType = GeneratedFieldType(
  type: _LateDartExt,
  declaredTypeName: '_LateDartExt',
  typeId: TypeIds.compatibleStruct,
  nullable: false,
  ref: true,
  dynamic: false,
  arguments: <GeneratedFieldType>[],
);

GeneratedFieldInfo _generatedField(String name) => GeneratedFieldInfo(
  name: name,
  identifier: name,
  id: null,
  fieldType: _intFieldType,
);

GeneratedFieldInfo _generatedMapField(String name) => GeneratedFieldInfo(
  name: name,
  identifier: name,
  id: null,
  fieldType: _mapFieldType,
);

void _rememberSchema(Type type, List<GeneratedFieldInfo> fields) {
  GeneratedTypeCatalog.remember(
    type,
    GeneratedTypeEntry(
      kind: GeneratedTypeKind.struct,
      serializerFactory: () => const _CacheTestSerializer(),
      evolving: true,
      needsRootRef: false,
      usesNestedTypeDefinitions: false,
      fields: fields.map((field) => field.toFieldInfo()).toList(),
    ),
  );
}

void _rememberEnum(Type type) {
  GeneratedTypeCatalog.remember(
    type,
    GeneratedTypeEntry(
      kind: GeneratedTypeKind.enumType,
      serializerFactory: () => const _CacheTestSerializer(),
    ),
  );
}

void _rememberLateHolder() {
  _rememberSchema(_LateDartHolder, <GeneratedFieldInfo>[
    const GeneratedFieldInfo(
      name: 'value',
      identifier: 'value',
      id: null,
      fieldType: _lateExtFieldType,
    ),
  ]);
}

Uint8List _lateHolderTypeDefBytes({required bool registerExtFirst}) {
  final resolver = TypeResolver(const Config());
  _rememberLateHolder();
  if (registerExtFirst) {
    resolver.registerSerializer(
      _LateDartExt,
      const _CacheTestSerializer(),
      namespace: 'example',
      typeName: 'LateDartExt',
    );
  }
  resolver.registerGenerated(
    _LateDartHolder,
    namespace: 'example',
    typeName: 'LateDartHolder',
  );
  if (!registerExtFirst) {
    resolver.registerSerializer(
      _LateDartExt,
      const _CacheTestSerializer(),
      namespace: 'example',
      typeName: 'LateDartExt',
    );
  }
  final resolved = resolver.resolveUserByName('example', 'LateDartHolder');
  return resolver.typeDefForResolved(resolved).encoded;
}

Uint8List _typeMetaBytes(
  Type type,
  String name,
  List<GeneratedFieldInfo> fields,
) {
  final resolver = TypeResolver(const Config());
  _rememberSchema(type, fields);
  final parts = name.split('.');
  resolver.registerGenerated(
    type,
    namespace: parts.first,
    typeName: parts.last,
  );
  final resolved = resolver.resolveUserByName(parts.first, parts.last);
  final buffer = Buffer();
  resolver.writeTypeMeta(
    buffer,
    resolved,
    typeDefIds: LinkedHashMap<TypeDef, int>.identity(),
    metaStringWriter: MetaStringWriter(),
  );
  return buffer.toBytes();
}

Uint8List _enumTypeMetaBytes(Type type, String name) {
  final resolver = TypeResolver(const Config());
  _rememberEnum(type);
  final parts = name.split('.');
  resolver.registerGenerated(
    type,
    namespace: parts.first,
    typeName: parts.last,
  );
  final resolved = resolver.resolveUserByName(parts.first, parts.last);
  final buffer = Buffer();
  resolver.writeTypeMeta(
    buffer,
    resolved,
    typeDefIds: LinkedHashMap<TypeDef, int>.identity(),
    metaStringWriter: MetaStringWriter(),
  );
  return buffer.toBytes();
}

TypeInfo _cachedTypeInfo(Int64 header) {
  return TypeInfo(
    type: Object,
    kind: RegistrationKind.builtin,
    typeId: TypeIds.struct,
    supportsRef: false,
    needsRootRef: false,
    usesNestedTypeDefinitions: false,
    evolving: false,
    fields: const <FieldInfo>[],
    serializer: const _CacheTestSerializer(),
    structSerializer: null,
    userTypeId: null,
    namespace: null,
    typeName: null,
    encodedNamespace: null,
    encodedTypeName: null,
    typeDef: TypeDef(
      evolving: false,
      fields: const <FieldInfo>[],
      header: header,
      encoded: Uint8List(0),
    ),
    remoteTypeDef: null,
  );
}

void _readTypeMeta(TypeResolver resolver, Uint8List bytes) {
  resolver.readTypeMeta(
    Buffer.wrap(bytes),
    sharedTypes: <TypeInfo>[],
    metaStringReader: MetaStringReader(resolver),
  );
}

void main() {
  group('xlang protocol regressions', () {
    test('deserializes NONE wire values as null', () {
      final fory = Fory();
      final bytes = Uint8List.fromList(<int>[0x01, 0xff, TypeIds.none]);

      expect(fory.deserialize<Object?>(bytes), isNull);
      expect(fory.deserialize<Null>(bytes), isNull);
    });

    test('deserializes FLOAT16_ARRAY wire values', () {
      final fory = Fory();
      final bytes = Uint8List.fromList(
        fory.serialize(Uint16List.fromList(<int>[0x3c00, 0xc000, 0x7e00])),
      );
      bytes[2] = TypeIds.float16Array;

      final values = fory.deserialize<Float16List>(bytes);

      expect(
        Uint16List.view(
          values.buffer,
          values.offsetInBytes,
          values.length,
        ).toList(),
        orderedEquals(<int>[0x3c00, 0xc000, 0x7e00]),
      );
    });

    test('deserializes BFLOAT16 and BFLOAT16_ARRAY wire values', () {
      final fory = Fory();
      final scalarBytes = Uint8List.fromList(
        fory.serializeBuiltin(
          fromBfloat16Bits(0xbf60),
          typeId: TypeIds.bfloat16,
        ),
      );
      final rawArray = Uint16List.fromList(<int>[0x3f80, 0xbf80, 0x7fc1]);
      final arrayBytes = Uint8List.fromList(
        fory.serialize(Bfloat16List.view(rawArray.buffer)),
      );

      expect(
        toBfloat16Bits(fory.deserialize<double>(scalarBytes)),
        equals(0xbf60),
      );
      final arrayValues = fory.deserialize<Bfloat16List>(arrayBytes);
      expect(
        Uint16List.view(
          arrayValues.buffer,
          arrayValues.offsetInBytes,
          arrayValues.length,
        ).toList(),
        orderedEquals(<int>[0x3f80, 0xbf80, 0x7fc1]),
      );
    });

    test('serializes root builtins with an explicit xlang type', () {
      final fory = Fory();
      final bytes = fory.serializeBuiltin(7, typeId: TypeIds.varInt32);

      expect(bytes[0], equals(0x01));
      expect(bytes[1], equals(0xff));
      expect(bytes[2], equals(TypeIds.varInt32));
      expect(fory.deserialize<int>(bytes), equals(7));
    });

    test('rejects out-of-band xlang payload headers', () {
      final fory = Fory();
      final bytes = Uint8List.fromList(fory.serialize('value'));
      bytes[0] |= 0x02;

      expect(
        () => fory.deserialize<String>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Out-of-band buffers'),
          ),
        ),
      );
    });

    test('parsed TypeDef cache publishes beyond old implementation floor', () {
      final cache = ParsedTypeMetaCache();
      const oldImplementationFloor = 8192;
      late TypeInfo lastResolved;
      for (var i = 0; i < oldImplementationFloor; i++) {
        final header = TypeHeader(Int64(i));
        final resolved = _cachedTypeInfo(header.value);
        cache.remember(header, resolved);
        lastResolved = resolved;
      }

      expect(
        cache.lookup(TypeHeader(Int64(oldImplementationFloor - 1))),
        same(lastResolved),
      );
      final aboveOldFloor = TypeHeader(Int64(oldImplementationFloor));
      final aboveOldFloorResolved = _cachedTypeInfo(aboveOldFloor.value);
      cache.remember(aboveOldFloor, aboveOldFloorResolved);

      expect(cache.lookup(aboveOldFloor), same(aboveOldFloorResolved));
    });

    test('TypeDef uses late registered field type', () {
      expect(
        _lateHolderTypeDefBytes(registerExtFirst: false),
        orderedEquals(_lateHolderTypeDefBytes(registerExtFirst: true)),
      );
    });

    test('remote schema limit rejects extra versions', () {
      const name = 'example.Unknown';
      final reader = TypeResolver(const Config(maxSchemaVersionsPerType: 1));
      _rememberSchema(_SchemaLocal, <GeneratedFieldInfo>[]);
      reader.registerGenerated(
        _SchemaLocal,
        namespace: 'example',
        typeName: 'Unknown',
      );
      final first = _typeMetaBytes(_SchemaRemoteA, name, <GeneratedFieldInfo>[
        _generatedField('firstValue'),
      ]);
      final second = _typeMetaBytes(_SchemaRemoteB, name, <GeneratedFieldInfo>[
        _generatedField('secondValue'),
      ]);

      _readTypeMeta(reader, first);

      expect(() => _readTypeMeta(reader, second), throwsA(isA<StateError>()));
    });

    test('named enum TypeDef uses metadata byte limit', () {
      const name = 'example.RemoteEnum';
      final reader = TypeResolver(const Config(maxTypeMetaBytes: 1));
      _rememberEnum(_SchemaLocal);
      final bytes = _enumTypeMetaBytes(_SchemaRemoteA, name);

      expect(() => _readTypeMeta(reader, bytes), throwsA(isA<StateError>()));
    });

    test('registered named enum TypeDef uses metadata byte limit', () {
      const name = 'example.RemoteEnum';
      final reader = TypeResolver(const Config(maxTypeMetaBytes: 1));
      _rememberEnum(_SchemaLocal);
      reader.registerGenerated(
        _SchemaLocal,
        namespace: 'example',
        typeName: 'RemoteEnum',
      );
      final bytes = _enumTypeMetaBytes(_SchemaRemoteA, name);

      expect(() => _readTypeMeta(reader, bytes), throwsA(isA<StateError>()));
    });

    test('exact local named enum TypeDef is accepted', () {
      const name = 'example.SharedEnum';
      final reader = TypeResolver(const Config(maxSchemaVersionsPerType: 1));
      _rememberEnum(_SchemaLocal);
      reader.registerGenerated(
        _SchemaLocal,
        namespace: 'example',
        typeName: 'SharedEnum',
      );
      final bytes = _enumTypeMetaBytes(_SchemaLocal, name);

      _readTypeMeta(reader, bytes);
    });

    test('type meta field limit rejects large struct', () {
      final reader = TypeResolver(const Config(maxTypeFields: 1));
      final bytes = _typeMetaBytes(
        _SchemaRemoteA,
        'example.TooManyFields',
        <GeneratedFieldInfo>[
          _generatedField('firstValue'),
          _generatedField('secondValue'),
        ],
      );

      expect(() => _readTypeMeta(reader, bytes), throwsA(isA<StateError>()));
    });

    test('type meta body limit rejects large metadata', () {
      final reader = TypeResolver(const Config(maxTypeMetaBytes: 1));
      final bytes = _typeMetaBytes(
        _SchemaRemoteA,
        'example.LargeTypeMeta',
        <GeneratedFieldInfo>[_generatedField('value')],
      );

      expect(() => _readTypeMeta(reader, bytes), throwsA(isA<StateError>()));
    });

    test('remote schema limit keeps unknown types separate', () {
      final reader = TypeResolver(const Config(maxSchemaVersionsPerType: 1));
      _rememberSchema(_SchemaLocal, <GeneratedFieldInfo>[]);
      reader.registerGenerated(
        _SchemaLocal,
        namespace: 'example',
        typeName: 'UnknownA',
      );
      _rememberSchema(_SchemaRemoteC, <GeneratedFieldInfo>[]);
      reader.registerGenerated(
        _SchemaRemoteC,
        namespace: 'example',
        typeName: 'UnknownB',
      );
      final first = _typeMetaBytes(
        _SchemaRemoteA,
        'example.UnknownA',
        <GeneratedFieldInfo>[_generatedField('firstValue')],
      );
      final second = _typeMetaBytes(
        _SchemaRemoteB,
        'example.UnknownB',
        <GeneratedFieldInfo>[_generatedField('secondValue')],
      );

      _readTypeMeta(reader, first);
      _readTypeMeta(reader, second);
    });

    test('failed remote schema does not consume schema limit', () {
      const name = 'example.Accepted';
      final reader = TypeResolver(const Config(maxSchemaVersionsPerType: 1));
      _rememberSchema(_SchemaLocal, <GeneratedFieldInfo>[
        _generatedField('value'),
      ]);
      reader.registerGenerated(
        _SchemaLocal,
        namespace: 'example',
        typeName: 'Accepted',
      );
      final invalid = _typeMetaBytes(_SchemaRemoteA, name, <GeneratedFieldInfo>[
        _generatedMapField('value'),
      ]);
      final valid = _typeMetaBytes(_SchemaRemoteB, name, <GeneratedFieldInfo>[
        _generatedField('extraValue'),
      ]);

      expect(() => _readTypeMeta(reader, invalid), throwsA(isA<StateError>()));
      _readTypeMeta(reader, valid);
    });

    test('validates parsed TypeDef body hash before caching', () {
      final body = Uint8List.fromList(<int>[0x80]);
      final header = TypeHeader(typeDefHeader(body));
      final valid = Buffer.wrap(body);
      header.skipRemaining(valid);
      expect(valid.readableBytes, equals(0));

      final malformed = Uint8List.fromList(body);
      malformed[0] ^= 1;
      expect(
        () => header.validateBodyHash(malformed),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('metadata hash'),
          ),
        ),
      );

      final headerWithDifferentLowBits = TypeHeader(header.value ^ 1);
      expect(
        () => headerWithDifferentLowBits.validateBodyHash(body),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('metadata hash'),
          ),
        ),
      );
    });
  });
}
