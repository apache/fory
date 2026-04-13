import 'package:fory/src/resolver/type_resolver.dart' show TypeIds;

final class FieldType {
  final Type type;
  final int typeId;
  final bool nullable;
  final bool ref;
  final bool? dynamic;
  final List<FieldType> arguments;

  const FieldType({
    required this.type,
    required this.typeId,
    required this.nullable,
    required this.ref,
    required this.dynamic,
    required this.arguments,
  });

  bool get isDynamic => dynamic == true || typeId == TypeIds.unknown;

  bool get isPrimitive => TypeIds.isPrimitive(typeId);

  bool get isContainer => TypeIds.isContainer(typeId);

  FieldType withRootOverrides({
    required bool nullable,
    required bool ref,
  }) {
    if (this.nullable == nullable && this.ref == ref) {
      return this;
    }
    return FieldType(
      type: type,
      typeId: typeId,
      nullable: nullable,
      ref: ref,
      dynamic: dynamic,
      arguments: arguments,
    );
  }
}
