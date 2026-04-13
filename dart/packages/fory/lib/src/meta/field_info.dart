import 'package:fory/src/meta/field_type.dart';

final class FieldInfo {
  final String name;
  final String identifier;
  final int? id;
  final FieldType fieldType;

  const FieldInfo({
    required this.name,
    required this.identifier,
    required this.id,
    required this.fieldType,
  });
}
