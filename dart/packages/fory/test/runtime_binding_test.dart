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

part 'runtime_binding_test.fory.dart';

@ForyStruct()
final class LateChild {
  LateChild([this.value = 0]);

  int value;
}

@ForyStruct()
final class LateParent {
  LateParent();

  LateChild child = LateChild();
}

@ForyStruct()
final class FrozenRegistrationType {
  FrozenRegistrationType();

  int value = 0;
}

final class _LateChildSerializer extends Serializer<LateChild> {
  int writes = 0;
  int reads = 0;

  @override
  void write(WriteContext context, LateChild value) {
    writes += 1;
    context.buffer.writeInt32(value.value + 1000);
  }

  @override
  LateChild read(ReadContext context) {
    reads += 1;
    return LateChild(context.buffer.readInt32() - 1000);
  }
}

@ForyStruct()
final class RemoteChild {
  RemoteChild();

  int value = 0;
}

@ForyStruct()
final class RemoteParent {
  RemoteParent();

  RemoteChild child = RemoteChild();
}

@ForyStruct()
final class RemoteExtChild {
  RemoteExtChild([this.value = 0]);

  int value;
}

final class _ExtChildSerializer extends Serializer<RemoteExtChild> {
  int writes = 0;
  int reads = 0;

  @override
  void write(WriteContext context, RemoteExtChild value) {
    writes += 1;
    context.buffer.writeInt32(value.value + 0x10203040);
  }

  @override
  RemoteExtChild read(ReadContext context) {
    reads += 1;
    return RemoteExtChild(context.buffer.readInt32() - 0x10203040);
  }
}

@ForyStruct()
final class RemoteExtParent {
  RemoteExtParent();

  RemoteExtChild child = RemoteExtChild();
}

@ForyStruct()
enum RemoteMode { first, second }

@ForyStruct()
final class RemoteEnumListParent {
  RemoteEnumListParent();

  List<RemoteMode?> values = <RemoteMode?>[];
}

@ForyStruct()
final class RemoteEnumSetParent {
  RemoteEnumSetParent();

  Set<RemoteMode?> values = <RemoteMode?>{};
}

@ForyStruct()
final class RemoteEnumMapParent {
  RemoteEnumMapParent();

  Map<RemoteMode?, RemoteMode?> values = <RemoteMode?, RemoteMode?>{};
}

@ForyStruct()
final class RemoteNestedEnumListParent {
  RemoteNestedEnumListParent();

  List<List<RemoteMode?>> values = <List<RemoteMode?>>[];
}

final class _FixedWidthModeSerializer extends EnumSerializer<RemoteMode> {
  int writes = 0;
  int reads = 0;

  @override
  void write(WriteContext context, RemoteMode value) {
    writes += 1;
    context.buffer.writeInt32(0x10203040 + value.index);
  }

  @override
  RemoteMode read(ReadContext context) {
    reads += 1;
    final index = context.buffer.readInt32() - 0x10203040;
    return RemoteMode.values[index];
  }
}

@ForyStruct()
final class RemoteNullableEnumParent {
  RemoteNullableEnumParent();

  RemoteMode? mode;
}

@ForyUnion()
final class RemoteUnionValue {
  const RemoteUnionValue(this.caseId, this.value);

  final int caseId;
  final Object value;
}

final class _FixedListUnionSerializer
    extends UnionSerializer<RemoteUnionValue> {
  int payloadWrites = 0;
  int payloadReads = 0;

  @override
  int caseId(RemoteUnionValue value) => value.caseId;

  @override
  Object caseValue(RemoteUnionValue value) => value.value;

  @override
  RemoteUnionValue buildValue(int caseId, Object? value) {
    return RemoteUnionValue(caseId, value as Object);
  }

  @override
  void writeCasePayload(WriteContext context, int caseId, Object? value) {
    payloadWrites += 1;
    final items = value as List<int>;
    if (caseId != 0 || items.length != 2) {
      throw StateError('Unsupported fixed-list union value.');
    }
    context.buffer.writeInt32(items[0]);
    context.buffer.writeInt32(items[1]);
  }

  @override
  Object readCasePayload(ReadContext context, int caseId) {
    payloadReads += 1;
    if (caseId != 0) {
      throw StateError('Unsupported fixed-list union case $caseId.');
    }
    return <int>[context.buffer.readInt32(), context.buffer.readInt32()];
  }
}

@ForyStruct()
final class RemoteUnionParent {
  RemoteUnionParent();

  RemoteUnionValue value = const RemoteUnionValue(0, <int>[0, 0]);
}

@ForyStruct()
final class LocalChild {
  LocalChild();

  int value = 0;
}

@ForyStruct()
final class LocalParent {
  LocalParent();

  int localValue = 1;
}

final class _PoisonObjectSerializer extends EnumSerializer<Object> {
  int reads = 0;

  @override
  void write(WriteContext context, Object value) {
    context.buffer.writeByte(0x7f);
  }

