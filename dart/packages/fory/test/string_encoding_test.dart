import 'package:fory/src/buffer.dart';
import 'package:fory/src/string_encoding.dart';
import 'package:test/test.dart';

void main() {
  group('string encoding', () {
    test('round-trips latin1 strings from the buffer fast path', () {
      const value = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789é';
      final buffer = Buffer();

      writeString(buffer, value);

      final header = buffer.readVarUint36Small();
      final encoding = header & 0x03;
      final byteLength = header >>> 2;
      expect(encoding, equals(stringLatin1Encoding));
      expect(
        readStringFromBuffer(buffer, byteLength, encoding),
        equals(value),
      );
    });

    test('round-trips utf8 strings from the buffer fast path', () {
      const value = '你好，Fory🙂';
      final buffer = Buffer();

      writeString(buffer, value);

      final header = buffer.readVarUint36Small();
      final encoding = header & 0x03;
      final byteLength = header >>> 2;
      expect(encoding, equals(stringUtf8Encoding));
      expect(
        readStringFromBuffer(buffer, byteLength, encoding),
        equals(value),
      );
    });

    test('round-trips empty strings', () {
      const value = '';
      final buffer = Buffer();

      writeString(buffer, value);

      final header = buffer.readVarUint36Small();
      final encoding = header & 0x03;
      final byteLength = header >>> 2;
      expect(byteLength, equals(0));
      expect(
        readStringFromBuffer(buffer, byteLength, encoding),
        equals(value),
      );
    });
  });
}
