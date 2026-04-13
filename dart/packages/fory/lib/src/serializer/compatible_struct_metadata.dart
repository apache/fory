import 'package:fory/src/meta/type_def.dart';

abstract final class CompatibleStructMetadata {
  static final Expando<TypeDef> _remoteTypeDefs =
      Expando<TypeDef>('fory_remote_type_def');

  static TypeDef? remoteTypeDefFor(Object value) => _remoteTypeDefs[value];

  static void rememberRemoteTypeDef(Object value, TypeDef typeDef) {
    _remoteTypeDefs[value] = typeDef;
  }
}
