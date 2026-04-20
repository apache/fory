import 'dart:typed_data';

import 'package:meta/meta.dart';

import 'package:fory/fory.dart';
import 'package:fory/src/buffer.dart';
import 'package:fory/src/codegen/generated_registry.dart';
import 'package:fory/src/meta/field_info.dart' as meta;
import 'package:fory/src/meta/field_type.dart' as meta_types;
import 'package:fory/src/resolver/type_resolver.dart' as resolver;
import 'package:fory/src/serializer/collection_serializers.dart';
import 'package:fory/src/serializer/map_serializers.dart';
import 'package:fory/src/serializer/scalar_serializers.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';
import 'package:fory/src/serializer/serializer_support.dart';
import 'package:fory/src/serializer/struct_serializer.dart';
import 'package:fory/src/serializer/struct_slots.dart';
import 'package:fory/src/serializer/typed_array_serializers.dart';

final BigInt _generatedCursorMask64Big = (BigInt.one << 64) - BigInt.one;
final BigInt _generatedCursorSevenBitMaskBig = BigInt.from(0x7f);
final BigInt _generatedCursorByteMaskBig = BigInt.from(0xff);
const bool _generatedCursorUseBigIntVarint64 =
    bool.fromEnvironment('dart.library.js_interop') ||
        bool.fromEnvironment('dart.library.js_util');

@internal
final class GeneratedWriteCursor {
  final Buffer _buffer;
  final Uint8List _bytes;
  final ByteData _view;
  int _offset;

  GeneratedWriteCursor._(
    this._buffer,
    this._bytes,
    this._view,
    this._offset,
  );

  factory GeneratedWriteCursor.reserve(Buffer buffer, int maxBytes) {
    final start = bufferReserveBytes(buffer, maxBytes);
    return GeneratedWriteCursor._(
      buffer,
      bufferBytes(buffer),
      bufferByteData(buffer),
      start,
    );
  }

  void finish() {
    bufferSetWriterIndex(_buffer, _offset);
  }

  void writeBool(bool value) {
    _bytes[_offset] = value ? 1 : 0;
    _offset += 1;
  }

  void writeByte(int value) {
    _view.setInt8(_offset, value);
    _offset += 1;
  }

  void writeUint8(int value) {
    _view.setUint8(_offset, value);
    _offset += 1;
  }

  void writeInt16(int value) {
    _view.setInt16(_offset, value, Endian.little);
    _offset += 2;
  }

  void writeUint16(int value) {
    _view.setUint16(_offset, value, Endian.little);
    _offset += 2;
  }

  void writeInt32(int value) {
    _view.setInt32(_offset, value, Endian.little);
    _offset += 4;
  }

  void writeUint32(int value) {
    _view.setUint32(_offset, value, Endian.little);
    _offset += 4;
  }

  void writeInt64(int value) {
    _view.setInt64(_offset, value, Endian.little);
    _offset += 8;
  }

  void writeUint64(int value) {
    _view.setUint64(_offset, value, Endian.little);
    _offset += 8;
  }

  void writeFloat16(Float16 value) {
    writeUint16(value.toBits());
  }

  void writeFloat32(double value) {
    _view.setFloat32(_offset, value, Endian.little);
    _offset += 4;
  }

  void writeFloat64(double value) {
    _view.setFloat64(_offset, value, Endian.little);
    _offset += 8;
  }

  void writeVarUint32(int value) {
    var remaining = value;
    while (remaining >= 0x80) {
      _bytes[_offset] = (remaining & 0x7f) | 0x80;
      _offset += 1;
      remaining >>>= 7;
    }
    _bytes[_offset] = remaining;
    _offset += 1;
  }

  void writeVarInt32(int value) {
    writeVarUint32((value << 1) ^ (value >> 31));
  }

  void writeVarUint64(int value) {
    if (!_generatedCursorUseBigIntVarint64) {
      var remaining = value;
      for (var index = 0; index < 8; index += 1) {
        final chunk = remaining & 0x7f;
        remaining = remaining >>> 7;
        if (remaining == 0) {
          _bytes[_offset] = chunk;
          _offset += 1;
          return;
        }
        _bytes[_offset] = chunk | 0x80;
        _offset += 1;
      }
      _bytes[_offset] = remaining & 0xff;
      _offset += 1;
      return;
    }
    _writeVarUint64BigInt(BigInt.from(value) & _generatedCursorMask64Big);
  }

