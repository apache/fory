/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import 'dart:async';

import 'package:analyzer/dart/constant/value.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/dart/element/nullability_suffix.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:build/build.dart';
import 'package:fory/fory.dart';
import 'package:source_gen/source_gen.dart';

class DebugGeneratedFieldTypeSpec {
  const DebugGeneratedFieldTypeSpec({
    required this.typeLiteral,
    required this.typeId,
    required this.nullable,
    required this.ref,
    required this.dynamic,
    this.declaredTypeName,
    this.arguments = const <DebugGeneratedFieldTypeSpec>[],
  });

  final String typeLiteral;
  final String? declaredTypeName;
  final int typeId;
  final bool nullable;
  final bool ref;
  final bool? dynamic;
  final List<DebugGeneratedFieldTypeSpec> arguments;
}

final class ForyGenerator extends Generator {
  static final TypeChecker _foryStructChecker = TypeChecker.fromRuntime(
    ForyStruct,
  );
  static final TypeChecker _foryFieldChecker = TypeChecker.fromRuntime(
    ForyField,
  );
  static final TypeChecker _listFieldChecker = TypeChecker.fromRuntime(
    ListField,
  );
  static final TypeChecker _arrayFieldChecker = TypeChecker.fromRuntime(
    ArrayField,
  );
  static final TypeChecker _setFieldChecker = TypeChecker.fromRuntime(
    SetField,
  );
  static final TypeChecker _mapFieldChecker = TypeChecker.fromRuntime(
    MapField,
  );
  static final TypeChecker _typeSpecChecker = TypeChecker.fromRuntime(
    TypeSpec,
  );
  static final TypeChecker _foryUnionChecker = TypeChecker.fromRuntime(
    ForyUnion,
  );

  final Map<String, String> _importPrefixByLibraryIdentifier =
      <String, String>{};

  @override
  FutureOr<String> generate(LibraryReader library, BuildStep buildStep) {
    _buildImportPrefixMap(library);
    final annotatedClasses = library.classes
        .where((element) => _foryStructChecker.hasAnnotationOf(element))
        .toList(growable: false);
    final enumElements = library.enums.toList(growable: false);
    if (annotatedClasses.isEmpty && enumElements.isEmpty) {
      return '';
    }

    final helperBaseName = _toPascalCase(
      buildStep.inputId.pathSegments.last.split('.').first,
    );
    final generatedApiName = '${helperBaseName}Fory';

    final enumSpecs = enumElements.map(_analyzeEnum).toList(growable: false);
    final structSpecs =
        annotatedClasses.map(_analyzeStruct).toList(growable: false);
    final output = StringBuffer()
      ..writeln(
        '// ignore_for_file: implementation_imports, invalid_use_of_internal_member, no_leading_underscores_for_local_identifiers, unreachable_switch_case, unused_element, unused_element_parameter, unnecessary_null_comparison',
      )
      ..writeln();

    for (final enumSpec in enumSpecs) {
      _writeEnum(output, enumSpec);
    }
    for (final structSpec in structSpecs) {
      _writeStruct(output, structSpec);
    }

    _writeRegistrationHelpers(
      output,
      enumSpecs: enumSpecs,
      structSpecs: structSpecs,
      generatedApiName: generatedApiName,
    );
    return output.toString();
  }

  _GeneratedStructSpec _analyzeStruct(ClassElement element) {
    final objectAnnotation = _foryStructChecker.firstAnnotationOf(element);
    final objectReader = ConstantReader(objectAnnotation);
    final evolving = objectReader.peek('evolving')?.boolValue ?? true;

    final fields = element.fields
        .where(
          (field) => !field.isStatic && identical(field.nonSynthetic, field),
        )
        .where((field) => !_isSkipped(field))
        .map(_analyzeField)
        .toList(growable: false);

    final sortedFields = _sortFields(fields);
    final constructorPlan = _buildConstructorPlan(element, sortedFields);
    return _GeneratedStructSpec(
      name: element.displayName,
      evolving: evolving,
      fields: sortedFields,
      constructorPlan: constructorPlan,
    );
  }

  void _buildImportPrefixMap(LibraryReader library) {
    _importPrefixByLibraryIdentifier.clear();
    for (final import
        in library.element.definingCompilationUnit.libraryImports) {
      final importedLibrary = import.importedLibrary;
      final prefix = import.prefix?.element.displayName;
      if (importedLibrary == null || prefix == null || prefix.isEmpty) {
        continue;
      }
      _importPrefixByLibraryIdentifier[importedLibrary.identifier] = prefix;
    }
  }

  _GeneratedEnumSpec _analyzeEnum(EnumElement element) {
    return _GeneratedEnumSpec(
      name: element.displayName,
      usesRawValue: _enumUsesRawValueElement(element),
    );
  }

  _GeneratedFieldSpec _analyzeField(FieldElement field) {
    final annotation = _fieldAnnotationOf(field);
    final reader = annotation == null ? null : ConstantReader(annotation);
    final idValue = reader?.peek('id');
    final nullableValue = reader?.peek('nullable');
    final dynamicValue = reader?.peek('dynamic');
    final fieldId = idValue == null || idValue.isNull ? null : idValue.intValue;
    final nullable = nullableValue == null || nullableValue.isNull
        ? _isNullable(field.type)
        : nullableValue.boolValue;
    final dynamic = dynamicValue == null || dynamicValue.isNull
        ? _autoDynamic(field.type)
        : dynamicValue.boolValue;
    final ref = reader?.peek('ref')?.boolValue ?? false;
    final typeSpec = _analyzeTypeSpecAnnotation(field, reader);

    return _GeneratedFieldSpec(
      name: field.displayName,
      type: field.type,
      displayType: _typeCodeString(field.type),
      identifier: fieldId != null && fieldId >= 0
          ? '$fieldId'
          : _toSnakeCase(field.displayName),
      id: fieldId,
      nullable: nullable,
      ref: ref,
      dynamic: dynamic,
      writable: field.setter != null,
      fieldType: _fieldTypeForType(
        field.type,
        nullable: nullable,
        ref: ref,
        dynamic: dynamic,
        typeSpec: typeSpec,
        errorElement: field,
      ),
    );
  }

  _GeneratedFieldTypeSpec _fieldTypeForType(
    DartType type, {
    required bool nullable,
    required bool ref,
    required bool? dynamic,
    _TypeSpecInfo? typeSpec,
    required Element errorElement,
  }) {
    if (typeSpec != null) {
      final specRef = typeSpec.ref;
      if (specRef != null) ref = specRef;
      final specNullable = typeSpec.nullable;
      if (specNullable != null) nullable = specNullable;
      final specDynamic = typeSpec.dynamic;
      if (specDynamic != null) dynamic = specDynamic;
    }
    if (_isBoolList(type)) {
      if (typeSpec?.typeId != null && typeSpec!.typeId != TypeIds.list) {
        if (typeSpec.typeId != TypeIds.boolArray) {
          throw InvalidGenerationSourceError(
            'Type override ${_typeSpecName(typeSpec.typeId!)} does not match the declared BoolList carrier.',
            element: errorElement,
          );
        }
        return _GeneratedFieldTypeSpec(
          typeLiteral: _typeReferenceLiteral(type),
          declaredTypeName: _typeReferenceLiteral(type),
          typeId: TypeIds.boolArray,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          arguments: const <_GeneratedFieldTypeSpec>[],
        );
      }
      final child = _GeneratedFieldTypeSpec(
        typeLiteral: 'bool',
        declaredTypeName: 'bool',
        typeId: TypeIds.boolType,
        nullable: false,
        ref: false,
        dynamic: null,
        arguments: const <_GeneratedFieldTypeSpec>[],
      );
      return _GeneratedFieldTypeSpec(
        typeLiteral: _typeReferenceLiteral(type),
        declaredTypeName: _typeReferenceLiteral(type),
        typeId: TypeIds.list,
        nullable: nullable,
        ref: ref,
        dynamic: dynamic,
        arguments: <_GeneratedFieldTypeSpec>[child],
      );
    }
    if (_isList(type) || _isSet(type)) {
      final expectedTypeId = _isSet(type) ? TypeIds.set : TypeIds.list;
      if (_isList(type) &&
          typeSpec?.typeId != null &&
          _isDenseArrayTypeId(typeSpec!.typeId!)) {
        return _fieldTypeForArrayListCarrier(
          errorElement,
        );
      }
      if (typeSpec?.typeId != null && typeSpec!.typeId != expectedTypeId) {
        throw InvalidGenerationSourceError(
          'Type override ${_typeSpecName(typeSpec.typeId!)} does not match '
          'the declared ${_isSet(type) ? 'Set' : 'List'} carrier.',
          element: errorElement,
        );
      }
      final argument = (type as InterfaceType).typeArguments.single;
      final elementSpec = typeSpec?.element;
      final child = _fieldTypeForType(
        argument,
        nullable: _isNullable(argument),
        ref: false,
        dynamic: _autoDynamic(argument),
        typeSpec: elementSpec,
        errorElement: errorElement,
      );
      return _GeneratedFieldTypeSpec(
        typeLiteral: _typeReferenceLiteral(type),
        declaredTypeName: _typeReferenceLiteral(type),
        typeId: expectedTypeId,
        nullable: nullable,
        ref: ref,
        dynamic: dynamic,
        arguments: <_GeneratedFieldTypeSpec>[child],
      );
    }
    if (_isMap(type)) {
      if (typeSpec?.typeId != null && typeSpec!.typeId != TypeIds.map) {
        throw InvalidGenerationSourceError(
          'Type override ${_typeSpecName(typeSpec.typeId!)} does not match '
          'the declared Map carrier.',
          element: errorElement,
        );
      }
      final arguments = (type as InterfaceType).typeArguments;
      final keySpec = typeSpec?.key;
      final valueSpec = typeSpec?.value;
      return _GeneratedFieldTypeSpec(
        typeLiteral: _typeReferenceLiteral(type),
        declaredTypeName: _typeReferenceLiteral(type),
        typeId: TypeIds.map,
        nullable: nullable,
        ref: ref,
        dynamic: dynamic,
        arguments: <_GeneratedFieldTypeSpec>[
          _fieldTypeForType(
            arguments[0],
            nullable: _isNullable(arguments[0]),
            ref: false,
            dynamic: _autoDynamic(arguments[0]),
            typeSpec: keySpec,
            errorElement: errorElement,
          ),
          _fieldTypeForType(
            arguments[1],
            nullable: _isNullable(arguments[1]),
            ref: false,
            dynamic: _autoDynamic(arguments[1]),
            typeSpec: valueSpec,
            errorElement: errorElement,
          ),
        ],
      );
    }
    final typeId = typeSpec?.typeId ?? _typeIdFor(type);
    _validateScalarTypeOverride(
      type,
      typeId,
      dynamic,
      typeSpec,
      errorElement,
    );
    return _GeneratedFieldTypeSpec(
      typeLiteral: _typeReferenceLiteral(type),
      declaredTypeName: _typeReferenceLiteral(type),
      typeId: typeId,
      nullable: nullable,
      ref: ref,
      dynamic: dynamic,
      arguments: const <_GeneratedFieldTypeSpec>[],
    );
  }

  _ConstructorPlan _buildConstructorPlan(
    ClassElement element,
    List<_GeneratedFieldSpec> fields,
  ) {
    final unnamedConstructor = element.unnamedConstructor;
    final hasZeroArgConstructor = unnamedConstructor != null &&
        !unnamedConstructor.isFactory &&
        unnamedConstructor.parameters.every(
          (parameter) => parameter.isOptional,
        );
    if (hasZeroArgConstructor && fields.every((field) => field.writable)) {
      return const _ConstructorPlan.mutable();
    }

    if (unnamedConstructor == null || unnamedConstructor.isFactory) {
      throw InvalidGenerationSourceError(
        'Generated Fory serializers require either a writable zero-argument constructor '
        'or an unnamed generative constructor whose parameters map to fields.',
        element: element,
      );
    }

    final fieldByName = <String, _GeneratedFieldSpec>{
      for (final field in fields) field.name: field,
    };
    final arguments = <_ConstructorArgumentSpec>[];
    final constructorFieldNames = <String>{};
    for (final parameter in unnamedConstructor.parameters) {
      final parameterName = parameter.displayName;
      final field = fieldByName[parameterName];
      if (field == null) {
        if (parameter.isRequiredNamed || parameter.isRequiredPositional) {
          throw InvalidGenerationSourceError(
            'Constructor parameter $parameterName does not match a serializable field.',
            element: parameter,
          );
        }
        continue;
      }
      constructorFieldNames.add(field.name);
      arguments.add(
        _ConstructorArgumentSpec(
          fieldName: field.name,
          parameterName: parameterName,
          named: parameter.isNamed,
        ),
      );
    }

    for (final field in fields) {
      if (!constructorFieldNames.contains(field.name) && !field.writable) {
        throw InvalidGenerationSourceError(
          'Field ${field.name} must be writable or provided by the unnamed constructor.',
          element: element,
        );
      }
    }

    final selfRefField = fields
        .where((field) => field.ref)
        .where((field) => _sameType(field.type, element.thisType))
        .firstOrNull;
    if (selfRefField != null) {
      throw InvalidGenerationSourceError(
        'Constructor-based generated serializers cannot bind self references early. '
        'Use a writable zero-argument constructor for ${element.displayName}.',
        element: selfRefField.type.element,
      );
    }

    final postConstructionFields = fields
        .where((field) => !constructorFieldNames.contains(field.name))
        .map((field) => field.name)
        .toList(growable: false);

    return _ConstructorPlan.constructor(
      arguments: arguments,
      postConstructionFieldNames: postConstructionFields,
    );
  }

