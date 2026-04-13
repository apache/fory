import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/resolver/type_resolver.dart';

final class SerializationFieldInfo {
  final FieldInfo field;
  final int slot;
  TypeInfo? _declaredTypeInfo;
  bool? _usesDeclaredType;

  SerializationFieldInfo({
    required this.field,
    required this.slot,
    TypeInfo? declaredTypeInfo,
    bool? usesDeclaredType,
  })  : _declaredTypeInfo = declaredTypeInfo,
        _usesDeclaredType = usesDeclaredType;

  String get name => field.name;

  String get identifier => field.identifier;

  int? get id => field.id;

  FieldType get fieldType => field.fieldType;

  TypeInfo? declaredTypeInfo(TypeResolver resolver) {
    final cached = _declaredTypeInfo;
    if (cached != null) {
      return cached;
    }
    final fieldType = field.fieldType;
    if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
      return null;
    }
    final resolved = resolver.tryResolveFieldType(fieldType);
    if (resolved != null) {
      _declaredTypeInfo = resolved;
    }
    return resolved;
  }

  bool usesDeclaredType(TypeResolver resolver) {
    final fieldType = field.fieldType;
    if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
      return false;
    }
    final resolved = declaredTypeInfo(resolver);
    if (resolved == null) {
      return false;
    }
    final cached = _usesDeclaredType;
    if (cached != null) {
      return cached;
    }
    final uses = usesDeclaredTypeInfo(
      resolver.config.compatible,
      fieldType,
      resolved,
    );
    _usesDeclaredType = uses;
    return uses;
  }
}
