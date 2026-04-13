import 'dart:typed_data';

/// Single-precision floating-point wrapper used by the xlang type system.
final class Float32 implements Comparable<Float32> {
  /// The normalized 32-bit floating-point value as a Dart [double].
  final double value;

  /// Creates a value rounded to IEEE 754 binary32 precision.
  Float32(num value) : value = (Float32List(1)..[0] = value.toDouble())[0];

  @override
  int compareTo(Float32 other) => value.compareTo(other.value);

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is Float32 && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value.toString();
}
