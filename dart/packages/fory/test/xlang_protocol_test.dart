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
import 'package:fory/src/meta/meta_string.dart';
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

final class _DuplicateIdSchema {}

final class _DuplicateNameSchema {}

final class _InconsistentTagSchema {}

final class _IdentityDomainLocal {}

final class _IdentityDomainRemote {}

final class _ExpectedHeaderLocal {}

final class _CachedHeaderLocal {}

final class _CachedHeaderRemote {}

final class _DynamicLocalValidation {}

final class _DynamicLocalValidationRemote {}

final class _DynamicLocalLarge {}

final class _DynamicLocalLargeRemote {}

final class _DynamicLocalOtherRemote {}

final class _ExpectedOwnerA {}

final class _ExpectedOwnerB {}

final class _ExpectedOwnerRemoteB {}

final class _ExpectedOwnerRemoteB2 {}

final class _AlternateEnumLocal {}

final class _AlternateEnumRemote {}

final class _RemoteDuplicateIdLocal {}

final class _RemoteDuplicateIdWriter {}

final class _RemoteDuplicateNameLocal {}

final class _RemoteDuplicateNameWriter {}

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

GeneratedFieldInfo _taggedField(int id) => GeneratedFieldInfo(
  name: 'tagged$id',
  identifier: '$id',
  id: id,
  fieldType: _intFieldType,
);

GeneratedFieldInfo _generatedMapField(String name) => GeneratedFieldInfo(
  name: name,
  identifier: name,
  id: null,
  fieldType: _mapFieldType,
);

GeneratedFieldInfo _generatedNestedListField(String name, int depth) {
  var fieldType = _intFieldType;
  for (var index = 0; index < depth; index += 1) {
    fieldType = GeneratedFieldType(
      type: List<Object?>,
      typeId: TypeIds.list,
      nullable: false,
      ref: true,
      dynamic: false,
      arguments: <GeneratedFieldType>[fieldType],
    );
  }
  return GeneratedFieldInfo(
    name: name,
    identifier: name,
    id: null,
    fieldType: fieldType,
  );
}

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
  final resolver = TypeResolver(Config());
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
  final resolver = TypeResolver(Config());
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
  final resolver = TypeResolver(Config());
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
    readDataAlwaysAdvances: false,
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
  _readTypeMetaBuffer(resolver, Buffer.wrap(bytes));
}

TypeInfo _readTypeMetaBuffer(
  TypeResolver resolver,
  Buffer buffer, {
  TypeInfo? expected,
}) {
  return resolver.readTypeMeta(
    buffer,
    expectedNamedType: expected,
    sharedTypes: <TypeInfo>[],
    metaStringReader: MetaStringReader(resolver),
  );
}

Buffer _typeDefFrame(
  Uint8List encoded,
  Uint8List body, {
  int? declaredLength,
  int? lowFlags,
}) {
  final source = Buffer.wrap(encoded);
  final typeId = source.readVarUint32Small7();
  final marker = source.readVarUint32Small14();
  if (marker != 0) {
    throw StateError('Expected an inline TypeDef.');
  }
  final originalHeader = source.readInt64();
  final currentLength = declaredLength ?? body.length;
  if (currentLength < 0) {
    throw ArgumentError.value(currentLength, 'declaredLength');
  }
  final inlineLength = currentLength >= 0xff ? 0xff : currentLength;
  final currentHeader = Int64.fromWords(
    (originalHeader.low32 & 0xfffff000) |
        ((lowFlags ?? originalHeader.low32) & 0x0f00) |
        inlineLength,
    originalHeader.high32Unsigned,
  );
  final frame =
      Buffer()
        ..writeVarUint32Small7(typeId)
        ..writeVarUint32Small14(marker)
        ..writeInt64(currentHeader);
  if (inlineLength == 0xff) {
    frame.writeVarUint32Small14(currentLength - 0xff);
  }
  frame.writeBytes(body);
  return frame;
}

Uint8List _typeDefBody(Uint8List encoded) {
  final source = Buffer.wrap(encoded);
  source.readVarUint32Small7();
  final marker = source.readVarUint32Small14();
  if (marker != 0) {
    throw StateError('Expected an inline TypeDef.');
  }
  final header = TypeHeader(source.readInt64());
  final bodyLength = header.readMetaSize(source);
  source.checkReadableBytes(bodyLength);
  final body = Uint8List.fromList(source.readBytes(bodyLength));
  if (source.readableBytes != 0) {
    throw StateError('Expected one complete TypeDef.');
  }
  return body;
}

