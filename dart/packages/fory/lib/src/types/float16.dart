import 'dart:typed_data';

/// Half-precision floating-point wrapper used by the xlang type system.
final class Float16 implements Comparable<Float16> {
  final int _bits;

  /// Creates a value directly from IEEE 754 binary16 bits.
  const Float16.fromBits(int bits) : _bits = bits & 0xffff;

  /// Converts [value] to the closest representable binary16 value.
  factory Float16(num value) => Float16.fromDouble(value.toDouble());

  /// Converts [value] to the closest representable binary16 value.
  factory Float16.fromDouble(double value) {
    if (value.isNaN) {
      return const Float16.fromBits(0x7e00);
    }
    final data = ByteData(8)..setFloat64(0, value, Endian.little);
    final bits = data.getUint64(0, Endian.little);
    final sign = (bits >> 63) & 0x1;
    final exponent = (bits >> 52) & 0x7ff;
    final mantissa = bits & 0x000fffffffffffff;

    if (exponent == 0x7ff) {
      return Float16.fromBits((sign << 15) | 0x7c00);
    }

    final adjustedExponent = exponent - 1023 + 15;
    if (adjustedExponent >= 0x1f) {
      return Float16.fromBits((sign << 15) | 0x7c00);
    }
    if (adjustedExponent <= 0) {
      if (adjustedExponent < -10) {
        return Float16.fromBits(sign << 15);
      }
      final shifted = (mantissa | (1 << 52)) >> (43 - adjustedExponent);
      final rounded = (shifted + 1) >> 1;
      return Float16.fromBits((sign << 15) | rounded);
    }

    var roundedMantissa = mantissa + 0x0000020000000000;
    var roundedExponent = adjustedExponent;
    if ((roundedMantissa & 0x0010000000000000) != 0) {
      roundedMantissa = 0;
      roundedExponent += 1;
      if (roundedExponent >= 0x1f) {
        return Float16.fromBits((sign << 15) | 0x7c00);
      }
    }
    return Float16.fromBits(
      (sign << 15) | (roundedExponent << 10) | (roundedMantissa >> 42),
    );
  }

  /// Returns the raw IEEE 754 binary16 bits for this value.
  int toBits() => _bits;

  /// Expands this binary16 value to a Dart [double].
  double toDouble() {
    final sign = (_bits >> 15) & 0x1;
    final exponent = (_bits >> 10) & 0x1f;
    final mantissa = _bits & 0x03ff;
    if (exponent == 0) {
      if (mantissa == 0) {
        return sign == 0 ? 0.0 : -0.0;
      }
      final value = mantissa / 1024.0 * 0.00006103515625;
      return sign == 0 ? value : -value;
    }
    if (exponent == 0x1f) {
      return mantissa == 0
          ? (sign == 0 ? double.infinity : double.negativeInfinity)
          : double.nan;
    }
    final value = (1.0 + mantissa / 1024.0) * (1 << (exponent - 15)).toDouble();
    return sign == 0 ? value : -value;
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Float16 && other._bits == _bits;

  @override
  int get hashCode => _bits.hashCode;

  @override
  int compareTo(Float16 other) => toDouble().compareTo(other.toDouble());

  @override
  String toString() => toDouble().toString();
}
