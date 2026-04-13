import 'package:fory/src/context/read_context.dart';
import 'package:fory/src/context/ref_writer.dart';
import 'package:fory/src/context/write_context.dart';
import 'package:fory/src/meta/field_info.dart';
import 'package:fory/src/meta/field_type.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/serializer/serialization_field_info.dart';

final class DeferredReadRef {
  final int id;

  const DeferredReadRef(this.id);
}

TypeInfo? fieldDeclaredTypeInfo(
  TypeResolver resolver,
  SerializationFieldInfo field,
) {
  return field.declaredTypeInfo(resolver);
}

bool fieldUsesDeclaredType(
  TypeResolver resolver,
  SerializationFieldInfo field,
) {
  return field.usesDeclaredType(resolver);
}

void writeFieldValue(
  WriteContext context,
  SerializationFieldInfo field,
  Object? value,
) {
  final fieldType = field.fieldType;
  if (fieldType.isDynamic) {
    if (fieldType.ref) {
      context.writeRef(value);
      return;
    }
    if (context.writeNullFlag(value)) {
      return;
    }
    context.buffer.writeByte(RefWriter.notNullValueFlag);
    context.writeNonRef(value as Object);
    return;
  }
  if (fieldType.isPrimitive && !fieldType.nullable) {
    if (value == null) {
      throw StateError('Field ${field.name} is not nullable.');
    }
    context.writePrimitiveValue(fieldType.typeId, value);
    return;
  }
  final declaredTypeInfo = fieldDeclaredTypeInfo(context.typeResolver, field);
  final usesDeclaredType = fieldUsesDeclaredType(context.typeResolver, field);
  if (!usesDeclaredType || declaredTypeInfo == null) {
    if (fieldType.ref) {
      context.writeRef(value);
      return;
    }
    if (fieldType.nullable) {
      if (context.writeNullFlag(value)) {
        return;
      }
      context.buffer.writeByte(RefWriter.notNullValueFlag);
    } else if (value == null) {
      throw StateError('Field ${field.name} is not nullable.');
    }
    context.writeNonRef(value as Object);
    return;
  }
  final resolved = declaredTypeInfo;
  if (fieldType.nullable || fieldType.ref) {
    final handled = context.refWriter.writeRefOrNull(
      context.buffer,
      value,
      trackRef: fieldType.ref && resolved.supportsRef,
    );
    if (handled) {
      return;
    }
  }
  if (value == null) {
    throw StateError('Field ${field.name} is not nullable.');
  }
  context.writeResolvedValue(resolved, value, fieldType);
}

T readFieldValue<T>(
  ReadContext context,
  SerializationFieldInfo field, [
  T? fallback,
]) {
  final fieldType = field.fieldType;
  if (fieldType.isDynamic) {
    return context.readRef() as T;
  }
  if (fieldType.isPrimitive && !fieldType.nullable) {
    return context.readPrimitiveValue(fieldType.typeId) as T;
  }
  final declaredTypeInfo = fieldDeclaredTypeInfo(context.typeResolver, field);
  final usesDeclaredType = fieldUsesDeclaredType(context.typeResolver, field);
  if (!usesDeclaredType || declaredTypeInfo == null) {
    if (fieldType.ref) {
      return context.readRef() as T;
    }
    if (fieldType.nullable) {
      return context.readNullable() as T;
    }
    return context.readNonRef() as T;
  }
  final resolved = declaredTypeInfo;
  if (fieldType.nullable || fieldType.ref) {
    final flag = context.refReader.tryPreserveRefId(context.buffer);
    final preservedRefId = flag >= RefWriter.refValueFlag ? flag : null;
    if (flag == RefWriter.nullFlag) {
      return fallback as T;
    }
    if (flag == RefWriter.refFlag) {
      return context.refReader.getReadRef() as T;
    }
    final value = context.readResolvedValue(
      resolved,
      fieldType,
      hasPreservedRef: preservedRefId != null,
    );
    if (preservedRefId != null &&
        resolved.supportsRef &&
        context.refReader.readRefAt(preservedRefId) == null) {
      context.refReader.setReadRef(preservedRefId, value);
    }
    return value as T;
  }
  return context.readResolvedValue(resolved, fieldType) as T;
}