Buffer _extendTypeDefSizeEncoding(Uint8List encoded) {
  final source = Buffer.wrap(encoded);
  final typeId = source.readVarUint32Small7();
  final marker = source.readVarUint32Small14();
  if (marker != 0) {
    throw StateError('Expected an inline TypeDef.');
  }
  final header = TypeHeader(source.readInt64());
  final bodyLength = header.readMetaSize(source);
  if ((header.value.low32 & 0xff) != 0xff) {
    throw StateError('Expected an extended TypeDef size.');
  }
  source.checkReadableBytes(bodyLength);
  final body = source.readBytes(bodyLength);
  if (source.readableBytes != 0) {
    throw StateError('Expected one complete TypeDef.');
  }
  final extraSize = Buffer()..writeVarUint32Small14(bodyLength - 0xff);
  final extraBytes = Uint8List.fromList(extraSize.toBytes());
  extraBytes[extraBytes.length - 1] |= 0x80;

  return Buffer()
    ..writeVarUint32Small7(typeId)
    ..writeVarUint32Small14(marker)
    ..writeInt64(header.value)
    ..writeBytes(extraBytes)
    ..writeUint8(0)
    ..writeBytes(body);
}

({TypeResolver resolver, TypeInfo expectedA, TypeInfo registeredB})
_ownerFixture({
  int maxSchemaVersionsPerType = Config.defaultMaxSchemaVersionsPerType,
}) {
  final resolver = TypeResolver(
    Config(maxSchemaVersionsPerType: maxSchemaVersionsPerType),
  );
  _rememberSchema(_ExpectedOwnerA, <GeneratedFieldInfo>[
    _generatedField('aValue'),
  ]);
  _rememberSchema(_ExpectedOwnerB, <GeneratedFieldInfo>[
    _generatedField('bValue'),
  ]);
  resolver.registerGenerated(
    _ExpectedOwnerA,
    namespace: 'example',
    typeName: 'ExpectedOwnerA',
  );
  resolver.registerGenerated(
    _ExpectedOwnerB,
    namespace: 'example',
    typeName: 'ExpectedOwnerB',
  );
  return (
    resolver: resolver,
    expectedA: resolver.resolvedRegisteredType(_ExpectedOwnerA),
    registeredB: resolver.resolvedRegisteredType(_ExpectedOwnerB),
  );
}

Uint8List _remoteOwnerBBytes() {
  return _typeMetaBytes(
    _ExpectedOwnerRemoteB,
    'example.ExpectedOwnerB',
    <GeneratedFieldInfo>[
      _generatedField('bValue'),
      _generatedField('remoteOnly'),
    ],
  );
}

Buffer _metaStringWire(
  EncodedMetaString encoded, {
  Uint8List? body,
  Int64? hash,
}) {
  final wireBody = body ?? encoded.bytes;
  final buffer = Buffer()..writeVarUint32Small7(wireBody.length << 1);
  if (wireBody.length > metaStringSmallThreshold) {
    buffer.writeInt64(
      hash ?? EncodedMetaString(wireBody, encoded.encoding).hash,
    );
  } else if (wireBody.isNotEmpty) {
    buffer.writeByte(encoded.encoding);
  }
  buffer.writeBytes(wireBody);
  return buffer;
}

Uint8List _rewriteTypeDefBody(
  Uint8List typeMetaBytes,
  void Function(Uint8List body) rewrite,
) {
  final source = Buffer.wrap(typeMetaBytes);
  final typeId = source.readVarUint32Small7();
  final marker = source.readVarUint32Small14();
  if (marker != 0) {
    throw StateError('Expected an inline TypeDef.');
  }
  final header = TypeHeader(source.readInt64());
  final bodyLength = header.readMetaSize(source);
  final body = Uint8List.fromList(source.readBytes(bodyLength));
  if (source.readableBytes != 0) {
    throw StateError('Expected one complete TypeDef.');
  }
  rewrite(body);

  final result = Buffer();
  result.writeVarUint32Small7(typeId);
  result.writeVarUint32(marker);
  result.writeInt64(typeDefHeader(body));
  if (body.length >= 0xff) {
    result.writeVarUint32(body.length - 0xff);
  }
  result.writeBytes(body);
  return result.toBytes();
}