  @override
  Object read(ReadContext context) {
    reads += 1;
    context.buffer.readByte();
    return Object();
  }
}

void _register(Fory fory, Type type, String name) {
  RuntimeBindingTestForyModule.register(fory, type, name: name);
}

void _checkUnknownContainerBody({
  required Type parentType,
  required String parentName,
  required Object empty,
  required Object noBody,
  required Object freshBody,
}) {
  final writer = Fory();
  final reader = Fory();
  writer.registerSerializer(
    RemoteMode,
    _FixedWidthModeSerializer(),
    name: 'binding.ContainerMode',
  );
  _register(writer, parentType, parentName);
  _register(reader, LocalParent, parentName);
  final poison = _PoisonObjectSerializer();
  reader.registerSerializer(Object, poison, name: 'binding.ContainerObject');

  expect(
    reader.deserialize<Object?>(writer.serialize(empty)),
    isA<LocalParent>(),
  );
  expect(
    reader.deserialize<Object?>(writer.serialize(noBody)),
    isA<LocalParent>(),
  );

  final freshBytes = writer.serialize(freshBody);
  expect(writer.deserialize<Object?>(freshBytes).runtimeType, parentType);
  expect(() => reader.deserialize<Object?>(freshBytes), throwsStateError);
  expect(poison.reads, 0);
  expect(
    reader.deserialize<LocalParent>(reader.serialize(LocalParent())).localValue,
    1,
  );
}