  void writeVarInt64(int value) {
    if (!_generatedCursorUseBigIntVarint64) {
      writeVarUint64((value << 1) ^ (value >> 63));
      return;
    }
    final signed = BigInt.from(value);
    final zigZag =
        ((signed << 1) ^ BigInt.from(value >> 63)) & _generatedCursorMask64Big;
    _writeVarUint64BigInt(zigZag);
  }

  void writeTaggedInt64(int value) {
    if (value >= -0x40000000 && value <= 0x3fffffff) {
      writeInt32(value << 1);
      return;
    }
    writeUint8(0x01);
    writeInt64(value);
  }

  void writeTaggedUint64(int value) {
    final unsigned = (BigInt.from(value) & _generatedCursorMask64Big).toInt();
    if (unsigned <= 0x7fffffff) {
      writeInt32((unsigned << 1) & 0xffffffff);
      return;
    }
    writeUint8(0x01);
    writeUint64(unsigned);
  }

  void _writeVarUint64BigInt(BigInt value) {
    var remaining = value & _generatedCursorMask64Big;
    for (var index = 0; index < 8; index += 1) {
      final chunk = (remaining & _generatedCursorSevenBitMaskBig).toInt();
      remaining >>= 7;
      if (remaining == BigInt.zero) {
        _bytes[_offset] = chunk;
        _offset += 1;
        return;
      }
      _bytes[_offset] = chunk | 0x80;
      _offset += 1;
    }
    _bytes[_offset] = (remaining & _generatedCursorByteMaskBig).toInt();
    _offset += 1;
  }
}

@internal
final class GeneratedReadCursor {
  final Buffer _buffer;
  final ByteData _view;
  int _offset;

  GeneratedReadCursor._(
    this._buffer,
    this._view,
    this._offset,
  );

  factory GeneratedReadCursor.start(Buffer buffer) {
    return GeneratedReadCursor._(
      buffer,
      bufferByteData(buffer),
      bufferReaderIndex(buffer),
    );
  }

  void finish() {
    bufferSetReaderIndex(_buffer, _offset);
  }

  bool readBool() => readUint8() != 0;

  int readByte() {
    final value = _view.getInt8(_offset);
    _offset += 1;
    return value;
  }

  int readUint8() {
    final value = _view.getUint8(_offset);
    _offset += 1;
    return value;
  }

  int readInt16() {
    final value = _view.getInt16(_offset, Endian.little);
    _offset += 2;
    return value;
  }

  int readUint16() {
    final value = _view.getUint16(_offset, Endian.little);
    _offset += 2;
    return value;
  }

  int readInt32() {
    final value = _view.getInt32(_offset, Endian.little);
    _offset += 4;
    return value;
  }

  int readUint32() {
    final value = _view.getUint32(_offset, Endian.little);
    _offset += 4;
    return value;
  }

  int readInt64() {
    final value = _view.getInt64(_offset, Endian.little);
    _offset += 8;
    return value;
  }

  int readUint64() {
    final value = _view.getUint64(_offset, Endian.little);
    _offset += 8;
    return value;
  }

  Float16 readFloat16() => Float16.fromBits(readUint16());

  double readFloat32() {
    final value = _view.getFloat32(_offset, Endian.little);
    _offset += 4;
    return value;
  }

  double readFloat64() {
    final value = _view.getFloat64(_offset, Endian.little);
    _offset += 8;
    return value;
  }

