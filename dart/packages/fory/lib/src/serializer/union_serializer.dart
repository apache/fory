import 'package:fory/src/serializer/serializer.dart';

/// Union-specific serializer base used by manual union serializers.
abstract class UnionSerializer<T> extends Serializer<T> {
  const UnionSerializer();
}
