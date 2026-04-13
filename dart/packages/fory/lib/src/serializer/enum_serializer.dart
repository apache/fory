import 'package:fory/src/serializer/serializer.dart';

/// Enum-specific serializer base used by generated enum serializers.
abstract class EnumSerializer<T> extends Serializer<T> {
  const EnumSerializer();

  @override
  bool get supportsRef => false;
}