  void _writeEnum(StringBuffer output, _GeneratedEnumSpec enumSpec) {
    final serializerClassName = '_${enumSpec.name}ForySerializer';
    final writeExpression = enumSpec.usesRawValue
        ? 'context.writeVarUint32(value.rawValue);'
        : 'context.writeVarUint32(value.index);';
    final readExpression = enumSpec.usesRawValue
        ? 'return ${enumSpec.name}.fromRawValue(context.readVarUint32());'
        : 'return ${enumSpec.name}.values[context.readVarUint32()];';
    output
      ..writeln(
        'final class $serializerClassName extends EnumSerializer<${enumSpec.name}> {',
      )
      ..writeln('  const $serializerClassName();')
      ..writeln('  @override')
      ..writeln('  void write(WriteContext context, ${enumSpec.name} value) {')
      ..writeln('    $writeExpression')
      ..writeln('  }')
      ..writeln()
      ..writeln('  @override')
      ..writeln('  ${enumSpec.name} read(ReadContext context) {')
      ..writeln('    $readExpression')
      ..writeln('  }')
      ..writeln('}')
      ..writeln();
  }

  void _writeStruct(StringBuffer output, _GeneratedStructSpec structSpec) {
    final serializerClassName = '_${structSpec.name}ForySerializer';
    final metadataListName = '_${_toCamelCase(structSpec.name)}ForyFieldInfo';
    final registrationName =
        '_${_toCamelCase(structSpec.name)}ForyRegistration';
    final hasRuntimeFastPath = structSpec.fields.any(
      (field) => !_usesDirectGeneratedBasicFastPath(field),
    );
    final directCursorRuns = _directGeneratedWriteReservationRuns(
      structSpec.fields,
    );
    final directCursorRunByStart = <int, _DirectGeneratedWriteReservationRun>{
      for (final run in directCursorRuns) run.start: run,
    };
    final directCursorRunByEnd = <int, _DirectGeneratedWriteReservationRun>{
      for (final run in directCursorRuns) run.end: run,
    };
    final directCursorStartByIndex = <int, int>{
      for (final run in directCursorRuns)
        for (var index = run.start; index <= run.end; index += 1)
          index: run.start,
    };

    output.writeln(
      'const List<GeneratedFieldInfo> $metadataListName = <GeneratedFieldInfo>[',
    );
    for (final field in structSpec.fields) {
      output.writeln(_fieldInfoLiteral(field));
    }
    output
      ..writeln('];')
      ..writeln()
      ..writeln(
        'typedef _${structSpec.name}FieldWriter = GeneratedStructFieldInfoWriter<${structSpec.name}>;',
      );
    if (structSpec.constructorPlan.mode == _ConstructorMode.mutable) {
      output.writeln(
        'typedef _${structSpec.name}FieldReader = GeneratedStructFieldInfoReader<${structSpec.name}>;',
      );
    }
    output.writeln();
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      final field = structSpec.fields[index];
      final fieldValue =
          _generatedFieldInfoWriteValueExpression(field, 'value.${field.name}');
      output
        ..writeln(
          'void _write${structSpec.name}Field$index(WriteContext context, GeneratedStructFieldInfo field, ${structSpec.name} value) {',
        )
        ..writeln(
          '  writeGeneratedStructFieldInfoValue(context, field, $fieldValue);',
        )
        ..writeln('}')
        ..writeln();
    }
    if (structSpec.constructorPlan.mode == _ConstructorMode.mutable) {
      for (var index = 0; index < structSpec.fields.length; index += 1) {
        final field = structSpec.fields[index];
        final readerFunctionName = field.readerFunctionName(structSpec.name);
        output
          ..writeln(
            'void _read${structSpec.name}Field$index(ReadContext context, ${structSpec.name} value, Object? rawValue) {',
          )
          ..writeln(
            '  value.${field.name} = $readerFunctionName(${_slotResolvedRawExpression('rawValue')}, value.${field.name});',
          )
          ..writeln('}')
          ..writeln();
      }
    }
    output
      ..writeln(
        'final GeneratedStructRegistration<${structSpec.name}> $registrationName = GeneratedStructRegistration<${structSpec.name}>(',
      )
      ..writeln('  fieldWritersBySlot: <_${structSpec.name}FieldWriter>[');
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      output.writeln('    _write${structSpec.name}Field$index,');
    }
    output
      ..writeln('  ],')
      ..writeln(
        structSpec.constructorPlan.mode == _ConstructorMode.mutable
            ? '  compatibleFactory: ${structSpec.name}.new,'
            : '  compatibleFactory: null,',
      );
    if (structSpec.constructorPlan.mode == _ConstructorMode.mutable) {
      output.writeln(
        '  compatibleReadersBySlot: <_${structSpec.name}FieldReader>[',
      );
      for (var index = 0; index < structSpec.fields.length; index += 1) {
        output.writeln('    _read${structSpec.name}Field$index,');
      }
      output.writeln('  ],');
    } else {
      output.writeln('  compatibleReadersBySlot: null,');
    }
    output
      ..writeln('  type: ${structSpec.name},')
      ..writeln('  serializerFactory: _${structSpec.name}ForySerializer.new,')
      ..writeln('  evolving: ${structSpec.evolving},')
      ..writeln('  fields: $metadataListName,')
      ..writeln(');')
      ..writeln()
      ..writeln(
        'final class $serializerClassName extends Serializer<${structSpec.name}> {',
      )
      ..writeln('  List<GeneratedStructFieldInfo>? _generatedFields;')
      ..writeln()
      ..writeln('  $serializerClassName();')
      ..writeln()
      ..writeln(
        '  List<GeneratedStructFieldInfo> _writeFields(WriteContext context) {',
      )
      ..writeln(
        '    return _generatedFields ??= buildGeneratedStructFieldInfos(',
      )
      ..writeln('      context.typeResolver,')
      ..writeln('      $registrationName,')
      ..writeln('    );')
      ..writeln('  }')
      ..writeln()
      ..writeln(
        '  List<GeneratedStructFieldInfo> _readFields(ReadContext context) {',
      )
      ..writeln(
        '    return _generatedFields ??= buildGeneratedStructFieldInfos(',
      )
      ..writeln('      context.typeResolver,')
      ..writeln('      $registrationName,')
      ..writeln('    );')
      ..writeln('  }')
      ..writeln('  @override')
      ..writeln(
        '  void write(WriteContext context, ${structSpec.name} value) {',
      )
      ..writeln('    final slots = generatedStructWriteSlots(context);')
      ..writeln('    if (slots == null) {');
    if (directCursorRuns.isNotEmpty) {
      output.writeln('      final buffer = context.buffer;');
    }
    if (hasRuntimeFastPath) {
      output.writeln('      final fields = _writeFields(context);');
    }
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      final field = structSpec.fields[index];
      final directCursorRun = directCursorRunByStart[index];
      if (directCursorRun != null) {
        output.writeln(
          '      final cursor$index = GeneratedWriteCursor.reserve(buffer, ${directCursorRun.bytes});',
        );
      }
      if (_usesReservedGeneratedFastPath(field)) {
        output.writeln(
          '      ${_directGeneratedCursorWriteStatement(field, 'cursor${directCursorStartByIndex[index]}', 'value.${field.name}')};',
        );
      } else if (_usesDirectGeneratedBasicFastPath(field)) {
        output.writeln(
          '      ${_directGeneratedWriteStatement(field, 'value.${field.name}')};',
        );
      } else if (_usesDirectGeneratedTypedContainerWriteFastPath(field)) {
        output.writeln(
          '      ${_directGeneratedTypedContainerWriteStatement(field, index, 'value.${field.name}')};',
        );
      } else {
        final fieldValue = _generatedFieldInfoWriteValueExpression(
          field,
          'value.${field.name}',
        );
        output.writeln(
          '      writeGeneratedStructFieldInfoValue(context, fields[$index], $fieldValue);',
        );
      }
      final directCursorEndRun = directCursorRunByEnd[index];
      if (directCursorEndRun != null) {
        output.writeln('      cursor${directCursorEndRun.start}.finish();');
      }
    }
    output
      ..writeln('      return;')
      ..writeln('    }')
      ..writeln('    final writers = $registrationName.fieldWritersBySlot;')
      ..writeln('    for (final field in slots.orderedFields) {')
      ..writeln('      writers[field.slot](context, field, value);')
      ..writeln('    }')
      ..writeln('  }')
      ..writeln()
      ..writeln('  @override')
      ..writeln('  ${structSpec.name} read(ReadContext context) {');

    switch (structSpec.constructorPlan.mode) {
      case _ConstructorMode.mutable:
        output
          ..writeln('    final slots = generatedStructReadSlots(context);')
          ..writeln('    final value = ${structSpec.name}();')
          ..writeln('    context.reference(value);')
          ..writeln('    if (slots == null) {');
        if (directCursorRuns.isNotEmpty) {
          output.writeln('      final buffer = context.buffer;');
        }
        if (hasRuntimeFastPath) {
          output.writeln('      final fields = _readFields(context);');
        }
        for (var index = 0; index < structSpec.fields.length; index += 1) {
          final field = structSpec.fields[index];
          final readerFunctionName = field.readerFunctionName(structSpec.name);
          final directCursorRun = directCursorRunByStart[index];
          if (directCursorRun != null) {
            output.writeln(
              '      final cursor$index = GeneratedReadCursor.start(buffer);',
            );
          }
          if (_usesReservedGeneratedFastPath(field)) {
            output.writeln(
              '      value.${field.name} = ${_directGeneratedCursorReadExpression(field, 'cursor${directCursorStartByIndex[index]}')};',
            );
          } else if (_usesDirectGeneratedBasicFastPath(field)) {
            output.writeln(
              '      value.${field.name} = ${_directGeneratedReadExpression(field)};',
            );
          } else if (_usesDirectGeneratedTypedContainerReadFastPath(field)) {
            output.writeln(
              '      value.${field.name} = ${_directGeneratedTypedContainerReadExpression(structSpec.name, field, 'fields[$index]')};',
            );
          } else if (_usesDirectGeneratedStructFieldFastPath(field)) {
            output.writeln(
              '      value.${field.name} = $readerFunctionName(readGeneratedStructDirectValue(context, fields[$index]), value.${field.name});',
            );
          } else if (_usesDirectGeneratedDeclaredReadFastPath(field)) {
            output.writeln(
              '      value.${field.name} = $readerFunctionName(readGeneratedStructDeclaredValue(context, fields[$index]), value.${field.name});',
            );
          } else {
            output.writeln(
              '      value.${field.name} = $readerFunctionName(readGeneratedStructFieldInfoValue(context, fields[$index], value.${field.name}), value.${field.name});',
            );
          }
          final directCursorEndRun = directCursorRunByEnd[index];
          if (directCursorEndRun != null) {
            output.writeln('      cursor${directCursorEndRun.start}.finish();');
          }
        }
        output.writeln('      return value;');
        output.writeln('    }');
        for (var index = 0; index < structSpec.fields.length; index += 1) {
          final field = structSpec.fields[index];
          final readerFunctionName = field.readerFunctionName(structSpec.name);
          final rawValueName = 'raw${structSpec.name}$index';
          output.writeln('    if (slots.containsSlot($index)) {');
          output.writeln(
            '      final $rawValueName = slots.valueForSlot($index);',
          );
          output.writeln(
            '      value.${field.name} = $readerFunctionName(${_slotResolvedRawExpression(rawValueName)}, value.${field.name});',
          );
          output.writeln('    }');
        }
        output.writeln('    return value;');
      case _ConstructorMode.constructor:
        output.writeln('    final slots = generatedStructReadSlots(context);');
        output.writeln('    if (slots == null) {');
        if (directCursorRuns.isNotEmpty) {
          output.writeln('      final buffer = context.buffer;');
        }
        if (hasRuntimeFastPath) {
          output.writeln('      final fields = _readFields(context);');
        }
        for (var index = 0; index < structSpec.fields.length; index += 1) {
          final field = structSpec.fields[index];
          final readerFunctionName = field.readerFunctionName(structSpec.name);
          final directCursorRun = directCursorRunByStart[index];
          if (directCursorRun != null) {
            output.writeln(
              '      final cursor$index = GeneratedReadCursor.start(buffer);',
            );
          }
          if (_usesReservedGeneratedFastPath(field)) {
            output.writeln(
              '      final ${field.displayType} ${field.localName} = ${_directGeneratedCursorReadExpression(field, 'cursor${directCursorStartByIndex[index]}')};',
            );
          } else if (_usesDirectGeneratedBasicFastPath(field)) {
            output.writeln(
              '      final ${field.displayType} ${field.localName} = ${_directGeneratedReadExpression(field)};',
            );
          } else if (_usesDirectGeneratedTypedContainerReadFastPath(field)) {
            output.writeln(
              '      final ${field.displayType} ${field.localName} = ${_directGeneratedTypedContainerReadExpression(structSpec.name, field, 'fields[$index]')};',
            );
          } else if (_usesDirectGeneratedStructFieldFastPath(field)) {
            output.writeln(
              '      final ${field.displayType} ${field.localName} = $readerFunctionName(readGeneratedStructDirectValue(context, fields[$index]));',
            );
          } else if (_usesDirectGeneratedDeclaredReadFastPath(field)) {
            output.writeln(
              '      final ${field.displayType} ${field.localName} = $readerFunctionName(readGeneratedStructDeclaredValue(context, fields[$index]));',
            );
          } else {
            output.writeln(
              '      final ${field.displayType} ${field.localName} = $readerFunctionName(readGeneratedStructFieldInfoValue(context, fields[$index]));',
            );
          }
          final directCursorEndRun = directCursorRunByEnd[index];
          if (directCursorEndRun != null) {
            output.writeln('      cursor${directCursorEndRun.start}.finish();');
          }
        }
        final constructorInvocation = _constructorInvocation(structSpec);
        output
          ..writeln('      final value = $constructorInvocation;')
          ..writeln('      context.reference(value);');
        for (final fieldName
            in structSpec.constructorPlan.postConstructionFieldNames) {
          final field = structSpec.fields.firstWhere(
            (item) => item.name == fieldName,
          );
          output.writeln('      value.${field.name} = ${field.localName};');
        }
        output.writeln('      return value;');
        // Slow path: schema-evolution slots present. Use `late final` so each
        // field can be conditionally assigned from its slot or read fresh.
        output.writeln('    }');
        for (var index = 0; index < structSpec.fields.length; index += 1) {
          final field = structSpec.fields[index];
          output.writeln(
            '    late final ${field.displayType} ${field.localName};',
          );
        }
        for (var index = 0; index < structSpec.fields.length; index += 1) {
          final field = structSpec.fields[index];
          final readerFunctionName = field.readerFunctionName(structSpec.name);
          final rawValueName = 'raw${structSpec.name}$index';
          output.writeln('    if (slots.containsSlot($index)) {');
          output.writeln(
            '      final $rawValueName = slots.valueForSlot($index);',
          );
          output.writeln(
            '      ${field.localName} = $readerFunctionName(${_slotResolvedRawExpression(rawValueName)});',
          );
          output.writeln('    } else {');
          output.writeln(
            '      ${field.localName} = $readerFunctionName(null);',
          );
          output.writeln('    }');
        }
        output
          ..writeln('    final value = $constructorInvocation;')
          ..writeln('    context.reference(value);');
        for (final fieldName
            in structSpec.constructorPlan.postConstructionFieldNames) {
          final field = structSpec.fields.firstWhere(
            (item) => item.name == fieldName,
          );
          output.writeln('    value.${field.name} = ${field.localName};');
        }
        output.writeln('    return value;');
    }

    output
      ..writeln('  }')
      ..writeln('}')
      ..writeln();

    for (final field in structSpec.fields) {
      if (_usesDirectGeneratedTypedContainerReadFastPath(field)) {
        _writeDirectContainerReaderHelpers(output, structSpec.name, field);
      }
      final readerFunctionName = field.readerFunctionName(structSpec.name);
      output
        ..writeln(
          '${field.displayType} $readerFunctionName(Object? value, [Object? fallback]) {',
        )
        ..writeln(
          '  return ${_conversionExpression(field, 'value', 'fallback')};',
        )
        ..writeln('}')
        ..writeln();
    }
  }

  void _writeRegistrationHelpers(
    StringBuffer output, {
    required List<_GeneratedEnumSpec> enumSpecs,
    required List<_GeneratedStructSpec> structSpecs,
    required String generatedApiName,
  }) {
    for (final enumSpec in enumSpecs) {
      final registrationName =
          '_${_toCamelCase(enumSpec.name)}ForyRegistration';
      output
        ..writeln(
          'final GeneratedEnumRegistration $registrationName = GeneratedEnumRegistration(',
        )
        ..writeln('  type: ${enumSpec.name},')
        ..writeln('  serializerFactory: _${enumSpec.name}ForySerializer.new,')
        ..writeln(');')
        ..writeln();
    }
    if (enumSpecs.isNotEmpty && structSpecs.isNotEmpty) {
      output.writeln();
    }

    output
      ..writeln('abstract final class $generatedApiName {')
      ..writeln('  static void register(')
      ..writeln('    Fory fory,')
      ..writeln('    Type type, {')
      ..writeln('    int? id,')
      ..writeln('    String? namespace,')
      ..writeln('    String? typeName,')
      ..writeln('  }) {')
      ..writeln('    final hasNumeric = id != null;')
      ..writeln('    final hasNamed = namespace != null || typeName != null;')
      ..writeln('    if (hasNumeric == hasNamed) {')
      ..writeln(
        "      throw ArgumentError('Exactly one registration mode is required: id, or namespace + typeName.');",
      )
      ..writeln('    }')
      ..writeln(
        '    if (hasNamed && (namespace == null || typeName == null)) {',
      )
      ..writeln(
        "      throw ArgumentError('Both namespace and typeName are required for named registration.');",
      )
      ..writeln('    }');

    for (final enumSpec in enumSpecs) {
      final registrationName =
          '_${_toCamelCase(enumSpec.name)}ForyRegistration';
      output.writeln('  if (type == ${enumSpec.name}) {');
      output.writeln('    registerGeneratedEnum(');
      output.writeln('      fory,');
      output.writeln('      $registrationName,');
      output.writeln('      id: id,');
      output.writeln('      namespace: namespace,');
      output.writeln('      typeName: typeName,');
      output.writeln('    );');
      output.writeln('    return;');
      output.writeln('  }');
    }
    for (final structSpec in structSpecs) {
      final registrationName =
          '_${_toCamelCase(structSpec.name)}ForyRegistration';
      output.writeln('  if (type == ${structSpec.name}) {');
      output.writeln('    registerGeneratedStruct(');
      output.writeln('      fory,');
      output.writeln('      $registrationName,');
      output.writeln('      id: id,');
      output.writeln('      namespace: namespace,');
      output.writeln('      typeName: typeName,');
      output.writeln('    );');
      output.writeln('    return;');
      output.writeln('  }');
    }

    output
      ..writeln(
        "  throw ArgumentError.value(type, 'type', 'No generated serializer metadata for this library.');",
      )
      ..writeln('}')
      ..writeln('}')
      ..writeln();
  }

  String _constructorInvocation(_GeneratedStructSpec structSpec) {
    final positionalArguments = <String>[];
    final namedArguments = <String>[];
    for (final argument in structSpec.constructorPlan.arguments) {
      final field = structSpec.fields.firstWhere(
        (item) => item.name == argument.fieldName,
      );
      if (argument.named) {
        namedArguments.add('${argument.parameterName}: ${field.localName}');
      } else {
        positionalArguments.add(field.localName);
      }
    }
    final arguments = <String>[
      ...positionalArguments,
      ...namedArguments,
    ].join(', ');
    return '${structSpec.name}($arguments)';
  }

  String _slotResolvedRawExpression(String rawValueExpression) {
    return 'resolveGeneratedSlotRawValue(context, $rawValueExpression)';
  }

  bool _isSkipped(FieldElement field) {
    final annotation = _fieldAnnotationOf(field);
    if (annotation == null) {
      return false;
    }
    return ConstantReader(annotation).peek('skip')?.boolValue ?? false;
  }

  String _fieldInfoLiteral(_GeneratedFieldSpec field) {
    final idLiteral = field.id == null ? 'null' : '${field.id}';
    return '''
  GeneratedFieldInfo(
    name: '${field.name}',
    identifier: '${field.identifier}',
    id: $idLiteral,
    fieldType: ${_fieldTypeLiteral(field.fieldType)},
  ),''';
  }

  String _fieldTypeLiteral(_GeneratedFieldTypeSpec fieldType) {
    final argumentsLiteral = fieldType.arguments.isEmpty
        ? '<GeneratedFieldType>[]'
        : '<GeneratedFieldType>[\n${fieldType.arguments.map(_fieldTypeLiteral).join(',\n')}\n      ]';
    final dynamicLiteral = switch (fieldType.dynamic) {
      true => 'true',
      false => 'false',
      null => 'null',
    };
    return '''
GeneratedFieldType(
      type: ${fieldType.typeLiteral},
      declaredTypeName: '${fieldType.typeLiteral}',
      typeId: ${fieldType.typeId},
      nullable: ${fieldType.nullable},
      ref: ${fieldType.ref},
      dynamic: $dynamicLiteral,
      arguments: $argumentsLiteral,
    )''';
  }

  String debugConversionExpressionForType(
    DartType type,
    DebugGeneratedFieldTypeSpec fieldType,
    String valueExpression, {
    required String nullExpression,
  }) {
    return _conversionExpressionForType(
      type,
      _fromDebugFieldType(fieldType),
      valueExpression,
      nullExpression: nullExpression,
    );
  }

  _GeneratedFieldTypeSpec _fromDebugFieldType(
    DebugGeneratedFieldTypeSpec fieldType,
  ) {
    return _GeneratedFieldTypeSpec(
      typeLiteral: fieldType.typeLiteral,
      declaredTypeName: fieldType.declaredTypeName,
      typeId: fieldType.typeId,
      nullable: fieldType.nullable,
      ref: fieldType.ref,
      dynamic: fieldType.dynamic,
      arguments:
          fieldType.arguments.map(_fromDebugFieldType).toList(growable: false),
    );
  }

  String _conversionExpression(
    _GeneratedFieldSpec field,
    String valueExpression,
    String fallbackExpression,
  ) {
    return _conversionExpressionForType(
      field.type,
      field.fieldType,
      valueExpression,
      nullExpression: _nullExpression(
        field.type,
        errorTarget: 'field ${field.name}',
        fallbackExpression: fallbackExpression,
      ),
    );
  }

  String _conversionExpressionForType(
    DartType type,
    _GeneratedFieldTypeSpec fieldType,
    String valueExpression, {
    required String nullExpression,
  }) {
    if (_withoutNullability(type).isDartCoreObject) {
      if (_isNullable(type)) {
        return valueExpression;
      }
      return '$valueExpression == null ? $nullExpression : $valueExpression';
    }
    if (_isNullable(type)) {
      final nonNullableType = _withoutNullability(type);
      final nonNullableFieldType = _nonNullableFieldType(fieldType);
      final converted = _conversionExpressionForType(
        nonNullableType,
        nonNullableFieldType,
        valueExpression,
        nullExpression: _nullExpression(nonNullableType, errorTarget: 'value'),
      );
      return '$valueExpression == null ? $nullExpression : $converted';
    }
    final converted = _conversionExpressionWithoutNullCheck(
      type,
      fieldType,
      valueExpression,
    );
    return '$valueExpression == null ? $nullExpression : $converted';
  }

  String _conversionExpressionWithoutNullCheck(
    DartType type,
    _GeneratedFieldTypeSpec fieldType,
    String valueExpression,
  ) {
    if (_isList(type)) {
      if (fieldType.typeId != TypeIds.list) {
        return '$valueExpression as ${_typeCodeString(type)}';
      }
      final elementType = (type as InterfaceType).typeArguments.single;
      final elementFieldType = fieldType.arguments.single;
      if (_supportsDirectContainerCast(elementType, elementFieldType)) {
        return 'List.castFrom<dynamic, ${_typeCodeString(elementType)}>($valueExpression as List)';
      }
      final convertedElement = _conversionExpressionForType(
        elementType,
        elementFieldType,
        'item',
        nullExpression: _nullExpression(elementType, errorTarget: 'list item'),
      );
      return 'List<${_typeCodeString(elementType)}>.of((($valueExpression as List)).map((item) => $convertedElement))';
    }
    if (_isBoolList(type)) {
      if (fieldType.typeId == TypeIds.boolArray) {
        return '$valueExpression as BoolList';
      }
      return 'BoolList.fromList(($valueExpression as List).cast<bool>())';
    }
    if (_isSet(type)) {
      if (fieldType.typeId != TypeIds.set) {
        return '$valueExpression as ${_typeCodeString(type)}';
      }
      final elementType = (type as InterfaceType).typeArguments.single;
      final elementFieldType = fieldType.arguments.single;
      if (_supportsDirectContainerCast(elementType, elementFieldType)) {
        return 'Set.castFrom<dynamic, ${_typeCodeString(elementType)}>($valueExpression as Set)';
      }
      final convertedElement = _conversionExpressionForType(
        elementType,
        elementFieldType,
        'item',
        nullExpression: _nullExpression(elementType, errorTarget: 'set item'),
      );
      return 'Set<${_typeCodeString(elementType)}>.of((($valueExpression as Set)).map((item) => $convertedElement))';
    }
    if (_isMap(type)) {
      final arguments = (type as InterfaceType).typeArguments;
      final keyType = arguments[0];
      final valueType = arguments[1];
      final keyFieldType = fieldType.arguments[0];
      final valueFieldType = fieldType.arguments[1];
      if (_supportsDirectContainerCast(keyType, keyFieldType) &&
          _supportsDirectContainerCast(valueType, valueFieldType)) {
        return 'Map.castFrom<dynamic, dynamic, ${_typeCodeString(keyType)}, ${_typeCodeString(valueType)}>($valueExpression as Map)';
      }
      final convertedKey = _conversionExpressionForType(
        keyType,
        keyFieldType,
        'key',
        nullExpression: _nullExpression(keyType, errorTarget: 'map key'),
      );
      final convertedValue = _conversionExpressionForType(
        valueType,
        valueFieldType,
        'value',
        nullExpression: _nullExpression(valueType, errorTarget: 'map value'),
      );
      return 'Map<${_typeCodeString(keyType)}, ${_typeCodeString(valueType)}>.of((($valueExpression as Map)).map((key, value) => MapEntry($convertedKey, $convertedValue)))';
    }
    if (type.isDartCoreInt) {
      switch (fieldType.typeId) {
        case TypeIds.int64:
        case TypeIds.varInt64:
        case TypeIds.taggedInt64:
          return 'switch ($valueExpression) { Int64 typed => typed.toInt(), int typed => typed, _ => throw StateError(\'Expected int or Int64.\') }';
        case TypeIds.uint64:
        case TypeIds.varUint64:
        case TypeIds.taggedUint64:
          return 'switch ($valueExpression) { Uint64 typed => typed.toInt(), int typed => typed, _ => throw StateError(\'Expected int or Uint64.\') }';
        default:
          return '$valueExpression as int';
      }
    }
    if (type.isDartCoreDouble) {
      if (fieldType.typeId == TypeIds.float32) {
        return 'switch ($valueExpression) { double typed => typed, Float32 typed => typed.value, _ => throw StateError(\'Expected double or Float32.\') }';
      }
      return '$valueExpression as double';
    }
    if (type.isDartCoreBool) {
      return '$valueExpression as bool';
    }
    if (type.isDartCoreString) {
      return '$valueExpression as String';
    }
    return '$valueExpression as ${_typeCodeString(type)}';
  }

  bool _supportsDirectContainerCast(
    DartType type,
    _GeneratedFieldTypeSpec fieldType,
  ) {
    if (_isNullable(type)) {
      return _supportsDirectContainerCast(
        _withoutNullability(type),
        _nonNullableFieldType(fieldType),
      );
    }
    if (_isList(type) || _isSet(type) || _isMap(type)) {
      return false;
    }
    if (type.isDartCoreInt) {
      return true;
    }
    return true;
  }

  bool _usesDirectGeneratedBasicFastPath(_GeneratedFieldSpec field) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        field.fieldType.dynamic == true) {
      return false;
    }
    return _isPrimitiveTypeId(field.fieldType.typeId) ||
        field.fieldType.typeId == TypeIds.string ||
        _isBuiltInTypeId(field.fieldType.typeId) ||
        field.fieldType.typeId == TypeIds.enumById;
  }

  bool _usesDirectGeneratedDeclaredReadFastPath(_GeneratedFieldSpec field) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        field.fieldType.dynamic == true) {
      return false;
    }
    final typeId = field.fieldType.typeId;
    return typeId == TypeIds.ext || typeId == TypeIds.namedExt;
  }

  bool _usesDirectGeneratedStructFieldFastPath(_GeneratedFieldSpec field) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        field.fieldType.dynamic == true) {
      return false;
    }
    final typeId = field.fieldType.typeId;
    return typeId == TypeIds.struct ||
        typeId == TypeIds.compatibleStruct ||
        typeId == TypeIds.namedStruct ||
        typeId == TypeIds.namedCompatibleStruct;
  }

  bool _usesDirectGeneratedTypedContainerReadFastPath(
    _GeneratedFieldSpec field,
  ) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        field.fieldType.dynamic == true) {
      return false;
    }
    if (_isBoolList(field.type)) {
      return false;
    }
    return field.fieldType.typeId == TypeIds.list ||
        field.fieldType.typeId == TypeIds.set ||
        field.fieldType.typeId == TypeIds.map;
  }

  bool _usesDirectGeneratedTypedContainerWriteFastPath(
    _GeneratedFieldSpec field,
  ) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        field.fieldType.dynamic == true) {
      return false;
    }
    if (_isBoolList(field.type)) {
      return false;
    }
    final typeId = field.fieldType.typeId;
    if (typeId != TypeIds.list && typeId != TypeIds.set) {
      return false;
    }
    final elementFieldType = field.fieldType.arguments.single;
    if (elementFieldType.ref || elementFieldType.dynamic == true) {
      return false;
    }
    final elementType = (field.type as InterfaceType).typeArguments.single;
    return !_isNullable(elementType);
  }

  String _directGeneratedTypedContainerWriteStatement(
    _GeneratedFieldSpec field,
    int fieldIndex,
    String valueExpression,
  ) {
    if (_isList(field.type)) {
      final elementType = (field.type as InterfaceType).typeArguments.single;
      return 'writeGeneratedDirectListValue<${_typeCodeString(elementType)}>(context, fields[$fieldIndex], $valueExpression)';
    }
    if (_isSet(field.type)) {
      final elementType = (field.type as InterfaceType).typeArguments.single;
      return 'writeGeneratedDirectSetValue<${_typeCodeString(elementType)}>(context, fields[$fieldIndex], $valueExpression)';
    }
    throw StateError(
      'Unsupported generated typed container write fast path for ${field.name}.',
    );
  }

  List<_DirectGeneratedWriteReservationRun>
      _directGeneratedWriteReservationRuns(List<_GeneratedFieldSpec> fields) {
    final runs = <_DirectGeneratedWriteReservationRun>[];
    int? start;
    var bytes = 0;
    for (var index = 0; index < fields.length; index += 1) {
      final fieldBytes = _directGeneratedWriteReservationBytes(fields[index]);
      if (fieldBytes == null) {
        if (start != null) {
          runs.add(
            _DirectGeneratedWriteReservationRun(start, index - 1, bytes),
          );
          start = null;
          bytes = 0;
        }
        continue;
      }
      start ??= index;
      bytes += fieldBytes;
    }
    if (start != null) {
      runs.add(
        _DirectGeneratedWriteReservationRun(start, fields.length - 1, bytes),
      );
    }
    return runs;
  }

  bool _usesReservedGeneratedFastPath(_GeneratedFieldSpec field) {
    return _directGeneratedWriteReservationBytes(field) != null;
  }

  int? _directGeneratedWriteReservationBytes(_GeneratedFieldSpec field) {
    if (!_usesDirectGeneratedBasicFastPath(field)) {
      return null;
    }
    switch (field.fieldType.typeId) {
      case TypeIds.boolType:
      case TypeIds.int8:
      case TypeIds.uint8:
        return 1;
      case TypeIds.int16:
      case TypeIds.uint16:
      case TypeIds.float16:
      case TypeIds.bfloat16:
        return 2;
      case TypeIds.int32:
      case TypeIds.uint32:
      case TypeIds.float32:
        return 4;
      case TypeIds.date:
        return 10;
      case TypeIds.int64:
      case TypeIds.uint64:
      case TypeIds.float64:
        return 8;
      case TypeIds.duration:
        return 14;
      case TypeIds.timestamp:
        return 12;
      case TypeIds.varInt32:
      case TypeIds.varUint32:
      case TypeIds.enumById:
        return 5;
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
        return 10;
      default:
        return null;
    }
  }

  String _directGeneratedWriteStatement(
    _GeneratedFieldSpec field,
    String valueExpression,
  ) {
    switch (field.fieldType.typeId) {
      case TypeIds.boolType:
        return 'buffer.writeBool($valueExpression)';
      case TypeIds.int8:
        return 'buffer.writeByte(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.int16:
        return 'buffer.writeInt16(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.int32:
        return 'buffer.writeInt32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varInt32:
        return 'buffer.writeVarInt32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.int64:
        if (field.type.isDartCoreInt) {
          return 'buffer.writeInt64FromInt($valueExpression)';
        }
        return 'buffer.writeInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varInt64:
        if (field.type.isDartCoreInt) {
          return 'buffer.writeVarInt64FromInt($valueExpression)';
        }
        return 'buffer.writeVarInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.taggedInt64:
        if (field.type.isDartCoreInt) {
          return 'buffer.writeTaggedInt64FromInt($valueExpression)';
        }
        return 'buffer.writeTaggedInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint8:
        return 'buffer.writeUint8(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint16:
        return 'buffer.writeUint16(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint32:
        return 'buffer.writeUint32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varUint32:
        return 'buffer.writeVarUint32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint64:
        return 'buffer.writeUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varUint64:
        return 'buffer.writeVarUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.taggedUint64:
        return 'buffer.writeTaggedUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.float16:
        return 'buffer.writeFloat16($valueExpression)';
      case TypeIds.bfloat16:
        return 'buffer.writeBfloat16($valueExpression)';
      case TypeIds.float32:
        return 'buffer.writeFloat32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.float64:
        return 'buffer.writeFloat64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.string:
        return 'context.writeString($valueExpression)';
      case TypeIds.binary:
        return 'writeGeneratedBinaryValue(context, $valueExpression)';
      case TypeIds.decimal:
        return 'writeGeneratedDecimalValue(context, $valueExpression)';
      case TypeIds.date:
        return 'writeGeneratedLocalDateValue(context, $valueExpression)';
      case TypeIds.duration:
        return 'writeGeneratedDurationValue(context, $valueExpression)';
      case TypeIds.timestamp:
        return _isDateTimeType(field.type)
            ? 'writeGeneratedDateTimeValue(context, $valueExpression)'
            : 'writeGeneratedTimestampValue(context, $valueExpression)';
      case TypeIds.boolArray:
        return 'writeGeneratedBoolArrayValue(context, $valueExpression)';
      case TypeIds.int8Array:
      case TypeIds.int16Array:
      case TypeIds.int32Array:
      case TypeIds.int64Array:
      case TypeIds.uint8Array:
      case TypeIds.uint16Array:
      case TypeIds.uint32Array:
      case TypeIds.uint64Array:
      case TypeIds.float16Array:
      case TypeIds.bfloat16Array:
      case TypeIds.float32Array:
      case TypeIds.float64Array:
        return 'writeGeneratedFixedArrayValue(context, $valueExpression)';
      case TypeIds.enumById:
        return _enumWriteExpression(field.type, valueExpression);
      default:
        throw StateError(
          'Unsupported generated direct write fast path for ${field.name}.',
        );
    }
  }

  String _directGeneratedCursorWriteStatement(
    _GeneratedFieldSpec field,
    String cursorExpression,
    String valueExpression,
  ) {
    switch (field.fieldType.typeId) {
      case TypeIds.boolType:
        return '$cursorExpression.writeBool($valueExpression)';
      case TypeIds.int8:
        return '$cursorExpression.writeByte(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.int16:
        return '$cursorExpression.writeInt16(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.int32:
        return '$cursorExpression.writeInt32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varInt32:
        return '$cursorExpression.writeVarInt32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.int64:
        if (field.type.isDartCoreInt) {
          return '$cursorExpression.writeInt64FromInt($valueExpression)';
        }
        return '$cursorExpression.writeInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varInt64:
        if (field.type.isDartCoreInt) {
          return '$cursorExpression.writeVarInt64FromInt($valueExpression)';
        }
        return '$cursorExpression.writeVarInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.taggedInt64:
        if (field.type.isDartCoreInt) {
          return '$cursorExpression.writeTaggedInt64FromInt($valueExpression)';
        }
        return '$cursorExpression.writeTaggedInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint8:
        return '$cursorExpression.writeUint8(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint16:
        return '$cursorExpression.writeUint16(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint32:
        return '$cursorExpression.writeUint32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varUint32:
        return '$cursorExpression.writeVarUint32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.uint64:
        if (field.type.isDartCoreInt) {
          return '$cursorExpression.writeUint64FromInt($valueExpression)';
        }
        return '$cursorExpression.writeUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varUint64:
        if (field.type.isDartCoreInt) {
          return '$cursorExpression.writeVarUint64FromInt($valueExpression)';
        }
        return '$cursorExpression.writeVarUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.taggedUint64:
        if (field.type.isDartCoreInt) {
          return '$cursorExpression.writeTaggedUint64FromInt($valueExpression)';
        }
        return '$cursorExpression.writeTaggedUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.float16:
        return '$cursorExpression.writeFloat16($valueExpression)';
      case TypeIds.bfloat16:
        return '$cursorExpression.writeBfloat16($valueExpression)';
      case TypeIds.float32:
        return '$cursorExpression.writeFloat32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.float64:
        return '$cursorExpression.writeFloat64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.date:
        return '$cursorExpression.writeVarInt64($valueExpression.toEpochDay())';
      case TypeIds.duration:
        return '$cursorExpression.writeVarInt64(generatedDurationWireSeconds($valueExpression)); $cursorExpression.writeInt32(generatedDurationWireNanoseconds($valueExpression))';
      case TypeIds.timestamp:
        return _isDateTimeType(field.type)
            ? '$cursorExpression.writeInt64(generatedDateTimeWireSeconds($valueExpression)); $cursorExpression.writeUint32(generatedDateTimeWireNanoseconds($valueExpression))'
            : '$cursorExpression.writeInt64($valueExpression.seconds); $cursorExpression.writeUint32(generatedTimestampWireNanoseconds($valueExpression))';
      case TypeIds.enumById:
        return _enumCursorWriteExpression(
          field.type,
          cursorExpression,
          valueExpression,
        );
      default:
        throw StateError(
          'Unsupported generated direct cursor write fast path for ${field.name}.',
        );
    }
  }

  String _directGeneratedReadExpression(_GeneratedFieldSpec field) {
    switch (field.fieldType.typeId) {
      case TypeIds.boolType:
        return 'buffer.readBool()';
      case TypeIds.int8:
        return 'buffer.readByte()';
      case TypeIds.int16:
        return 'buffer.readInt16()';
      case TypeIds.int32:
        return 'buffer.readInt32()';
      case TypeIds.varInt32:
        return 'buffer.readVarInt32()';
      case TypeIds.int64:
        return field.type.isDartCoreInt
            ? 'buffer.readInt64AsInt()'
            : 'buffer.readInt64()';
      case TypeIds.varInt64:
        return field.type.isDartCoreInt
            ? 'buffer.readVarInt64AsInt()'
            : 'buffer.readVarInt64()';
      case TypeIds.taggedInt64:
        return field.type.isDartCoreInt
            ? 'buffer.readTaggedInt64AsInt()'
            : 'buffer.readTaggedInt64()';
      case TypeIds.uint8:
        return 'buffer.readUint8()';
      case TypeIds.uint16:
        return 'buffer.readUint16()';
      case TypeIds.uint32:
        return 'buffer.readUint32()';
      case TypeIds.varUint32:
        return 'buffer.readVarUint32()';
      case TypeIds.uint64:
        return field.type.isDartCoreInt
            ? 'buffer.readUint64().toInt()'
            : 'buffer.readUint64()';
      case TypeIds.varUint64:
        return field.type.isDartCoreInt
            ? 'buffer.readVarUint64().toInt()'
            : 'buffer.readVarUint64()';
      case TypeIds.taggedUint64:
        return field.type.isDartCoreInt
            ? 'buffer.readTaggedUint64().toInt()'
            : 'buffer.readTaggedUint64()';
      case TypeIds.float16:
        return 'buffer.readFloat16()';
      case TypeIds.bfloat16:
        return 'buffer.readBfloat16()';
      case TypeIds.float32:
        return field.type.isDartCoreDouble
            ? 'buffer.readFloat32()'
            : 'Float32(buffer.readFloat32())';
      case TypeIds.float64:
        return 'buffer.readFloat64()';
      case TypeIds.string:
        return 'context.readString()';
      case TypeIds.binary:
        return 'readGeneratedBinaryValue(context)';
      case TypeIds.decimal:
        return 'readGeneratedDecimalValue(context)';
      case TypeIds.date:
        return 'readGeneratedLocalDateValue(context)';
      case TypeIds.duration:
        return 'readGeneratedDurationValue(context)';
      case TypeIds.timestamp:
        return _isDateTimeType(field.type)
            ? 'readGeneratedDateTimeValue(context)'
            : 'readGeneratedTimestampValue(context)';
      case TypeIds.boolArray:
        return 'readGeneratedBoolArrayValue(context)';
      case TypeIds.int8Array:
        return 'readGeneratedTypedArrayValue<Int8List>(context, 1, (bytes) => bytes.buffer.asInt8List(bytes.offsetInBytes, bytes.lengthInBytes))';
      case TypeIds.int16Array:
        return 'readGeneratedTypedArrayValue<Int16List>(context, 2, (bytes) => bytes.buffer.asInt16List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 2))';
      case TypeIds.int32Array:
        return 'readGeneratedTypedArrayValue<Int32List>(context, 4, (bytes) => bytes.buffer.asInt32List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 4))';
      case TypeIds.int64Array:
        return 'readGeneratedTypedArrayValue<Int64List>(context, 8, (bytes) => Int64List.view(bytes.buffer, bytes.offsetInBytes, bytes.lengthInBytes ~/ 8))';
      case TypeIds.uint8Array:
        return 'readGeneratedBinaryValue(context)';
      case TypeIds.uint16Array:
        return 'readGeneratedTypedArrayValue<Uint16List>(context, 2, (bytes) => bytes.buffer.asUint16List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 2))';
      case TypeIds.float16Array:
        return 'readGeneratedTypedArrayValue<Float16List>(context, 2, (bytes) => Float16List.view(bytes.buffer, bytes.offsetInBytes, bytes.lengthInBytes ~/ 2))';
      case TypeIds.bfloat16Array:
        return 'readGeneratedTypedArrayValue<Bfloat16List>(context, 2, (bytes) => Bfloat16List.view(bytes.buffer, bytes.offsetInBytes, bytes.lengthInBytes ~/ 2))';
      case TypeIds.uint32Array:
        return 'readGeneratedTypedArrayValue<Uint32List>(context, 4, (bytes) => bytes.buffer.asUint32List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 4))';
      case TypeIds.uint64Array:
        return 'readGeneratedTypedArrayValue<Uint64List>(context, 8, (bytes) => Uint64List.view(bytes.buffer, bytes.offsetInBytes, bytes.lengthInBytes ~/ 8))';
      case TypeIds.float32Array:
        return 'readGeneratedTypedArrayValue<Float32List>(context, 4, (bytes) => bytes.buffer.asFloat32List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 4))';
      case TypeIds.float64Array:
        return 'readGeneratedTypedArrayValue<Float64List>(context, 8, (bytes) => bytes.buffer.asFloat64List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 8))';
      case TypeIds.enumById:
        return _enumReadExpression(field.type, 'context');
      default:
        throw StateError(
          'Unsupported generated direct read fast path for ${field.name}.',
        );
    }
  }

  String _directGeneratedCursorReadExpression(
    _GeneratedFieldSpec field,
    String cursorExpression,
  ) {
    switch (field.fieldType.typeId) {
      case TypeIds.boolType:
        return '$cursorExpression.readBool()';
      case TypeIds.int8:
        return '$cursorExpression.readByte()';
      case TypeIds.int16:
        return '$cursorExpression.readInt16()';
      case TypeIds.int32:
        return '$cursorExpression.readInt32()';
      case TypeIds.varInt32:
        return '$cursorExpression.readVarInt32()';
      case TypeIds.int64:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readInt64AsInt()'
            : '$cursorExpression.readInt64()';
      case TypeIds.varInt64:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readVarInt64AsInt()'
            : '$cursorExpression.readVarInt64()';
      case TypeIds.taggedInt64:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readTaggedInt64AsInt()'
            : '$cursorExpression.readTaggedInt64()';
      case TypeIds.uint8:
        return '$cursorExpression.readUint8()';
      case TypeIds.uint16:
        return '$cursorExpression.readUint16()';
      case TypeIds.uint32:
        return '$cursorExpression.readUint32()';
      case TypeIds.varUint32:
        return '$cursorExpression.readVarUint32()';
      case TypeIds.uint64:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readUint64AsInt()'
            : '$cursorExpression.readUint64()';
      case TypeIds.varUint64:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readVarUint64AsInt()'
            : '$cursorExpression.readVarUint64()';
      case TypeIds.taggedUint64:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readTaggedUint64AsInt()'
            : '$cursorExpression.readTaggedUint64()';
      case TypeIds.float16:
        return '$cursorExpression.readFloat16()';
      case TypeIds.bfloat16:
        return '$cursorExpression.readBfloat16()';
      case TypeIds.float32:
        return field.type.isDartCoreDouble
            ? '$cursorExpression.readFloat32()'
            : 'Float32($cursorExpression.readFloat32())';
      case TypeIds.float64:
        return '$cursorExpression.readFloat64()';
      case TypeIds.date:
        return 'LocalDate.fromEpochDay($cursorExpression.readVarInt64())';
      case TypeIds.duration:
        return 'readGeneratedDurationFromWire($cursorExpression.readVarInt64(), $cursorExpression.readInt32())';
      case TypeIds.timestamp:
        return _isDateTimeType(field.type)
            ? 'readGeneratedDateTimeFromWire($cursorExpression.readInt64(), $cursorExpression.readUint32())'
            : 'readGeneratedTimestampFromWire($cursorExpression.readInt64(), $cursorExpression.readUint32())';
      case TypeIds.enumById:
        return _enumCursorReadExpression(field.type, cursorExpression);
      default:
        throw StateError(
          'Unsupported generated direct cursor read fast path for ${field.name}.',
        );
    }
  }

  String _directGeneratedTypedContainerReadExpression(
    String structName,
    _GeneratedFieldSpec field,
    String fieldRuntimeExpression,
  ) {
    if (_isList(field.type)) {
      final elementType = (field.type as InterfaceType).typeArguments.single;
      return 'readGeneratedDirectListValue<${_typeCodeString(elementType)}>(context, $fieldRuntimeExpression, ${_containerElementReaderFunctionName(structName, field)})';
    }
    if (_isSet(field.type)) {
      final elementType = (field.type as InterfaceType).typeArguments.single;
      return 'readGeneratedDirectSetValue<${_typeCodeString(elementType)}>(context, $fieldRuntimeExpression, ${_containerElementReaderFunctionName(structName, field)})';
    }
    if (_isMap(field.type)) {
      final arguments = (field.type as InterfaceType).typeArguments;
      return 'readGeneratedDirectMapValue<${_typeCodeString(arguments[0])}, ${_typeCodeString(arguments[1])}>(context, $fieldRuntimeExpression, ${_containerKeyReaderFunctionName(structName, field)}, ${_containerValueReaderFunctionName(structName, field)})';
    }
    throw StateError(
      'Unsupported generated typed container read fast path for ${field.name}.',
    );
  }

  String _directGeneratedScalarExpression(
    _GeneratedFieldSpec field,
    String valueExpression,
  ) {
    if (field.type.isDartCoreInt) {
      switch (field.fieldType.typeId) {
        case TypeIds.int64:
        case TypeIds.varInt64:
        case TypeIds.taggedInt64:
          return 'Int64($valueExpression)';
        case TypeIds.uint64:
        case TypeIds.varUint64:
        case TypeIds.taggedUint64:
          return 'Uint64($valueExpression)';
        default:
          return _checkedGeneratedScalarExpression(
              field.fieldType.typeId, valueExpression);
      }
    }
    if (field.type.isDartCoreDouble ||
        field.type.isDartCoreBool ||
        field.type.isDartCoreString) {
      return valueExpression;
    }
    switch (field.fieldType.typeId) {
      case TypeIds.int64:
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.uint64:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
      case TypeIds.float16:
      case TypeIds.bfloat16:
        return valueExpression;
      default:
        return '$valueExpression.value';
    }
  }

  String _generatedFieldInfoWriteValueExpression(
    _GeneratedFieldSpec field,
    String valueExpression,
  ) =>
      valueExpression;

  String _nullExpression(
    DartType type, {
    required String errorTarget,
    String? fallbackExpression,
  }) {
    final displayType = _typeCodeString(type);
    if (_isNullable(type)) {
      return 'null as $displayType';
    }
    if (fallbackExpression != null) {
      return '($fallbackExpression != null ? $fallbackExpression as $displayType : (throw StateError(\'Received null for non-nullable $errorTarget.\')))';
    }
    return '(throw StateError(\'Received null for non-nullable $errorTarget.\'))';
  }

  _GeneratedFieldTypeSpec _nonNullableFieldType(
    _GeneratedFieldTypeSpec fieldType,
  ) {
    if (!fieldType.nullable) {
      return fieldType;
    }
    return _GeneratedFieldTypeSpec(
      typeLiteral: fieldType.typeLiteral,
      declaredTypeName: fieldType.declaredTypeName,
      typeId: fieldType.typeId,
      nullable: false,
      ref: fieldType.ref,
      dynamic: fieldType.dynamic,
      arguments: fieldType.arguments,
    );
  }

  void _writeDirectContainerReaderHelpers(
    StringBuffer output,
    String structName,
    _GeneratedFieldSpec field,
  ) {
    if (_isList(field.type) || _isSet(field.type)) {
      final elementType = (field.type as InterfaceType).typeArguments.single;
      final elementFieldType = field.fieldType.arguments.single;
      final functionName = _containerElementReaderFunctionName(
        structName,
        field,
      );
      output
        ..writeln(
          '${_typeCodeString(elementType)} $functionName(Object? value) {',
        )
        ..writeln(
          '  return ${_conversionExpressionForType(elementType, elementFieldType, 'value', nullExpression: _nullExpression(elementType, errorTarget: '${field.name} item'))};',
        )
        ..writeln('}')
        ..writeln();
      return;
    }
    if (_isMap(field.type)) {
      final arguments = (field.type as InterfaceType).typeArguments;
      final keyType = arguments[0];
      final valueType = arguments[1];
      final keyFieldType = field.fieldType.arguments[0];
      final valueFieldType = field.fieldType.arguments[1];
      final keyFunctionName = _containerKeyReaderFunctionName(
        structName,
        field,
      );
      final valueFunctionName = _containerValueReaderFunctionName(
        structName,
        field,
      );
      output
        ..writeln(
          '${_typeCodeString(keyType)} $keyFunctionName(Object? value) {',
        )
        ..writeln(
          '  return ${_conversionExpressionForType(keyType, keyFieldType, 'value', nullExpression: _nullExpression(keyType, errorTarget: '${field.name} map key'))};',
        )
        ..writeln('}')
        ..writeln()
        ..writeln(
          '${_typeCodeString(valueType)} $valueFunctionName(Object? value) {',
        )
        ..writeln(
          '  return ${_conversionExpressionForType(valueType, valueFieldType, 'value', nullExpression: _nullExpression(valueType, errorTarget: '${field.name} map value'))};',
        )
        ..writeln('}')
        ..writeln();
      return;
    }
    throw StateError(
      'Unsupported generated direct container reader helpers for ${field.name}.',
    );
  }

  String _containerElementReaderFunctionName(
    String structName,
    _GeneratedFieldSpec field,
  ) {
    final fieldName =
        '${field.name[0].toUpperCase()}${field.name.substring(1)}';
    return '_read$structName${fieldName}Element';
  }

  String _containerKeyReaderFunctionName(
    String structName,
    _GeneratedFieldSpec field,
  ) {
    final fieldName =
        '${field.name[0].toUpperCase()}${field.name.substring(1)}';
    return '_read$structName${fieldName}Key';
  }

  String _containerValueReaderFunctionName(
    String structName,
    _GeneratedFieldSpec field,
  ) {
    final fieldName =
        '${field.name[0].toUpperCase()}${field.name.substring(1)}';
    return '_read$structName${fieldName}Value';
  }

  List<_GeneratedFieldSpec> _sortFields(List<_GeneratedFieldSpec> fields) {
    final primitiveFields = <_GeneratedFieldSpec>[];
    final boxedPrimitiveFields = <_GeneratedFieldSpec>[];
    final builtInFields = <_GeneratedFieldSpec>[];
    final collectionFields = <_GeneratedFieldSpec>[];
    final mapFields = <_GeneratedFieldSpec>[];
    final otherFields = <_GeneratedFieldSpec>[];

    for (final field in fields) {
      if (_isPrimitiveTypeId(field.fieldType.typeId)) {
        if (field.nullable) {
          boxedPrimitiveFields.add(field);
        } else {
          primitiveFields.add(field);
        }
      } else if (field.fieldType.typeId == TypeIds.list ||
          field.fieldType.typeId == TypeIds.set) {
        collectionFields.add(field);
      } else if (field.fieldType.typeId == TypeIds.map) {
        mapFields.add(field);
      } else if (_isBuiltInTypeId(field.fieldType.typeId)) {
        builtInFields.add(field);
      } else {
        otherFields.add(field);
      }
    }

    primitiveFields.sort(_comparePrimitiveFields);
    boxedPrimitiveFields.sort(_comparePrimitiveFields);
    builtInFields.sort(_compareNonPrimitiveFields);
    collectionFields.sort(_compareNonPrimitiveFields);
    mapFields.sort(_compareNonPrimitiveFields);
    otherFields.sort(_compareOtherFields);

    return <_GeneratedFieldSpec>[
      ...primitiveFields,
      ...boxedPrimitiveFields,
      ...builtInFields,
      ...collectionFields,
      ...mapFields,
      ...otherFields,
    ];
  }

  int _comparePrimitiveFields(
    _GeneratedFieldSpec left,
    _GeneratedFieldSpec right,
  ) {
    final leftCompressed = _isCompressedTypeId(left.fieldType.typeId);
    final rightCompressed = _isCompressedTypeId(right.fieldType.typeId);
    if (leftCompressed != rightCompressed) {
      return leftCompressed ? 1 : -1;
    }
    final sizeCompare = _primitiveSize(right.fieldType.typeId) -
        _primitiveSize(left.fieldType.typeId);
    if (sizeCompare != 0) {
      return sizeCompare;
    }
    final typeCompare = left.fieldType.typeId - right.fieldType.typeId;
    if (typeCompare != 0) {
      return typeCompare;
    }
    final keyCompare = _compareFieldIdentity(left, right);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return left.name.compareTo(right.name);
  }

  int _compareNonPrimitiveFields(
    _GeneratedFieldSpec left,
    _GeneratedFieldSpec right,
  ) {
    final typeCompare = left.fieldType.typeId - right.fieldType.typeId;
    if (typeCompare != 0) {
      return typeCompare;
    }
    final keyCompare = _compareFieldIdentity(left, right);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return left.name.compareTo(right.name);
  }

  int _compareOtherFields(_GeneratedFieldSpec left, _GeneratedFieldSpec right) {
    final keyCompare = _compareFieldIdentity(left, right);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return left.name.compareTo(right.name);
  }

  int _compareFieldIdentity(
    _GeneratedFieldSpec left,
    _GeneratedFieldSpec right,
  ) {
    final leftId = left.id;
    final rightId = right.id;
    if (leftId != null && leftId >= 0 && rightId != null && rightId >= 0) {
      final idCompare = leftId.compareTo(rightId);
      if (idCompare != 0) {
        return idCompare;
      }
    }
    final keyCompare = left.identifier.compareTo(right.identifier);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return 0;
  }

  int _primitiveSize(int typeId) {
    switch (typeId) {
      case TypeIds.boolType:
      case TypeIds.int8:
      case TypeIds.uint8:
        return 1;
      case TypeIds.int16:
      case TypeIds.uint16:
      case TypeIds.float16:
      case TypeIds.bfloat16:
        return 2;
      case TypeIds.int32:
      case TypeIds.varInt32:
      case TypeIds.uint32:
      case TypeIds.varUint32:
      case TypeIds.float32:
        return 4;
      case TypeIds.int64:
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.uint64:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
      case TypeIds.float64:
        return 8;
      default:
        return 0;
    }
  }

  bool _isCompressedTypeId(int typeId) {
    switch (typeId) {
      case TypeIds.varInt32:
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.varUint32:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
        return true;
      default:
        return false;
    }
  }

  bool _isPrimitiveTypeId(int typeId) {
    switch (typeId) {
      case TypeIds.boolType:
      case TypeIds.int8:
      case TypeIds.int16:
      case TypeIds.int32:
      case TypeIds.varInt32:
      case TypeIds.varInt64:
      case TypeIds.taggedInt64:
      case TypeIds.int64:
      case TypeIds.uint8:
      case TypeIds.uint16:
      case TypeIds.uint32:
      case TypeIds.varUint32:
      case TypeIds.uint64:
      case TypeIds.varUint64:
      case TypeIds.taggedUint64:
      case TypeIds.float16:
      case TypeIds.bfloat16:
      case TypeIds.float32:
      case TypeIds.float64:
        return true;
      default:
        return false;
    }
  }

  bool _isBuiltInTypeId(int typeId) {
    switch (typeId) {
      case TypeIds.string:
      case TypeIds.binary:
      case TypeIds.decimal:
      case TypeIds.date:
      case TypeIds.duration:
      case TypeIds.timestamp:
      case TypeIds.boolArray:
      case TypeIds.int8Array:
      case TypeIds.int16Array:
      case TypeIds.int32Array:
      case TypeIds.int64Array:
      case TypeIds.uint8Array:
      case TypeIds.uint16Array:
      case TypeIds.uint32Array:
      case TypeIds.uint64Array:
      case TypeIds.float16Array:
      case TypeIds.bfloat16Array:
      case TypeIds.float32Array:
      case TypeIds.float64Array:
        return true;
      default:
        return false;
    }
  }

  DartObject? _fieldAnnotationOf(FieldElement field) {
    for (final metadata in field.metadata) {
      final annotation = metadata.computeConstantValue();
      final annotationType = annotation?.type;
      if (annotationType != null &&
          _typeSpecChecker.isAssignableFromType(annotationType)) {
        throw InvalidGenerationSourceError(
          'Standalone type-spec annotations like @${annotationType.element?.displayName ?? 'TypeSpec'}() '
          'are not supported. Use @ForyField(type: ...) or container field sugar instead.',
          element: field,
        );
      }
    }
    final annotations = <DartObject?>[
      _foryFieldChecker.firstAnnotationOf(field),
      _listFieldChecker.firstAnnotationOf(field),
      _arrayFieldChecker.firstAnnotationOf(field),
      _setFieldChecker.firstAnnotationOf(field),
      _mapFieldChecker.firstAnnotationOf(field),
    ].whereType<DartObject>().toList(growable: false);
    if (annotations.length > 1) {
      throw InvalidGenerationSourceError(
        'Use only one of @ForyField, @ListField, @ArrayField, @SetField, or @MapField on a field.',
        element: field,
      );
    }
    return annotations.isEmpty ? null : annotations.single;
  }

  _TypeSpecInfo? _analyzeTypeSpecAnnotation(
    FieldElement field,
    ConstantReader? reader,
  ) {
    final annotation = _fieldAnnotationOf(field);
    if (annotation == null || reader == null) {
      return null;
    }
    final annotationType = annotation.type;
    if (annotationType != null &&
        _foryFieldChecker.isExactlyType(annotationType)) {
      final typeReader = reader.peek('type');
      if (typeReader == null || typeReader.isNull) {
        return null;
      }
      final typeSpec = _readTypeSpecObj(typeReader, field);
      _validateRootTypeSpecConflicts(field, reader, typeSpec);
      return typeSpec;
    }
    if (annotationType != null &&
        _listFieldChecker.isExactlyType(annotationType)) {
      return _TypeSpecInfo(
        typeId: TypeIds.list,
        element: _readOptionalTypeSpec(reader.peek('element'), field),
      );
    }
    if (annotationType != null &&
        _arrayFieldChecker.isExactlyType(annotationType)) {
      final element = _readRequiredTypeSpec(reader.peek('element'), field);
      return _TypeSpecInfo(
        typeId: _arrayTypeIdForElementSpec(element, field),
      );
    }
    if (annotationType != null &&
        _setFieldChecker.isExactlyType(annotationType)) {
      return _TypeSpecInfo(
        typeId: TypeIds.set,
        element: _readOptionalTypeSpec(reader.peek('element'), field),
      );
    }
    if (annotationType != null &&
        _mapFieldChecker.isExactlyType(annotationType)) {
      return _TypeSpecInfo(
        typeId: TypeIds.map,
        key: _readOptionalTypeSpec(reader.peek('key'), field),
        value: _readOptionalTypeSpec(reader.peek('value'), field),
      );
    }
    return null;
  }

  _TypeSpecInfo? _readOptionalTypeSpec(ConstantReader? reader, Element field) {
    if (reader == null || reader.isNull) {
      return null;
    }
    return _readTypeSpecObj(reader, field);
  }

  _TypeSpecInfo _readRequiredTypeSpec(ConstantReader? reader, Element field) {
    if (reader == null || reader.isNull) {
      throw InvalidGenerationSourceError(
        'ArrayType requires an element type spec.',
        element: field,
      );
    }
    return _readTypeSpecObj(reader, field);
  }

  _TypeSpecInfo _readTypeSpecObj(ConstantReader reader, Element field) {
    final objType = reader.objectValue.type;
    final typeName = objType?.element?.displayName;
    final nullable = _readBoolOverride(reader.peek('nullable'));
    final ref = _readBoolOverride(reader.peek('ref'));
    final dynamic = _readBoolOverride(reader.peek('dynamic'));
    switch (typeName) {
      case 'DeclaredType':
        return _TypeSpecInfo(
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'ListType':
        return _TypeSpecInfo(
          typeId: TypeIds.list,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          element: _readOptionalTypeSpec(reader.peek('element'), field),
        );
      case 'ArrayType':
        final element = _readRequiredTypeSpec(reader.peek('element'), field);
        return _TypeSpecInfo(
          typeId: _arrayTypeIdForElementSpec(element, field),
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'SetType':
        return _TypeSpecInfo(
          typeId: TypeIds.set,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          element: _readOptionalTypeSpec(reader.peek('element'), field),
        );
      case 'MapType':
        return _TypeSpecInfo(
          typeId: TypeIds.map,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          key: _readOptionalTypeSpec(reader.peek('key'), field),
          value: _readOptionalTypeSpec(reader.peek('value'), field),
        );
      case 'BoolType':
        return _TypeSpecInfo(
          typeId: TypeIds.boolType,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Int8Type':
        return _TypeSpecInfo(
          typeId: TypeIds.int8,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Int16Type':
        return _TypeSpecInfo(
          typeId: TypeIds.int16,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Int32Type':
        return _TypeSpecInfo(
          typeId: _encodingTypeId(
            reader,
            fixed: TypeIds.int32,
            varint: TypeIds.varInt32,
            field: field,
          ),
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          hasExplicitScalarEncoding: _hasExplicitScalarEncoding(reader),
        );
      case 'Int64Type':
        return _TypeSpecInfo(
          typeId: _encodingTypeId(
            reader,
            fixed: TypeIds.int64,
            varint: TypeIds.varInt64,
            tagged: TypeIds.taggedInt64,
            field: field,
          ),
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          hasExplicitScalarEncoding: _hasExplicitScalarEncoding(reader),
        );
      case 'Uint8Type':
        return _TypeSpecInfo(
          typeId: TypeIds.uint8,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Uint16Type':
        return _TypeSpecInfo(
          typeId: TypeIds.uint16,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Uint32Type':
        return _TypeSpecInfo(
          typeId: _encodingTypeId(
            reader,
            fixed: TypeIds.uint32,
            varint: TypeIds.varUint32,
            field: field,
          ),
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          hasExplicitScalarEncoding: _hasExplicitScalarEncoding(reader),
        );
      case 'Uint64Type':
        return _TypeSpecInfo(
          typeId: _encodingTypeId(
            reader,
            fixed: TypeIds.uint64,
            varint: TypeIds.varUint64,
            tagged: TypeIds.taggedUint64,
            field: field,
          ),
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
          hasExplicitScalarEncoding: _hasExplicitScalarEncoding(reader),
        );
      case 'Float16Type':
        return _TypeSpecInfo(
          typeId: TypeIds.float16,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Bfloat16Type':
        return _TypeSpecInfo(
          typeId: TypeIds.bfloat16,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Float32Type':
        return _TypeSpecInfo(
          typeId: TypeIds.float32,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'Float64Type':
        return _TypeSpecInfo(
          typeId: TypeIds.float64,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'StringType':
        return _TypeSpecInfo(
          typeId: TypeIds.string,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'DecimalType':
        return _TypeSpecInfo(
          typeId: TypeIds.decimal,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'TimestampType':
        return _TypeSpecInfo(
          typeId: TypeIds.timestamp,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'DateType':
        return _TypeSpecInfo(
          typeId: TypeIds.date,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'DurationType':
        return _TypeSpecInfo(
          typeId: TypeIds.duration,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      case 'BinaryType':
        return _TypeSpecInfo(
          typeId: TypeIds.binary,
          nullable: nullable,
          ref: ref,
          dynamic: dynamic,
        );
      default:
        throw InvalidGenerationSourceError(
          'Unsupported type spec ${typeName ?? reader.objectValue.toString()}.',
          element: field,
        );
    }
  }

  bool? _readBoolOverride(ConstantReader? reader) {
    if (reader == null || reader.isNull) {
      return null;
    }
    return reader.boolValue;
  }

  bool _hasExplicitScalarEncoding(ConstantReader reader) {
    final encodingReader = reader.peek('encoding');
    return encodingReader != null && !encodingReader.isNull;
  }

  int _encodingTypeId(
    ConstantReader reader, {
    required int fixed,
    required int varint,
    int? tagged,
    required Element field,
  }) {
    final encodingReader = reader.peek('encoding');
    final encodingValue = encodingReader == null || encodingReader.isNull
        ? 'varint'
        : encodingReader.revive().accessor.split('.').last;
    return switch (encodingValue) {
      'fixed' => fixed,
      'varint' => varint,
      'tagged' when tagged != null => tagged,
      _ => throw InvalidGenerationSourceError(
          'Unsupported encoding $encodingValue for type spec.',
          element: field,
        ),
    };
  }

  void _validateRootTypeSpecConflicts(
    FieldElement field,
    ConstantReader reader,
    _TypeSpecInfo typeSpec,
  ) {
    final fieldNullable = reader.peek('nullable');
    if (fieldNullable != null &&
        !fieldNullable.isNull &&
        typeSpec.nullable != null &&
        fieldNullable.boolValue != typeSpec.nullable) {
      throw InvalidGenerationSourceError(
        'Field nullable conflicts with root type nullable override.',
        element: field,
      );
    }
    final fieldRef = reader.peek('ref');
    if (fieldRef != null &&
        !fieldRef.isNull &&
        typeSpec.ref != null &&
        fieldRef.boolValue != typeSpec.ref) {
      throw InvalidGenerationSourceError(
        'Field ref conflicts with root type ref override.',
        element: field,
      );
    }
    final fieldDynamic = reader.peek('dynamic');
    if (fieldDynamic != null &&
        !fieldDynamic.isNull &&
        typeSpec.dynamic != null &&
        fieldDynamic.boolValue != typeSpec.dynamic) {
      throw InvalidGenerationSourceError(
        'Field dynamic conflicts with root type dynamic override.',
        element: field,
      );
    }
  }

  bool _isDenseArrayTypeId(int typeId) =>
      typeId >= TypeIds.boolArray && typeId <= TypeIds.float64Array;

  int _arrayTypeIdForElementSpec(_TypeSpecInfo element, Element field) {
    if (element.typeId == null) {
      throw InvalidGenerationSourceError(
        'ArrayType requires a concrete numeric or bool scalar element type.',
        element: field,
      );
    }
    if (element.hasExplicitScalarEncoding ||
        element.nullable == true ||
        element.ref == true ||
        element.dynamic == true) {
      throw InvalidGenerationSourceError(
        'ArrayType elements cannot use scalar encoding modifiers, nullable, ref-tracked, or dynamic.',
        element: field,
      );
    }
    return switch (element.typeId!) {
      TypeIds.boolType => TypeIds.boolArray,
      TypeIds.int8 => TypeIds.int8Array,
      TypeIds.int16 => TypeIds.int16Array,
      TypeIds.varInt32 => TypeIds.int32Array,
      TypeIds.varInt64 => TypeIds.int64Array,
      TypeIds.uint8 => TypeIds.uint8Array,
      TypeIds.uint16 => TypeIds.uint16Array,
      TypeIds.varUint32 => TypeIds.uint32Array,
      TypeIds.varUint64 => TypeIds.uint64Array,
      TypeIds.float16 => TypeIds.float16Array,
      TypeIds.bfloat16 => TypeIds.bfloat16Array,
      TypeIds.float32 => TypeIds.float32Array,
      TypeIds.float64 => TypeIds.float64Array,
      _ => throw InvalidGenerationSourceError(
          'ArrayType requires a numeric or bool scalar element type without fixed/tagged encoding.',
          element: field,
        ),
    };
  }

  Never _fieldTypeForArrayListCarrier(Element errorElement) {
    throw InvalidGenerationSourceError(
      'ArrayType(BoolType) requires a BoolList carrier. List<bool> maps to list<bool>.',
      element: errorElement,
    );
  }

  void _validateScalarTypeOverride(
    DartType type,
    int typeId,
    bool? dynamic,
    _TypeSpecInfo? typeSpec,
    Element errorElement,
  ) {
    final declaredOnlyOverride = typeSpec != null && typeSpec.typeId == null;
    if (dynamic == true &&
        (TypeIds.isPrimitive(typeId) || _isBuiltInTypeId(typeId))) {
      throw InvalidGenerationSourceError(
        'dynamic: true is not valid for fixed scalar or built-in leaf type ${_typeSpecName(typeId)}.',
        element: errorElement,
      );
    }
    if (typeId == TypeIds.list ||
        typeId == TypeIds.set ||
        typeId == TypeIds.map) {
      throw InvalidGenerationSourceError(
        'Type override ${_typeSpecName(typeId)} does not match the declared ${_typeCodeString(type)} carrier.',
        element: errorElement,
      );
    }
    final nonNullable = _withoutNullability(type);
    final valid = switch (_typeLiteral(nonNullable)) {
      'bool' => typeId == TypeIds.boolType,
      'int' => _isSupportedIntTypeId(typeId),
      'double' => typeId == TypeIds.float32 || typeId == TypeIds.float64,
      'String' => typeId == TypeIds.string,
      'Int64' => _isSigned64TypeId(typeId),
      'Uint64' => _isUnsigned64TypeId(typeId),
      'Float16' => typeId == TypeIds.float16,
      'Bfloat16' => typeId == TypeIds.bfloat16,
      'Float32' => typeId == TypeIds.float32,
      'Decimal' => typeId == TypeIds.decimal,
      'Timestamp' || 'DateTime' => typeId == TypeIds.timestamp,
      'LocalDate' => typeId == TypeIds.date,
      'Duration' => typeId == TypeIds.duration,
      'BoolList' => typeId == TypeIds.boolArray || typeId == TypeIds.list,
      'Uint8List' => typeId == TypeIds.binary || typeId == TypeIds.uint8Array,
      'Int8List' => typeId == TypeIds.int8Array,
      'Int16List' => typeId == TypeIds.int16Array,
      'Int32List' => typeId == TypeIds.int32Array,
      'Int64List' => typeId == TypeIds.int64Array,
      'Uint16List' => typeId == TypeIds.uint16Array,
      'Uint32List' => typeId == TypeIds.uint32Array,
      'Uint64List' => typeId == TypeIds.uint64Array,
      'Float16List' => typeId == TypeIds.float16Array,
      'Bfloat16List' => typeId == TypeIds.bfloat16Array,
      'Float32List' => typeId == TypeIds.float32Array,
      'Float64List' => typeId == TypeIds.float64Array,
      _ => typeSpec == null || declaredOnlyOverride,
    };
    if (!valid) {
      throw InvalidGenerationSourceError(
        'Type override ${_typeSpecName(typeId)} is not valid for declared Dart type ${_typeCodeString(type)}.',
        element: errorElement,
      );
    }
  }

  bool _isSupportedIntTypeId(int typeId) =>
      typeId == TypeIds.int8 ||
      typeId == TypeIds.int16 ||
      typeId == TypeIds.int32 ||
      typeId == TypeIds.varInt32 ||
      _isSigned64TypeId(typeId) ||
      typeId == TypeIds.uint8 ||
      typeId == TypeIds.uint16 ||
      typeId == TypeIds.uint32 ||
      typeId == TypeIds.varUint32 ||
      _isUnsigned64TypeId(typeId);

  bool _isSigned64TypeId(int typeId) =>
      typeId == TypeIds.int64 ||
      typeId == TypeIds.varInt64 ||
      typeId == TypeIds.taggedInt64;

  bool _isUnsigned64TypeId(int typeId) =>
      typeId == TypeIds.uint64 ||
      typeId == TypeIds.varUint64 ||
      typeId == TypeIds.taggedUint64;

  String _checkedGeneratedScalarExpression(int typeId, String valueExpression) {
    switch (typeId) {
      case TypeIds.int8:
        return 'generatedCheckedInt8($valueExpression)';
      case TypeIds.int16:
        return 'generatedCheckedInt16($valueExpression)';
      case TypeIds.int32:
      case TypeIds.varInt32:
        return 'generatedCheckedInt32($valueExpression)';
      case TypeIds.uint8:
        return 'generatedCheckedUint8($valueExpression)';
      case TypeIds.uint16:
        return 'generatedCheckedUint16($valueExpression)';
      case TypeIds.uint32:
      case TypeIds.varUint32:
        return 'generatedCheckedUint32($valueExpression)';
      default:
        return valueExpression;
    }
  }

  String _typeSpecName(int typeId) {
    switch (typeId) {
      case TypeIds.boolType:
        return 'BoolType';
      case TypeIds.int8:
        return 'Int8Type';
      case TypeIds.int16:
        return 'Int16Type';
      case TypeIds.int32:
        return 'Int32Type(encoding: Encoding.fixed)';
      case TypeIds.varInt32:
        return 'Int32Type(encoding: Encoding.varint)';
      case TypeIds.int64:
        return 'Int64Type(encoding: Encoding.fixed)';
      case TypeIds.varInt64:
        return 'Int64Type(encoding: Encoding.varint)';
      case TypeIds.taggedInt64:
        return 'Int64Type(encoding: Encoding.tagged)';
      case TypeIds.uint8:
        return 'Uint8Type';
      case TypeIds.uint16:
        return 'Uint16Type';
      case TypeIds.uint32:
        return 'Uint32Type(encoding: Encoding.fixed)';
      case TypeIds.varUint32:
        return 'Uint32Type(encoding: Encoding.varint)';
      case TypeIds.uint64:
        return 'Uint64Type(encoding: Encoding.fixed)';
      case TypeIds.varUint64:
        return 'Uint64Type(encoding: Encoding.varint)';
      case TypeIds.taggedUint64:
        return 'Uint64Type(encoding: Encoding.tagged)';
      case TypeIds.float16:
        return 'Float16Type';
      case TypeIds.bfloat16:
        return 'Bfloat16Type';
      case TypeIds.float32:
        return 'Float32Type';
      case TypeIds.float64:
        return 'Float64Type';
      case TypeIds.string:
        return 'StringType';
      case TypeIds.binary:
        return 'BinaryType';
      case TypeIds.decimal:
        return 'DecimalType';
      case TypeIds.timestamp:
        return 'TimestampType';
      case TypeIds.date:
        return 'DateType';
      case TypeIds.duration:
        return 'DurationType';
      case TypeIds.list:
        return 'ListType';
      case TypeIds.set:
        return 'SetType';
      case TypeIds.map:
        return 'MapType';
      case TypeIds.boolArray:
        return 'ArrayType(element: BoolType())';
      case TypeIds.int8Array:
        return 'ArrayType(element: Int8Type())';
      case TypeIds.int16Array:
        return 'ArrayType(element: Int16Type())';
      case TypeIds.int32Array:
        return 'ArrayType(element: Int32Type())';
      case TypeIds.int64Array:
        return 'ArrayType(element: Int64Type())';
      case TypeIds.uint8Array:
        return 'ArrayType(element: Uint8Type())';
      case TypeIds.uint16Array:
        return 'ArrayType(element: Uint16Type())';
      case TypeIds.uint32Array:
        return 'ArrayType(element: Uint32Type())';
      case TypeIds.uint64Array:
        return 'ArrayType(element: Uint64Type())';
      case TypeIds.float16Array:
        return 'ArrayType(element: Float16Type())';
      case TypeIds.bfloat16Array:
        return 'ArrayType(element: Bfloat16Type())';
      case TypeIds.float32Array:
        return 'ArrayType(element: Float32Type())';
      case TypeIds.float64Array:
        return 'ArrayType(element: Float64Type())';
      default:
        return 'type id $typeId';
    }
  }

  int _typeIdFor(DartType type) {
    final nonNullable = _withoutNullability(type);
    if (nonNullable.isDartCoreBool) {
      return TypeIds.boolType;
    }
    if (nonNullable.isDartCoreInt) {
      return TypeIds.varInt64;
    }
    if (nonNullable.isDartCoreDouble) {
      return TypeIds.float64;
    }
    if (nonNullable.isDartCoreString) {
      return TypeIds.string;
    }
    final display = nonNullable.getDisplayString().replaceAll('?', '');
    switch (display) {
      case 'BoolList':
        return TypeIds.list;
      case 'Uint8List':
        return TypeIds.binary;
      case 'Int8List':
        return TypeIds.int8Array;
      case 'Int16List':
        return TypeIds.int16Array;
      case 'Int32List':
        return TypeIds.int32Array;
      case 'Int64List':
        return TypeIds.int64Array;
      case 'Uint16List':
        return TypeIds.uint16Array;
      case 'Uint32List':
        return TypeIds.uint32Array;
      case 'Uint64List':
        return TypeIds.uint64Array;
      case 'Float16List':
        return TypeIds.float16Array;
      case 'Bfloat16List':
        return TypeIds.bfloat16Array;
      case 'Float32List':
        return TypeIds.float32Array;
      case 'Float64List':
        return TypeIds.float64Array;
    }
    if (_isList(nonNullable)) {
      return TypeIds.list;
    }
    if (_isSet(nonNullable)) {
      return TypeIds.set;
    }
    if (_isMap(nonNullable)) {
      return TypeIds.map;
    }
    final typeLiteral = _typeLiteral(nonNullable);
    switch (typeLiteral) {
      case 'Uint64':
        return TypeIds.varUint64;
      case 'Int64':
        return TypeIds.varInt64;
      case 'Float16':
        return TypeIds.float16;
      case 'Bfloat16':
        return TypeIds.bfloat16;
      case 'Float32':
        return TypeIds.float32;
      case 'Decimal':
        return TypeIds.decimal;
      case 'Timestamp':
      case 'DateTime':
        return TypeIds.timestamp;
      case 'LocalDate':
        return TypeIds.date;
      case 'Duration':
        return TypeIds.duration;
      case 'Object':
        return TypeIds.unknown;
      default:
        if (nonNullable.element is EnumElement) {
          return TypeIds.enumById;
        }
        final element = nonNullable.element;
        if (element is ClassElement &&
            _foryUnionChecker.hasAnnotationOf(element)) {
          return TypeIds.typedUnion;
        }
        return TypeIds.compatibleStruct;
    }
  }

  bool _enumUsesRawValue(DartType type) {
    final element = _withoutNullability(type).element;
    if (element is! EnumElement) {
      return false;
    }
    return _enumUsesRawValueElement(element);
  }

  bool _enumUsesRawValueElement(EnumElement element) {
    final getter = element.getGetter('rawValue');
    if (getter == null || getter.isStatic || !getter.returnType.isDartCoreInt) {
      return false;
    }
    final method = element.getMethod('fromRawValue');
    if (method == null ||
        !method.isStatic ||
        method.parameters.length != 1 ||
        !method.parameters.single.type.isDartCoreInt) {
      return false;
    }
    return method.returnType.element == element;
  }

  String _enumWriteExpression(DartType type, String valueExpression) {
    if (_enumUsesRawValue(type)) {
      return 'buffer.writeVarUint32($valueExpression.rawValue)';
    }
    return 'buffer.writeVarUint32($valueExpression.index)';
  }

  String _enumCursorWriteExpression(
    DartType type,
    String cursorExpression,
    String valueExpression,
  ) {
    if (_enumUsesRawValue(type)) {
      return '$cursorExpression.writeVarUint32($valueExpression.rawValue)';
    }
    return '$cursorExpression.writeVarUint32($valueExpression.index)';
  }

  String _enumReadExpression(DartType type, String contextExpression) {
    final typeDisplay = _typeReferenceLiteral(type);
    if (_enumUsesRawValue(type)) {
      return '$typeDisplay.fromRawValue($contextExpression.readVarUint32())';
    }
    return '$typeDisplay.values[$contextExpression.readVarUint32()]';
  }

  String _enumCursorReadExpression(DartType type, String cursorExpression) {
    final typeDisplay = _typeReferenceLiteral(type);
    if (_enumUsesRawValue(type)) {
      return '$typeDisplay.fromRawValue($cursorExpression.readVarUint32())';
    }
    return '$typeDisplay.values[$cursorExpression.readVarUint32()]';
  }

  bool _sameType(DartType left, DartType right) =>
      _typeLiteral(_withoutNullability(left)) ==
      _typeLiteral(_withoutNullability(right));

  bool? _autoDynamic(DartType type) {
    final nonNullable = _withoutNullability(type);
    if (nonNullable is DynamicType || nonNullable is InvalidType) {
      return true;
    }
    if (nonNullable.isDartCoreObject) {
      return true;
    }
    if (_isList(nonNullable) || _isSet(nonNullable) || _isMap(nonNullable)) {
      return null;
    }
    final typeId = _typeIdFor(nonNullable);
    if (_isPrimitiveTypeId(typeId) ||
        _isBuiltInTypeId(typeId) ||
        typeId == TypeIds.enumById) {
      return null;
    }
    final element = nonNullable.element;
    if (element is ClassElement && element.isAbstract) {
      return true;
    }
    return null;
  }

  DartType _withoutNullability(DartType type) {
    if (type.nullabilitySuffix != NullabilitySuffix.question) {
      return type;
    }
    if (type is InterfaceType) {
      return type.element.instantiate(
        typeArguments: type.typeArguments,
        nullabilitySuffix: NullabilitySuffix.none,
      );
    }
    if (type is TypeParameterType) {
      return type.element.instantiate(
        nullabilitySuffix: NullabilitySuffix.none,
      );
    }
    return type;
  }

  bool _isNullable(DartType type) =>
      type.nullabilitySuffix == NullabilitySuffix.question;

  bool _isDateTimeType(DartType type) {
    final nonNullable = _withoutNullability(type);
    return nonNullable is InterfaceType &&
        nonNullable.element.name == 'DateTime' &&
        nonNullable.element.library.isDartCore;
  }

  bool _isList(DartType type) => type.isDartCoreList;

  bool _isBoolList(DartType type) =>
      _typeLiteral(_withoutNullability(type)) == 'BoolList';

  bool _isSet(DartType type) =>
      type is InterfaceType && type.element.name == 'Set';

  bool _isMap(DartType type) => type.isDartCoreMap;

  String _typeLiteral(DartType type) {
    if (type is DynamicType || type is InvalidType) {
      return 'Object';
    }
    if (type is InterfaceType) {
      return type.element.displayName;
    }
    return type.getDisplayString().replaceAll('?', '');
  }

  String _typeReferenceLiteral(DartType type) {
    final nonNullable = _withoutNullability(type);
    if (nonNullable is DynamicType || nonNullable is InvalidType) {
      return 'Object';
    }
    if (nonNullable is InterfaceType) {
      final element = nonNullable.element;
      final prefix =
          _importPrefixByLibraryIdentifier[element.library.identifier];
      final elementName = element.displayName;
      final baseName = prefix == null ? elementName : '$prefix.$elementName';
      if (nonNullable.typeArguments.isEmpty) {
        return baseName;
      }
      final typeArguments =
          nonNullable.typeArguments.map(_typeCodeString).join(', ');
      return '$baseName<$typeArguments>';
    }
    return nonNullable.getDisplayString();
  }

  String _typeCodeString(DartType type) {
    final base = _typeReferenceLiteral(type);
    return _isNullable(type) ? '$base?' : base;
  }

  String _toPascalCase(String value) => value
      .split(RegExp(r'[_\-\s]+'))
      .where((part) => part.isNotEmpty)
      .map((part) => '${part[0].toUpperCase()}${part.substring(1)}')
      .join();

  String _toCamelCase(String value) {
    final pascal = _toPascalCase(value);
    if (pascal.isEmpty) {
      return pascal;
    }
    return '${pascal[0].toLowerCase()}${pascal.substring(1)}';
  }

  String _toSnakeCase(String value) {
    final buffer = StringBuffer();
    for (var index = 0; index < value.length; index += 1) {
      final codeUnit = value.codeUnitAt(index);
      final isUpper = codeUnit >= 65 && codeUnit <= 90;
      if (isUpper && index > 0) {
        buffer.write('_');
      }
      buffer.write(String.fromCharCode(isUpper ? codeUnit + 32 : codeUnit));
    }
    return buffer.toString();
  }
}

final class _GeneratedEnumSpec {
  final String name;
  final bool usesRawValue;

  const _GeneratedEnumSpec({required this.name, required this.usesRawValue});
}

final class _GeneratedStructSpec {
  final String name;
  final bool evolving;
  final List<_GeneratedFieldSpec> fields;
  final _ConstructorPlan constructorPlan;

  const _GeneratedStructSpec({
    required this.name,
    required this.evolving,
    required this.fields,
    required this.constructorPlan,
  });
}

final class _GeneratedFieldSpec {
  final String name;
  final DartType type;
  final String displayType;
  final String identifier;
  final int? id;
  final bool nullable;
  final bool ref;
  final bool? dynamic;
  final bool writable;
  final _GeneratedFieldTypeSpec fieldType;

  const _GeneratedFieldSpec({
    required this.name,
    required this.type,
    required this.displayType,
    required this.identifier,
    required this.id,
    required this.nullable,
    required this.ref,
    required this.dynamic,
    required this.writable,
    required this.fieldType,
  });
  String readerFunctionName(String structName) {
    final fieldName = '${name[0].toUpperCase()}${name.substring(1)}';
    return '_read$structName$fieldName';
  }

  String get localName => '_${name}Value';
}

final class _DirectGeneratedWriteReservationRun {
  final int start;
  final int end;
  final int bytes;

  const _DirectGeneratedWriteReservationRun(this.start, this.end, this.bytes);
}

final class _GeneratedFieldTypeSpec {
  final String typeLiteral;
  final String? declaredTypeName;
  final int typeId;
  final bool nullable;
  final bool ref;
  final bool? dynamic;
  final List<_GeneratedFieldTypeSpec> arguments;

  const _GeneratedFieldTypeSpec({
    required this.typeLiteral,
    this.declaredTypeName,
    required this.typeId,
    required this.nullable,
    required this.ref,
    required this.dynamic,
    required this.arguments,
  });
}

enum _ConstructorMode { mutable, constructor }

final class _ConstructorPlan {
  final _ConstructorMode mode;
  final List<_ConstructorArgumentSpec> arguments;
  final List<String> postConstructionFieldNames;

  const _ConstructorPlan.mutable()
      : mode = _ConstructorMode.mutable,
        arguments = const <_ConstructorArgumentSpec>[],
        postConstructionFieldNames = const <String>[];

  const _ConstructorPlan.constructor({
    required this.arguments,
    required this.postConstructionFieldNames,
  }) : mode = _ConstructorMode.constructor;
}

final class _ConstructorArgumentSpec {
  final String fieldName;
  final String parameterName;
  final bool named;

  const _ConstructorArgumentSpec({
    required this.fieldName,
    required this.parameterName,
    required this.named,
  });
}

class _TypeSpecInfo {
  final int? typeId;
  final bool? nullable;
  final bool? ref;
  final bool? dynamic;
  final _TypeSpecInfo? element;
  final _TypeSpecInfo? key;
  final _TypeSpecInfo? value;
  final bool hasExplicitScalarEncoding;

  const _TypeSpecInfo({
    this.typeId,
    this.nullable,
    this.ref,
    this.dynamic,
    this.element,
    this.key,
    this.value,
    this.hasExplicitScalarEncoding = false,
  });
}