Object? readCompatibleField(
  ReadContext context,
  FieldInfo field,
) {
  final fieldType = field.fieldType;
  if (fieldType.isDynamic) {
    return context.readRef();
  }
  if (fieldType.isPrimitive && !fieldType.nullable) {
    return context.readPrimitiveValue(fieldType.typeId);
  }
  final declaredTypeInfo = _compatibleFieldDeclaredTypeInfo(
    context.typeResolver,
    field,
  );
  final usesDeclaredType = declaredTypeInfo != null &&
      usesDeclaredTypeInfo(
        context.typeResolver.config.compatible,
        fieldType,
        declaredTypeInfo,
      );
  if (!usesDeclaredType) {
    if (fieldType.ref) {
      return context.readRef();
    }
    if (fieldType.nullable) {
      return context.readNullable();
    }
    return context.readNonRef();
  }
  final resolved = declaredTypeInfo;
  if (fieldType.nullable || fieldType.ref) {
    final flag = context.refReader.tryPreserveRefId(context.buffer);
    final preservedRefId = flag >= RefWriter.refValueFlag ? flag : null;
    if (flag == RefWriter.nullFlag) {
      return null;
    }
    if (flag == RefWriter.refFlag) {
      return context.refReader.getReadRef();
    }
    final value = context.readResolvedValue(
      resolved,
      fieldType,
      hasPreservedRef: preservedRefId != null,
    );
    if (preservedRefId != null &&
        resolved.supportsRef &&
        context.refReader.readRefAt(preservedRefId) == null) {
      context.refReader.setReadRef(preservedRefId, value);
    }
    return value;
  }
  return context.readResolvedValue(resolved, fieldType);
}

TypeInfo? _compatibleFieldDeclaredTypeInfo(
  TypeResolver resolver,
  FieldInfo field,
) {
  final fieldType = field.fieldType;
  if (fieldType.isDynamic || (fieldType.isPrimitive && !fieldType.nullable)) {
    return null;
  }
  return resolver.resolveFieldType(fieldType);
}

FieldInfo mergeCompatibleWriteField(
  FieldInfo localField,
  FieldInfo remoteField,
) {
  FieldType mergeFieldType(
    FieldType local,
    FieldType remote,
  ) {
    final mergedArguments = <FieldType>[];
    final argumentCount = remote.arguments.length;
    for (var index = 0; index < argumentCount; index += 1) {
      final remoteArgument = remote.arguments[index];
      final localArgument = index < local.arguments.length
          ? local.arguments[index]
          : remoteArgument;
      mergedArguments.add(mergeFieldType(localArgument, remoteArgument));
    }
    return FieldType(
      type: local.type,
      typeId: remote.typeId,
      nullable: remote.nullable,
      ref: remote.ref,
      dynamic: local.dynamic ?? remote.dynamic,
      arguments: mergedArguments,
    );
  }

  return FieldInfo(
    name: localField.name,
    identifier: localField.identifier,
    id: localField.id,
    fieldType: mergeFieldType(localField.fieldType, remoteField.fieldType),
  );
}

FieldInfo mergeCompatibleReadField(
  FieldInfo localField,
  FieldInfo remoteField,
) {
  FieldType mergeFieldType(
    FieldType local,
    FieldType remote,
  ) {
    final mergedArguments = <FieldType>[];
    final argumentCount = remote.arguments.length;
    for (var index = 0; index < argumentCount; index += 1) {
      final remoteArgument = remote.arguments[index];
      final localArgument = index < local.arguments.length
          ? local.arguments[index]
          : remoteArgument;
      mergedArguments.add(mergeFieldType(localArgument, remoteArgument));
    }
    return FieldType(
      type: local.type,
      typeId: remote.typeId,
      nullable: remote.nullable,
      ref: remote.ref,
      dynamic: local.dynamic ?? remote.dynamic,
      arguments: mergedArguments,
    );
  }

  return FieldInfo(
    name: localField.name,
    identifier: localField.identifier,
    id: localField.id,
    fieldType: mergeFieldType(localField.fieldType, remoteField.fieldType),
  );
}
