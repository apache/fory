import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/write_context.dart';

/// Advanced extension point for generated and manual serializers.
///
/// Most application code uses a generated library namespace for generated
/// types, or [Fory.registerSerializer] for manual serializers, instead of
/// implementing this interface directly.
abstract class Serializer<T> {
  const Serializer();

  /// Whether values handled by this serializer may participate in Ref
  /// tracking when the active field or root operation requests it.
  ///
  /// Immutable serializers such as enums should override this to `false`.
  bool get supportsRef => true;

  /// Writes [value] to [context].
  void write(WriteContext context, T value);

  /// Reads a value of type [T] from [context].
  T read(ReadContext context);
}
