import 'package:meta/meta.dart';

import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/struct_serializer.dart';

enum GeneratedRegistrationKind {
  enumType,
  struct,
}

@internal
final class GeneratedRegistration {
  final GeneratedRegistrationKind kind;
  final Serializer<Object?> Function() serializerFactory;
  final bool evolving;
  final List<FieldInfo> fields;
  final GeneratedStructCompatibleFactory<Object>? compatibleFactory;
  final List<GeneratedStructCompatibleFieldReader<Object>>?
      compatibleReadersBySlot;

  const GeneratedRegistration({
    required this.kind,
    required this.serializerFactory,
    this.evolving = true,
    this.fields = const <FieldInfo>[],
    this.compatibleFactory,
    this.compatibleReadersBySlot,
  });
}

@internal
abstract final class GeneratedRegistrationCatalog {
  static final Map<Type, GeneratedRegistration> _byType =
      <Type, GeneratedRegistration>{};

  static void remember(Type type, GeneratedRegistration registration) {
    _byType[type] = registration;
  }

  static GeneratedRegistration? lookup(Type type) {
    return _byType[type];
  }
}