  int readVarUint32() {
    var shift = 0;
    var result = 0;
    while (true) {
      final byte = readUint8();
      result |= (byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
  }

  int readVarInt32() {
    final value = readVarUint32();
    return (value >>> 1) ^ -(value & 1);
  }

  int readVarUint64() {
    if (!_generatedCursorUseBigIntVarint64) {
      var shift = 0;
      var result = 0;
      while (shift < 56) {
        final byte = readUint8();
        result |= (byte & 0x7f) << shift;
        if ((byte & 0x80) == 0) {
          return result;
        }
        shift += 7;
      }
      return result | (readUint8() << 56);
    }
    return _readVarUint64BigInt().toInt();
  }

  int readVarInt64() {
    if (!_generatedCursorUseBigIntVarint64) {
      final encoded = readVarUint64();
      return (encoded >>> 1) ^ -(encoded & 1);
    }
    final encoded = _readVarUint64BigInt();
    final magnitude = (encoded >> 1).toInt();
    if ((encoded & BigInt.one) == BigInt.zero) {
      return magnitude;
    }
    return -magnitude - 1;
  }

  int readTaggedInt64() {
    final readIndex = _offset;
    final first = _view.getInt32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _offset = readIndex + 4;
      return first >> 1;
    }
    final value = _view.getInt64(readIndex + 1, Endian.little);
    _offset = readIndex + 9;
    return value;
  }

  int readTaggedUint64() {
    final readIndex = _offset;
    final first = _view.getUint32(readIndex, Endian.little);
    if ((first & 1) == 0) {
      _offset = readIndex + 4;
      return first >>> 1;
    }
    final value = _view.getUint64(readIndex + 1, Endian.little);
    _offset = readIndex + 9;
    return value;
  }

  BigInt _readVarUint64BigInt() {
    var shift = 0;
    var result = BigInt.zero;
    while (shift < 56) {
      final byte = readUint8();
      result |= BigInt.from(byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        return result;
      }
      shift += 7;
    }
    return result |
        ((BigInt.from(readUint8()) & _generatedCursorByteMaskBig) << 56);
  }
}

@internal
final class GeneratedFieldType {
  final Type type;
  final int typeId;
  final bool nullable;
  final bool ref;
  final bool? dynamic;
  final List<GeneratedFieldType> arguments;

  const GeneratedFieldType({
    required this.type,
    required this.typeId,
    required this.nullable,
    required this.ref,
    required this.dynamic,
    required this.arguments,
  });

  meta_types.FieldType toFieldType() {
    return meta_types.FieldType(
      type: type,
      typeId: typeId,
      nullable: nullable,
      ref: ref,
      dynamic: dynamic,
      arguments: arguments
          .map((argument) => argument.toFieldType())
          .toList(growable: false),
    );
  }
}

@internal
Object? resolveGeneratedSlotRawValue(
  ReadContext context,
  Object? rawValue,
) {
  if (rawValue is DeferredReadRef) {
    return context.getReadRef(rawValue.id);
  }
  return rawValue;
}

@internal
final class GeneratedFieldInfo {
  final String name;
  final String identifier;
  final int? id;
  final GeneratedFieldType fieldType;

  const GeneratedFieldInfo({
    required this.name,
    required this.identifier,
    required this.id,
    required this.fieldType,
  });

  meta.FieldInfo toFieldInfo() {
    return meta.FieldInfo(
      name: name,
      identifier: identifier,
      id: id,
      fieldType: fieldType.toFieldType(),
    );
  }
}

@internal
final class GeneratedEnumRegistration {
  final Type type;
  final Serializer<Object?> Function() serializerFactory;

  const GeneratedEnumRegistration({
    required this.type,
    required this.serializerFactory,
  });
}

@internal
typedef GeneratedStructFieldInfo = SerializationFieldInfo;

@internal
typedef GeneratedStructFieldInfoWriter<T> = void Function(
    WriteContext context, GeneratedStructFieldInfo field, T value);

@internal
typedef GeneratedStructFieldInfoReader<T> = void Function(
    ReadContext context, T value, Object? rawValue);

@internal
final class GeneratedStructRegistration<T> {
  final List<GeneratedStructFieldInfoWriter<T>> fieldWritersBySlot;
  final GeneratedStructCompatibleFactory<T>? compatibleFactory;
  final List<GeneratedStructFieldInfoReader<T>>? compatibleReadersBySlot;
  final Type type;
  final Serializer<Object?> Function() serializerFactory;
  final bool evolving;
  final List<GeneratedFieldInfo> fields;

  GeneratedStructRegistration({
    required this.fieldWritersBySlot,
    this.compatibleFactory,
    this.compatibleReadersBySlot,
    required this.type,
    required this.serializerFactory,
    required this.evolving,
    required this.fields,
  });

  late final List<meta.FieldInfo> fieldInfos =
      List<meta.FieldInfo>.unmodifiable(
    List<meta.FieldInfo>.generate(
      fields.length,
      (index) => fields[index].toFieldInfo(),
    ),
  );

