import 'dart:typed_data';
import 'package:fory/fory.dart';
import 'package:fory/src/codegen/generated_support.dart';
import 'package:test/test.dart';

void main() {
  group('Buffer', () {
    test('round-trips primitive values', () {
      final buffer = Buffer();
      buffer.writeBool(true);
      buffer.writeInt16(-7);
      buffer.writeInt32(42);
      buffer.writeInt64(123456789);
      buffer.writeFloat32(1.5);
      buffer.writeFloat64(2.5);
      buffer.writeVarInt32(-9);
      buffer.writeVarUint32(300);
      buffer.writeVarInt64(-17);
      buffer.writeVarUint64(9000);

      expect(buffer.readBool(), isTrue);
      expect(buffer.readInt16(), equals(-7));
      expect(buffer.readInt32(), equals(42));
      expect(buffer.readInt64(), equals(123456789));
      expect(buffer.readFloat32(), closeTo(1.5, 0.0001));
      expect(buffer.readFloat64(), equals(2.5));
      expect(buffer.readVarInt32(), equals(-9));
      expect(buffer.readVarUint32(), equals(300));
      expect(buffer.readVarInt64(), equals(-17));
      expect(buffer.readVarUint64(), equals(9000));
    });

    test('tagged uint64 sign extension regression', () {
      final buffer = Buffer();
      final testValue = 0x7FFFFFFF;

      buffer.writeTaggedUint64(testValue);
      buffer.wrap(buffer.toBytes());

      final result = buffer.readTaggedUint64();

      expect(result, equals(testValue));
    });

    test('GeneratedReadCursor tagged uint64 sign extension regression', () {
      final buffer = Buffer();
      final testValue = 0x7FFFFFFF;

      buffer.writeTaggedUint64(testValue);
      buffer.wrap(buffer.toBytes());

      final cursor = GeneratedReadCursor.start(buffer);
      final result = cursor.readTaggedUint64();

      expect(result, equals(testValue));
    });
  });
}