void main() {
  test('pre-use custom registration binds generated fields', () {
    final fory = Fory(compatible: false, checkStructVersion: false);
    _register(fory, LateChild, 'binding.LateChild');
    _register(fory, LateParent, 'binding.LateParent');
    final serializer = _LateChildSerializer();
    fory.registerSerializer(LateChild, serializer, name: 'binding.LateChild');
    final replacement = LateParent()..child.value = 23;
    final decoded = fory.deserialize<LateParent>(fory.serialize(replacement));

    expect(decoded.child.value, 23);
    expect(serializer.writes, 1);
    expect(serializer.reads, 1);
  });

  test('root operations freeze registration', () {
    final writer = Fory()..serialize(null);
    expect(
      () =>
          _register(writer, FrozenRegistrationType, 'binding.FrozenGenerated'),
      throwsStateError,
    );
    final fresh = Fory();
    expect(
      () => fresh.register(
        FrozenRegistrationType,
        name: 'binding.FrozenGenerated',
      ),
      throwsStateError,
    );
    _register(fresh, FrozenRegistrationType, 'binding.FrozenGenerated');
    expect(
      () => writer.registerSerializer(
        LateChild,
        _LateChildSerializer(),
        name: 'binding.FrozenChild',
      ),
      throwsStateError,
    );

    final builtinWriter = Fory()..serializeBuiltin(1, typeId: TypeIds.int32);
    expect(
      () => builtinWriter.registerSerializer(
        LateChild,
        _LateChildSerializer(),
        name: 'binding.FrozenBuiltinChild',
      ),
      throwsStateError,
    );

    final reader = Fory();
    expect(() => reader.deserialize<Object?>(Uint8List(0)), throwsA(anything));
    expect(
      () => reader.registerSerializer(
        LateChild,
        _LateChildSerializer(),
        name: 'binding.FrozenChild',
      ),
      throwsStateError,
    );

    final bufferReader = Fory();
    expect(
      () => bufferReader.deserializeFrom<Object?>(Buffer()),
      throwsA(anything),
    );
    expect(
      () => bufferReader.registerSerializer(
        LateChild,
        _LateChildSerializer(),
        name: 'binding.FrozenBufferChild',
      ),
      throwsStateError,
    );
  });

  test('compatible skips use remote child type metadata', () {
    final writer = Fory();
    final reader = Fory();
    _register(writer, RemoteChild, 'binding.Child');
    _register(writer, RemoteParent, 'binding.Parent');
    _register(reader, LocalChild, 'binding.Child');
    _register(reader, LocalParent, 'binding.Parent');
    final poison = _PoisonObjectSerializer();
    reader.registerSerializer(Object, poison, name: 'binding.Object');

    final first = RemoteParent()..child.value = 11;
    final second = RemoteParent()..child.value = 22;
    final decoded = reader.deserialize<Object?>(
      writer.serialize(<Object>[first, second]),
    );

    expect(decoded, isA<List>());
    expect(decoded as List, hasLength(2));
    expect(decoded, everyElement(isA<LocalParent>()));
    expect(
      decoded.cast<LocalParent>().map((value) => value.localValue),
      everyElement(1),
    );
    expect(poison.reads, 0);
  });

  test('compatible skips extensions through remote type metadata', () {
    final writer = Fory();
    final reader = Fory();
    final writerExt = _ExtChildSerializer();
    final readerExt = _ExtChildSerializer();
    writer.registerSerializer(
      RemoteExtChild,
      writerExt,
      name: 'binding.ExtChild',
    );
    reader.registerSerializer(
      RemoteExtChild,
      readerExt,
      name: 'binding.ExtChild',
    );
    _register(writer, RemoteExtParent, 'binding.ExtParent');
    _register(reader, LocalParent, 'binding.ExtParent');
    final poison = _PoisonObjectSerializer();
    reader.registerSerializer(Object, poison, name: 'binding.ExtObject');

    final decoded = reader.deserialize<Object?>(
      writer.serialize(<Object>[
        RemoteExtParent()..child.value = 34,
        RemoteExtParent()..child.value = 55,
      ]),
    );

    expect(decoded, isA<List>());
    expect(decoded as List, hasLength(2));
    expect(decoded, everyElement(isA<LocalParent>()));
    expect(writerExt.writes, 2);
    expect(readerExt.reads, 2);
    expect(poison.reads, 0);
  });

  test('compatible rejects unknown custom enum bodies', () {
    final writer = Fory();
    final reader = Fory();
    final enumSerializer = _FixedWidthModeSerializer();
    writer.registerSerializer(RemoteMode, enumSerializer, name: 'binding.Mode');
    _register(writer, RemoteNullableEnumParent, 'binding.EnumParent');
    _register(reader, LocalParent, 'binding.EnumParent');
    final poison = _PoisonObjectSerializer();
    reader.registerSerializer(Object, poison, name: 'binding.EnumObject');

    expect(
      reader.deserialize<Object?>(writer.serialize(RemoteNullableEnumParent())),
      isA<LocalParent>(),
    );

    final bytes = writer.serialize(
      RemoteNullableEnumParent()..mode = RemoteMode.second,
    );
    expect(
      writer.deserialize<RemoteNullableEnumParent>(bytes).mode,
      RemoteMode.second,
    );
    expect(() => reader.deserialize<Object?>(bytes), throwsStateError);

    expect(enumSerializer.writes, 1);
    expect(enumSerializer.reads, 1);
    expect(poison.reads, 0);
    expect(
      reader
          .deserialize<LocalParent>(reader.serialize(LocalParent()))
          .localValue,
      1,
    );
  });

  test('compatible rejects unknown declared union bodies', () {
    final writer = Fory();
    final reader = Fory();
    final unionSerializer = _FixedListUnionSerializer();
    writer.registerSerializer(
      RemoteUnionValue,
      unionSerializer,
      name: 'binding.Union',
    );
    _register(writer, RemoteUnionParent, 'binding.UnionParent');
    _register(reader, LocalParent, 'binding.UnionParent');
    final poison = _PoisonObjectSerializer();
    reader.registerSerializer(Object, poison, name: 'binding.UnionObject');

    final bytes = writer.serialize(
      RemoteUnionParent()
        ..value = RemoteUnionValue(0, <int>[0x10203040, 0x50607080]),
    );
    final roundTrip = writer.deserialize<RemoteUnionParent>(bytes);
    expect(roundTrip.value.value, <int>[0x10203040, 0x50607080]);
    expect(() => reader.deserialize<Object?>(bytes), throwsStateError);

    expect(unionSerializer.payloadWrites, 1);
    expect(unionSerializer.payloadReads, 1);
    expect(poison.reads, 0);
    expect(
      reader
          .deserialize<LocalParent>(reader.serialize(LocalParent()))
          .localValue,
      1,
    );
  });

  test('compatible rejects unknown container bodies', () {
    _checkUnknownContainerBody(
      parentType: RemoteEnumListParent,
      parentName: 'binding.EnumListParent',
      empty: RemoteEnumListParent(),
      noBody: RemoteEnumListParent()..values = <RemoteMode?>[null],
      freshBody:
          RemoteEnumListParent()..values = <RemoteMode?>[RemoteMode.second],
    );
    _checkUnknownContainerBody(
      parentType: RemoteEnumSetParent,
      parentName: 'binding.EnumSetParent',
      empty: RemoteEnumSetParent(),
      noBody: RemoteEnumSetParent()..values = <RemoteMode?>{null},
      freshBody:
          RemoteEnumSetParent()..values = <RemoteMode?>{RemoteMode.second},
    );
    _checkUnknownContainerBody(
      parentType: RemoteEnumMapParent,
      parentName: 'binding.EnumMapParent',
      empty: RemoteEnumMapParent(),
      noBody:
          RemoteEnumMapParent()
            ..values = <RemoteMode?, RemoteMode?>{null: null},
      freshBody:
          RemoteEnumMapParent()
            ..values = <RemoteMode?, RemoteMode?>{
              RemoteMode.first: RemoteMode.second,
            },
    );
    _checkUnknownContainerBody(
      parentType: RemoteNestedEnumListParent,
      parentName: 'binding.NestedEnumListParent',
      empty: RemoteNestedEnumListParent(),
      noBody: RemoteNestedEnumListParent()..values = <List<RemoteMode?>>[[]],
      freshBody:
          RemoteNestedEnumListParent()
            ..values = <List<RemoteMode?>>[
              <RemoteMode?>[RemoteMode.second],
            ],
    );
  });
}