  late final List<int> defaultSlots = List<int>.unmodifiable(
    List<int>.generate(fieldInfos.length, (index) => index),
  );
}

@internal
void registerGeneratedEnum(
  Fory fory,
  GeneratedEnumRegistration registration, {
  int? id,
  String? namespace,
  String? typeName,
}) {
  GeneratedRegistrationCatalog.remember(
    registration.type,
    GeneratedRegistration(
      kind: GeneratedRegistrationKind.enumType,
      serializerFactory: registration.serializerFactory,
    ),
  );
  fory.register(
    registration.type,
    id: id,
    namespace: namespace,
    typeName: typeName,
  );
}

@internal
void registerGeneratedStruct<T>(
  Fory fory,
  GeneratedStructRegistration<T> registration, {
  int? id,
  String? namespace,
  String? typeName,
}) {
  final compatibleReadersBySlot = registration.compatibleReadersBySlot == null
      ? null
      : List<GeneratedStructCompatibleFieldReader<Object>>.unmodifiable(
          registration.compatibleReadersBySlot!.map(
            (reader) => (
              ReadContext context,
              Object value,
              Object? rawValue,
            ) =>
                reader(context, value as T, rawValue),
          ),
        );
  GeneratedRegistrationCatalog.remember(
    registration.type,
    GeneratedRegistration(
      kind: GeneratedRegistrationKind.struct,
      serializerFactory: registration.serializerFactory,
      evolving: registration.evolving,
      fields: registration.fieldInfos,
      compatibleFactory: registration.compatibleFactory == null
          ? null
          : () => registration.compatibleFactory!() as Object,
      compatibleReadersBySlot: compatibleReadersBySlot,
    ),
  );
  fory.register(
    registration.type,
    id: id,
    namespace: namespace,
    typeName: typeName,
  );
}

@internal
StructWriteSlots? generatedStructWriteSlots(WriteContext context) {
  return context.structWriteSlots;
}

@internal
StructReadSlots? generatedStructReadSlots(ReadContext context) {
  return context.structReadSlots;
}

@internal
void writeGeneratedBinaryValue(WriteContext context, Uint8List value) {
  BinarySerializer.writePayload(context, value);
}

@internal
Uint8List readGeneratedBinaryValue(ReadContext context) {
  return BinarySerializer.readPayload(context);
}

@internal
void writeGeneratedBoolArrayValue(WriteContext context, List<bool> value) {
  const BoolArraySerializer().write(context, value);
}

@internal
List<bool> readGeneratedBoolArrayValue(ReadContext context) {
  return const BoolArraySerializer().read(context);
}

@internal
void writeGeneratedLocalDateValue(WriteContext context, LocalDate value) {
  const LocalDateSerializer().write(context, value);
}

@internal
LocalDate readGeneratedLocalDateValue(ReadContext context) {
  return const LocalDateSerializer().read(context);
}

@internal
void writeGeneratedTimestampValue(WriteContext context, Timestamp value) {
  const TimestampSerializer().write(context, value);
}

@internal
Timestamp readGeneratedTimestampValue(ReadContext context) {
  return const TimestampSerializer().read(context);
}

@internal
void writeGeneratedFixedArrayValue(WriteContext context, TypedData value) {
  writeTypedArrayBytes(context, value);
}

@internal
T readGeneratedTypedArrayValue<T>(
  ReadContext context,
  int elementSize,
  T Function(Uint8List bytes) viewBuilder,
) {
  return readTypedArrayBytes(context, elementSize, viewBuilder);
}

@internal
List<GeneratedStructFieldInfo> buildGeneratedStructFieldInfos(
  resolver.TypeResolver typeResolver,
  GeneratedStructRegistration registration,
) {
  return typeResolver
      .resolvedRegisteredType(registration.type)
      .structSerializer!
      .localFields;
}

@internal
void writeGeneratedStructFieldInfoValue(
  WriteContext context,
  GeneratedStructFieldInfo field,
  Object? value,
) {
  final fieldType = field.fieldType;
  if (!fieldType.isDynamic && !fieldType.ref && !fieldType.nullable) {
    if (fieldType.isPrimitive) {
      context.writePrimitiveValue(fieldType.typeId, value as Object);
      return;
    }
    final resolved = fieldDeclaredTypeInfo(context.typeResolver, field)!;
    if (fieldUsesDeclaredType(context.typeResolver, field)) {
      context.writeResolvedValue(resolved, value as Object, fieldType);
      return;
    }
    final actualResolved = context.typeResolver.resolveValue(value as Object);
    context.writeTypeMetaValue(actualResolved, value);
    context.writeResolvedValue(actualResolved, value, fieldType);
    return;
  }
  writeFieldValue(context, field, value);
}

@internal
Object? readGeneratedStructFieldInfoValue(
  ReadContext context,
  GeneratedStructFieldInfo field, [
  Object? fallback,
]) {
  final fieldType = field.fieldType;
  if (fallback == null &&
      !fieldType.isDynamic &&
      !fieldType.ref &&
      !fieldType.nullable) {
    if (fieldType.isPrimitive) {
      return context.readPrimitiveValue(fieldType.typeId);
    }
    final resolved = fieldDeclaredTypeInfo(context.typeResolver, field)!;
    if (fieldUsesDeclaredType(context.typeResolver, field)) {
      return context.readResolvedValue(resolved, fieldType);
    }
    final actualResolved = context.readTypeMetaValue(
      resolved.isNamed ? resolved : null,
    );
    return context.readResolvedValue(actualResolved, fieldType);
  }
  return readFieldValue(context, field, fallback);
}

@internal
Object? readGeneratedStructDeclaredValue(
  ReadContext context,
  GeneratedStructFieldInfo field,
) {
  final resolved = fieldDeclaredTypeInfo(context.typeResolver, field)!;
  if (fieldUsesDeclaredType(context.typeResolver, field)) {
    return context.readResolvedValue(resolved, field.fieldType);
  }
  final actualResolved = context.readTypeMetaValue(
    resolved.isNamed ? resolved : null,
  );
  return context.readResolvedValue(actualResolved, field.fieldType);
}

@internal
Object readGeneratedStructDirectValue(
  ReadContext context,
  GeneratedStructFieldInfo field,
) {
  final declared = fieldDeclaredTypeInfo(context.typeResolver, field)!;
  final resolver.TypeInfo resolved;
  if (fieldUsesDeclaredType(context.typeResolver, field)) {
    resolved = declared;
  } else {
    resolved =
        context.readTypeMetaValue(declared.isNamed ? declared : null);
  }
  context.increaseDepth();
  final value = resolved.structSerializer!.readValue(context, resolved);
  context.decreaseDepth();
  return value;
}

@internal
List<T> readGeneratedDirectListValue<T>(
  ReadContext context,
  GeneratedStructFieldInfo field,
  T Function(Object? value) convert,
) {
  final fieldType = field.fieldType;
  if (fieldType.typeId != TypeIds.list ||
      fieldType.nullable ||
      fieldType.ref ||
      fieldType.isDynamic) {
    throw StateError('Field ${field.name} is not a direct list path.');
  }
  return readTypedListPayload(
    context,
    fieldType.arguments.single,
    convert,
  );
}

@internal
Set<T> readGeneratedDirectSetValue<T>(
  ReadContext context,
  GeneratedStructFieldInfo field,
  T Function(Object? value) convert,
) {
  final fieldType = field.fieldType;
  if (fieldType.typeId != TypeIds.set ||
      fieldType.nullable ||
      fieldType.ref ||
      fieldType.isDynamic) {
    throw StateError('Field ${field.name} is not a direct set path.');
  }
  return readTypedSetPayload(
    context,
    fieldType.arguments.single,
    convert,
  );
}

@internal
Map<K, V> readGeneratedDirectMapValue<K, V>(
  ReadContext context,
  GeneratedStructFieldInfo field,
  K Function(Object? value) convertKey,
  V Function(Object? value) convertValue,
) {
  final fieldType = field.fieldType;
  if (fieldType.typeId != TypeIds.map ||
      fieldType.nullable ||
      fieldType.ref ||
      fieldType.isDynamic) {
    throw StateError('Field ${field.name} is not a direct map path.');
  }
  return readTypedMapPayload(
    context,
    fieldType.arguments[0],
    fieldType.arguments[1],
    convertKey,
    convertValue,
  );
}