void _replaceUniqueBytes(
  Uint8List bytes,
  List<int> source,
  List<int> replacement,
) {
  if (source.length != replacement.length) {
    throw ArgumentError('Replacement byte lengths must match.');
  }
  var match = -1;
  for (var offset = 0; offset <= bytes.length - source.length; offset += 1) {
    var equal = true;
    for (var index = 0; index < source.length; index += 1) {
      if (bytes[offset + index] != source[index]) {
        equal = false;
        break;
      }
    }
    if (!equal) {
      continue;
    }
    if (match >= 0) {
      throw StateError('Expected a unique byte sequence.');
    }
    match = offset;
  }
  if (match < 0) {
    throw StateError('Byte sequence was not found.');
  }
  bytes.setRange(match, match + replacement.length, replacement);
}

Uint8List _duplicateTaggedTypeDef(Uint8List validBytes) {
  const tagOneHeader = (3 << 6) | (1 << 2);
  const tagTwoHeader = (3 << 6) | (2 << 2);
  return _rewriteTypeDefBody(
    validBytes,
    (body) => _replaceUniqueBytes(
      body,
      const <int>[tagTwoHeader, TypeIds.int32],
      const <int>[tagOneHeader, TypeIds.int32],
    ),
  );
}

Uint8List _duplicateNamedTypeDef(Uint8List validBytes) {
  final first = encodeFieldNameMetaString('alpha');
  final second = encodeFieldNameMetaString('bravo');
  if (first.encoding != second.encoding ||
      first.bytes.length != second.bytes.length) {
    throw StateError('Test field names must use the same encoding shape.');
  }
  return _rewriteTypeDefBody(
    validBytes,
    (body) => _replaceUniqueBytes(body, second.bytes, first.bytes),
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

    test('rejects a trailing meta string escape', () {
      expect(
        () => decodeMetaString(
          const <int>[0x74],
          metaStringAllToLowerSpecialEncoding,
          specialChar1: r'$',
          specialChar2: '_',
        ),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('trailing escape'),
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

    test('keys parsed TypeDef cache by top 52 bits', () {
      final cache = ParsedTypeMetaCache();
      final storedHeader = TypeHeader(Int64.fromWords(0x12345011, 0x23456789));
      final stored = _cachedTypeInfo(storedHeader.value);
      cache.remember(storedHeader, stored);
      final distractorHeader = TypeHeader(
        Int64.fromWords(0x12346022, 0x23456789),
      );
      cache.remember(distractorHeader, _cachedTypeInfo(distractorHeader.value));
      final currentHeader = TypeHeader(Int64.fromWords(0x12345ffe, 0x23456789));

      expect(cache.lookup(currentHeader), same(stored));
    });

    test('expected TypeDef top-52 hit ignores low flags', () {
      final resolver = TypeResolver(Config());
      _rememberSchema(_ExpectedHeaderLocal, <GeneratedFieldInfo>[
        _generatedField('value'),
      ]);
      resolver.registerGenerated(
        _ExpectedHeaderLocal,
        namespace: 'example',
        typeName: 'ExpectedHeader',
      );
      final expected = resolver.resolvedRegisteredType(_ExpectedHeaderLocal);
      final encoded = Buffer();
      resolver.writeTypeMeta(
        encoded,
        expected,
        typeDefIds: LinkedHashMap<TypeDef, int>.identity(),
        metaStringWriter: MetaStringWriter(),
      );
      final currentBody = Uint8List.fromList(<int>[0x11, 0x22, 0x33]);
      final currentFrame = _typeDefFrame(
        encoded.toBytes(),
        currentBody,
        lowFlags: 0x0f00,
      );

      expect(
        _readTypeMetaBuffer(resolver, currentFrame, expected: expected),
        same(expected),
      );
      expect(currentFrame.readableBytes, 0);
    });

    test('parsed TypeDef hit uses current frame bounds', () {
      const name = 'example.CachedHeader';
      final reader = TypeResolver(Config());
      _rememberSchema(_CachedHeaderLocal, <GeneratedFieldInfo>[
        _generatedField('value'),
      ]);
      reader.registerGenerated(
        _CachedHeaderLocal,
        namespace: 'example',
        typeName: 'CachedHeader',
      );
      final encoded = _typeMetaBytes(
        _CachedHeaderRemote,
        name,
        <GeneratedFieldInfo>[
          _generatedField('value'),
          _generatedField('remoteOnly'),
        ],
      );
      final cached = _readTypeMetaBuffer(reader, Buffer.wrap(encoded));
      final currentBody = Uint8List(0x100);
      currentBody.fillRange(0, currentBody.length, 0x44);
      final currentFrame = _typeDefFrame(
        encoded,
        currentBody,
        lowFlags: 0x0f00,
      );

      expect(_readTypeMetaBuffer(reader, currentFrame), same(cached));
      expect(currentFrame.readableBytes, 0);

      final truncated = _typeDefFrame(
        encoded,
        Uint8List.fromList(<int>[0x66]),
        declaredLength: 2,
      );
      expect(
        () => _readTypeMetaBuffer(reader, truncated),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Insufficient readable bytes'),
          ),
        ),
      );
    });

    test('rejects shared TypeDef owner mismatch', () {
      final fixture = _ownerFixture();
      final buffer = Buffer();
      final typeDefIds = LinkedHashMap<TypeDef, int>.identity();
      final metaStringWriter = MetaStringWriter();
      fixture.resolver.writeTypeMeta(
        buffer,
        fixture.registeredB,
        typeDefIds: typeDefIds,
        metaStringWriter: metaStringWriter,
      );
      fixture.resolver.writeTypeMeta(
        buffer,
        fixture.registeredB,
        typeDefIds: typeDefIds,
        metaStringWriter: metaStringWriter,
      );
      final sharedTypes = <TypeInfo>[];

      expect(
        fixture.resolver.readExpectedTypeDefMeta(
          buffer,
          fixture.registeredB,
          sharedTypes: sharedTypes,
        ),
        same(fixture.registeredB),
      );
      expect(
        () => fixture.resolver.readExpectedTypeDefMeta(
          buffer,
          fixture.expectedA,
          sharedTypes: sharedTypes,
        ),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('owner mismatch'),
          ),
        ),
      );
      expect(sharedTypes, hasLength(1));
    });

    test('rejects cached TypeDef owner mismatch', () {
      final fixture = _ownerFixture();
      final encoded = _remoteOwnerBBytes();
      final sharedTypes = <TypeInfo>[];
      final metaStringReader = MetaStringReader(fixture.resolver);
      final remoteB = fixture.resolver.readTypeMeta(
        Buffer.wrap(encoded),
        sharedTypes: sharedTypes,
        metaStringReader: metaStringReader,
      );
      expect(identical(remoteB.type, fixture.registeredB.type), isTrue);
      expect(remoteB.remoteTypeDef, isNotNull);
      final hit = _typeDefFrame(
        encoded,
        Uint8List.fromList(<int>[0x11, 0x22, 0x33]),
        lowFlags: 0x0f00,
      );

      expect(
        () => fixture.resolver.readExpectedTypeDefMeta(
          hit,
          fixture.expectedA,
          sharedTypes: sharedTypes,
        ),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('owner mismatch'),
          ),
        ),
      );
      expect(hit.readableBytes, 3);
      expect(sharedTypes, hasLength(1));
    });

    test('rejects validated TypeDef owner mismatch', () {
      final fixture = _ownerFixture(maxSchemaVersionsPerType: 1);
      final encoded = _remoteOwnerBBytes();
      final sharedTypes = <TypeInfo>[];

      expect(
        () => fixture.resolver.readExpectedTypeDefMeta(
          Buffer.wrap(encoded),
          fixture.expectedA,
          sharedTypes: sharedTypes,
        ),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('owner mismatch'),
          ),
        ),
      );
      expect(sharedTypes, isEmpty);

      final uncachedFlags = _typeDefFrame(
        encoded,
        _typeDefBody(encoded),
        lowFlags: 0x0200,
      );
      expect(
        () => _readTypeMetaBuffer(fixture.resolver, uncachedFlags),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('global header'),
          ),
        ),
      );

      final remoteVersion = _typeMetaBytes(
        _ExpectedOwnerRemoteB2,
        'example.ExpectedOwnerB',
        <GeneratedFieldInfo>[
          _generatedField('bValue'),
          _generatedField('anotherRemoteOnly'),
        ],
      );
      _readTypeMeta(fixture.resolver, remoteVersion);
    });

    test('validates dynamic local misses before reuse', () {
      const name = 'example.DynamicValidation';
      final reader = TypeResolver(Config());
      final fields = <GeneratedFieldInfo>[_generatedField('value')];
      _rememberSchema(_DynamicLocalValidation, fields);
      reader.registerGenerated(
        _DynamicLocalValidation,
        namespace: 'example',
        typeName: 'DynamicValidation',
      );
      final encoded = _typeMetaBytes(
        _DynamicLocalValidationRemote,
        name,
        fields,
      );
      final body = _typeDefBody(encoded);
      final fieldName = encodeFieldNameMetaString('value');
      final changedName = encodeFieldNameMetaString('valuf');
      if (fieldName.encoding != changedName.encoding ||
          fieldName.length != changedName.length) {
        throw StateError('Test field names must use the same encoding shape.');
      }
      _replaceUniqueBytes(body, fieldName.bytes, changedName.bytes);
      final forgedBody = _typeDefFrame(encoded, body);

      expect(
        () => _readTypeMetaBuffer(reader, forgedBody),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('metadata hash'),
          ),
        ),
      );

      final forgedFlags = _typeDefFrame(
        encoded,
        _typeDefBody(encoded),
        lowFlags: 0x0200,
      );
      expect(
        () => _readTypeMetaBuffer(reader, forgedFlags),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('global header'),
          ),
        ),
      );
    });

    test('lazy local TypeDef miss is not remote state', () {
      const name = 'example.DynamicLarge';
      final fields = List<GeneratedFieldInfo>.generate(
        24,
        (index) => _generatedField('longDynamicFieldName$index'),
      );
      final reader = TypeResolver(Config(maxSchemaVersionsPerType: 1));
      _rememberSchema(_DynamicLocalLarge, fields);
      reader.registerGenerated(
        _DynamicLocalLarge,
        namespace: 'example',
        typeName: 'DynamicLarge',
      );
      final encoded = _typeMetaBytes(_DynamicLocalLargeRemote, name, fields);
      final currentFrame = _extendTypeDefSizeEncoding(encoded);
      final local = reader.resolvedRegisteredType(_DynamicLocalLarge);

      expect(_readTypeMetaBuffer(reader, currentFrame), same(local));
      expect(currentFrame.readableBytes, 0);

      final uncachedFlags = _typeDefFrame(
        encoded,
        _typeDefBody(encoded),
        lowFlags: 0x0200,
      );
      expect(
        () => _readTypeMetaBuffer(reader, uncachedFlags),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('global header'),
          ),
        ),
      );

      final remoteVersion = _typeMetaBytes(
        _DynamicLocalOtherRemote,
        name,
        <GeneratedFieldInfo>[...fields, _generatedField('remoteOnlyField')],
      );
      _readTypeMeta(reader, remoteVersion);
    });

    test('TypeDef uses late registered field type', () {
      expect(
        _lateHolderTypeDefBytes(registerExtFirst: false),
        orderedEquals(_lateHolderTypeDefBytes(registerExtFirst: true)),
      );
    });

    test('canonicalizes an empty TypeDef namespace', () {
      final reader = TypeResolver(Config());
      final writer = TypeResolver(Config());
      _rememberSchema(_SchemaLocal, <GeneratedFieldInfo>[]);
      _rememberSchema(_SchemaRemoteA, <GeneratedFieldInfo>[]);
      reader.registerGenerated(
        _SchemaLocal,
        namespace: '',
        typeName: 'my_wrapper',
      );
      writer.registerGenerated(
        _SchemaRemoteA,
        namespace: '',
        typeName: 'my_wrapper',
      );
      final buffer = Buffer();
      writer.writeTypeMeta(
        buffer,
        writer.resolveUserByName('', 'my_wrapper'),
        typeDefIds: LinkedHashMap<TypeDef, int>.identity(),
        metaStringWriter: MetaStringWriter(),
      );

      _readTypeMeta(reader, buffer.toBytes());
    });

    test('rejects TypeDef field nesting beyond maxDepth', () {
      final bytes = _typeMetaBytes(
        _SchemaRemoteA,
        'example.DeepField',
        <GeneratedFieldInfo>[_generatedNestedListField('value', 3)],
      );
      final resolver = TypeResolver(Config(maxDepth: 2));

      expect(
        () => _readTypeMeta(resolver, bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('TypeDef field depth exceeded'),
          ),
        ),
      );
    });

    test('reuses expected big meta string by hash', () {
      final resolver = TypeResolver(Config());
      final reader = MetaStringReader(resolver);
      final expected = resolver.typeNameMetaString(
        'LongExpectedTypeNameForIdentity',
      );
      final differentBody = Uint8List(expected.length + 3);
      differentBody.fillRange(0, differentBody.length, 0x78);
      final buffer = _metaStringWire(
        expected,
        body: differentBody,
        hash: expected.hash,
      );

      expect(reader.readMetaString(buffer, expected), same(expected));
      expect(buffer.readableBytes, 0);
    });

    test('checks expected big meta string frame bounds', () {
      final resolver = TypeResolver(Config());
      final reader = MetaStringReader(resolver);
      final expected = resolver.typeNameMetaString(
        'LongExpectedTypeNameForIdentity',
      );
      final declaredLength = expected.length + 3;
      final buffer =
          Buffer()
            ..writeVarUint32Small7(declaredLength << 1)
            ..writeInt64(expected.hash)
            ..writeBytes(expected.bytes);

      expect(
        () => reader.readMetaString(buffer, expected),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Insufficient readable bytes'),
          ),
        ),
      );
    });

    test('keeps unaccepted meta strings operation-local', () {
      final resolver = TypeResolver(Config());
      final reader = MetaStringReader(resolver);
      final candidate = EncodedMetaString(
        Uint8List.fromList(<int>[0x61, 0x62]),
        metaStringUtf8Encoding,
      );
      final decoded = reader.readMetaString(_metaStringWire(candidate));

      reader.reset();
      final internedLater = resolver.internEncodedMetaString(
        Uint8List.fromList(candidate.bytes),
        encoding: candidate.encoding,
      );
      expect(internedLater, isNot(same(decoded)));

      final accepted = resolver.fieldNameMetaString('known');
      expect(reader.readMetaString(_metaStringWire(accepted)), same(accepted));
    });

    test('metadata limits require positive safe integers', () {
      const unsafeInteger = 9007199254740992;
      final factories = <Config Function(int)>[
        (value) => Config(maxDepth: value),
        (value) => Config(maxTypeFields: value),
        (value) => Config(maxTypeMetaBytes: value),
        (value) => Config(maxSchemaVersionsPerType: value),
        (value) => Config(maxAverageSchemaVersionsPerType: value),
      ];

      for (final factory in factories) {
        expect(() => factory(0), throwsA(isA<ArgumentError>()));
        expect(() => factory(unsafeInteger), throwsA(isA<ArgumentError>()));
      }
    });

    test('rejects duplicate local field ids', () {
      final resolver = TypeResolver(Config());
      _rememberSchema(_DuplicateIdSchema, <GeneratedFieldInfo>[
        _taggedField(1),
        _taggedField(1),
      ]);

      expect(
        () => resolver.registerGenerated(
          _DuplicateIdSchema,
          namespace: 'example',
          typeName: 'DuplicateId',
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

    test('rejects duplicate local field names', () {
      final resolver = TypeResolver(Config());
      _rememberSchema(_DuplicateNameSchema, <GeneratedFieldInfo>[
        _generatedField('value'),
        _generatedMapField('value'),
      ]);

      expect(
        () => resolver.registerGenerated(
          _DuplicateNameSchema,
          namespace: 'example',
          typeName: 'DuplicateName',
        ),
        throwsA(
          isA<ArgumentError>().having(
            (error) => error.toString(),
            'message',
            contains('Duplicate field wire name value'),
          ),
        ),
      );
    });

    test('rejects inconsistent local tagged identity', () {
      final resolver = TypeResolver(Config());
      _rememberSchema(_InconsistentTagSchema, <GeneratedFieldInfo>[
        const GeneratedFieldInfo(
          name: 'tagged',
          identifier: '2',
          id: 1,
          fieldType: _intFieldType,
        ),
      ]);

      expect(
        () => resolver.registerGenerated(
          _InconsistentTagSchema,
          namespace: 'example',
          typeName: 'InconsistentTag',
        ),
        throwsA(
          isA<ArgumentError>().having(
            (error) => error.toString(),
            'message',
            contains('textual identifier 2, which must match field id 1'),
          ),
        ),
      );
    });

    test('keeps tagged ids separate from field names', () {
      const name = 'example.IdentityDomains';
      final reader = TypeResolver(Config());
      _rememberSchema(_IdentityDomainLocal, <GeneratedFieldInfo>[
        _taggedField(1),
        _generatedMapField('1'),
      ]);
      reader.registerGenerated(
        _IdentityDomainLocal,
        namespace: 'example',
        typeName: 'IdentityDomains',
      );
      final remote = _typeMetaBytes(
        _IdentityDomainRemote,
        name,
        <GeneratedFieldInfo>[_generatedMapField('1'), _taggedField(1)],
      );

      _readTypeMeta(reader, remote);
    });

    test('rejects duplicate remote field ids before caching', () {
      const name = 'example.RemoteDuplicateId';
      final reader = TypeResolver(Config(maxSchemaVersionsPerType: 1));
      _rememberSchema(_RemoteDuplicateIdLocal, <GeneratedFieldInfo>[
        _generatedField('value'),
      ]);
      reader.registerGenerated(
        _RemoteDuplicateIdLocal,
        namespace: 'example',
        typeName: 'RemoteDuplicateId',
      );
      final valid = _typeMetaBytes(
        _RemoteDuplicateIdWriter,
        name,
        <GeneratedFieldInfo>[_taggedField(1), _taggedField(2)],
      );
      final duplicate = _duplicateTaggedTypeDef(valid);

      expect(
        () => _readTypeMeta(reader, duplicate),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Duplicate field id 1'),
          ),
        ),
      );
      expect(
        () => _readTypeMeta(reader, duplicate),
        throwsA(isA<StateError>()),
      );
      _readTypeMeta(reader, valid);
    });

    test('rejects duplicate remote field names before caching', () {
      const name = 'example.RemoteDuplicateName';
      final reader = TypeResolver(Config(maxSchemaVersionsPerType: 1));
      _rememberSchema(_RemoteDuplicateNameLocal, <GeneratedFieldInfo>[
        _generatedField('value'),
      ]);
      reader.registerGenerated(
        _RemoteDuplicateNameLocal,
        namespace: 'example',
        typeName: 'RemoteDuplicateName',
      );
      final valid = _typeMetaBytes(
        _RemoteDuplicateNameWriter,
        name,
        <GeneratedFieldInfo>[
          _generatedField('alpha'),
          _generatedField('bravo'),
        ],
      );
      final duplicate = _duplicateNamedTypeDef(valid);

      expect(
        () => _readTypeMeta(reader, duplicate),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Duplicate field wire name alpha'),
          ),
        ),
      );
      expect(
        () => _readTypeMeta(reader, duplicate),
        throwsA(isA<StateError>()),
      );
      _readTypeMeta(reader, valid);
    });

    test('remote schema limit rejects extra versions', () {
      const name = 'example.Unknown';
      final reader = TypeResolver(Config(maxSchemaVersionsPerType: 1));
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

    test(
      'caps persistent remote TypeDef logical keys',
      () {
        const keyLimit = 8192;
        const firstId = 1000;
        final reader = TypeResolver(Config());
        final writer = TypeResolver(Config());
        _rememberSchema(_SchemaLocal, <GeneratedFieldInfo>[]);
        _rememberSchema(_SchemaRemoteA, <GeneratedFieldInfo>[
          _generatedField('remoteValue'),
        ]);

        Uint8List writeRegisteredTypeMeta(TypeResolver resolver, int id) {
          final buffer = Buffer();
          resolver.writeTypeMeta(
            buffer,
            resolver.resolveUserById(id),
            typeDefIds: LinkedHashMap<TypeDef, int>.identity(),
            metaStringWriter: MetaStringWriter(),
          );
          return buffer.toBytes();
        }

        late Uint8List cachedBytes;
        for (var index = 0; index < keyLimit; index += 1) {
          final id = firstId + index;
          reader.registerGenerated(_SchemaLocal, id: id);
          writer.registerGenerated(_SchemaRemoteA, id: id);
          final bytes = writeRegisteredTypeMeta(writer, id);
          if (index == 0) {
            cachedBytes = bytes;
          }
          _readTypeMeta(reader, bytes);
        }

        final rejectedId = firstId + keyLimit;
        reader.registerGenerated(_SchemaLocal, id: rejectedId);
        writer.registerGenerated(_SchemaRemoteA, id: rejectedId);
        final rejectedBytes = writeRegisteredTypeMeta(writer, rejectedId);
        final exceedsKeyLimit = throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('logical type limit'),
          ),
        );

        expect(() => _readTypeMeta(reader, rejectedBytes), exceedsKeyLimit);
        expect(() => _readTypeMeta(reader, rejectedBytes), exceedsKeyLimit);

        // Checked-cache hits and exact-local TypeDefs do not consume or check
        // the remote logical-key limit.
        _readTypeMeta(reader, cachedBytes);
        final localBytes = writeRegisteredTypeMeta(reader, rejectedId);
        _readTypeMeta(reader, localBytes);

        // A new version of an already accepted logical key remains governed by
        // the existing per-type and average limits after the key cap is full.
        final nextWriter = TypeResolver(Config());
        _rememberSchema(_SchemaRemoteB, <GeneratedFieldInfo>[
          _generatedField('nextValue'),
        ]);
        nextWriter.registerGenerated(_SchemaRemoteB, id: firstId);
        _readTypeMeta(reader, writeRegisteredTypeMeta(nextWriter, firstId));

        // Rejection and the exact-local hit above must not publish or count the
        // rejected remote key.
        expect(() => _readTypeMeta(reader, rejectedBytes), exceedsKeyLimit);
      },
      timeout: const Timeout(Duration(minutes: 2)),
    );

    test('named enum TypeDef uses metadata byte limit', () {
      const name = 'example.RemoteEnum';
      final reader = TypeResolver(Config(maxTypeMetaBytes: 1));
      _rememberEnum(_SchemaLocal);
      final bytes = _enumTypeMetaBytes(_SchemaRemoteA, name);

      expect(() => _readTypeMeta(reader, bytes), throwsA(isA<StateError>()));
    });

    test('registered named enum TypeDef uses metadata byte limit', () {
      const name = 'example.RemoteEnum';
      final reader = TypeResolver(Config(maxTypeMetaBytes: 1));
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
      final reader = TypeResolver(Config(maxSchemaVersionsPerType: 1));
      _rememberEnum(_SchemaLocal);
      reader.registerGenerated(
        _SchemaLocal,
        namespace: 'example',
        typeName: 'SharedEnum',
      );
      final bytes = _enumTypeMetaBytes(_SchemaLocal, name);

      _readTypeMeta(reader, bytes);
    });

    test('caches alternate non-struct TypeDef header', () {
      const typeName = 'AlternateEnum';
      final reader = TypeResolver(Config());
      _rememberEnum(_AlternateEnumLocal);
      reader.registerGenerated(
        _AlternateEnumLocal,
        namespace: '',
        typeName: typeName,
      );
      final canonical = _enumTypeMetaBytes(_AlternateEnumRemote, '.$typeName');
      final alternate = _rewriteTypeDefBody(canonical, (body) {
        // All supported package-name encodings represent an empty namespace.
        // The reader canonicalizes them to the same registered name owner.
        expect(body[1], 0);
        body[1] = 1;
      });
      final frame = Buffer.wrap(alternate);
      expect(frame.readVarUint32Small7(), TypeIds.namedEnum);
      expect(frame.readVarUint32Small14(), 0);
      final remoteHeader = frame.readInt64();
      final local = reader.resolvedRegisteredType(_AlternateEnumLocal);
      expect(
        TypeHeader.sameHash(local.cachedTypeDefHeader, remoteHeader),
        isFalse,
      );

      final remote = _readTypeMetaBuffer(reader, Buffer.wrap(alternate));
      expect(remote, isNot(same(local)));
      expect(identical(remote.type, local.type), isTrue);
      expect(remote.remoteTypeDef, isNotNull);
      expect(
        TypeHeader.sameHash(remote.cachedTypeDefHeader, remoteHeader),
        isTrue,
      );

      final hit = _typeDefFrame(
        alternate,
        Uint8List.fromList(<int>[0x11, 0x22, 0x33]),
        lowFlags: 0x0f00,
      );
      expect(_readTypeMetaBuffer(reader, hit), same(remote));
      expect(hit.readableBytes, 0);
    });

    test('type meta field limit rejects large struct', () {
      final reader = TypeResolver(Config(maxTypeFields: 1));
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
      final reader = TypeResolver(Config(maxTypeMetaBytes: 1));
      final bytes = _typeMetaBytes(
        _SchemaRemoteA,
        'example.LargeTypeMeta',
        <GeneratedFieldInfo>[_generatedField('value')],
      );

      expect(() => _readTypeMeta(reader, bytes), throwsA(isA<StateError>()));
    });

    test('remote schema limit keeps unknown types separate', () {
      final reader = TypeResolver(Config(maxSchemaVersionsPerType: 1));
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
      final reader = TypeResolver(Config(maxSchemaVersionsPerType: 1));
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
