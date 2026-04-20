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
    this.arguments = const <DebugGeneratedFieldTypeSpec>[],
  });

  final String typeLiteral;
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
  static final TypeChecker _foryUnionChecker = TypeChecker.fromRuntime(
    ForyUnion,
  );
  static final TypeChecker _listTypeChecker = TypeChecker.fromRuntime(ListType);
  static final TypeChecker _mapTypeChecker = TypeChecker.fromRuntime(MapType);
  static final TypeChecker _refOptionChecker = TypeChecker.fromRuntime(
    RefOption,
  );
  static final TypeChecker _nullableOptionChecker = TypeChecker.fromRuntime(
    NullableOption,
  );
  static final TypeChecker _int32Checker = TypeChecker.fromRuntime(Int32Type);
  static final TypeChecker _int64Checker = TypeChecker.fromRuntime(Int64Type);
  static final TypeChecker _uint8Checker = TypeChecker.fromRuntime(Uint8Type);
  static final TypeChecker _uint16Checker = TypeChecker.fromRuntime(Uint16Type);
  static final TypeChecker _uint32Checker = TypeChecker.fromRuntime(Uint32Type);
  static final TypeChecker _uint64Checker = TypeChecker.fromRuntime(Uint64Type);

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
    final structSpecs = annotatedClasses
        .map(_analyzeStruct)
        .toList(growable: false);
    final output = StringBuffer()
      ..writeln(
        '// ignore_for_file: implementation_imports, invalid_use_of_internal_member, unused_element, unnecessary_null_comparison',
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
    final annotation = _foryFieldChecker.firstAnnotationOf(field);
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

    // Read container TypeSpec annotations (@ListType / @MapType).
    final typeSpec = _analyzeTypeSpecAnnotation(field);

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
        integerAnnotation: _analyzeIntegerAnnotation(field),
      ),
    );
  }

  _GeneratedFieldTypeSpec _fieldTypeForType(
    DartType type, {
    required bool nullable,
    required bool ref,
    required bool? dynamic,
    _TypeSpecInfo? typeSpec,
    _IntegerAnnotationSpec? integerAnnotation,
  }) {
    // If a TypeSpec annotation overrides this level, apply it.
    if (typeSpec != null) {
      final specRef = typeSpec.ref;
      if (specRef != null) ref = specRef;
      final specNullable = typeSpec.nullable;
      if (specNullable != null) nullable = specNullable;
    }
    if (_isList(type) || _isSet(type)) {
      final argument = (type as InterfaceType).typeArguments.single;
      final elementSpec = typeSpec is _ListTypeSpecInfo
          ? typeSpec.element
          : null;
      return _GeneratedFieldTypeSpec(
        typeLiteral: _typeReferenceLiteral(type),
        typeId: _typeIdFor(type),
        nullable: nullable,
        ref: ref,
        dynamic: dynamic,
        arguments: <_GeneratedFieldTypeSpec>[
          _fieldTypeForType(
            argument,
            nullable: true,
            ref: elementSpec?.ref ?? false,
            dynamic: _autoDynamic(argument),
            typeSpec: elementSpec,
          ),
        ],
      );
    }
    if (_isMap(type)) {
      final arguments = (type as InterfaceType).typeArguments;
      final keySpec = typeSpec is _MapTypeSpecInfo ? typeSpec.key : null;
      final valueSpec = typeSpec is _MapTypeSpecInfo ? typeSpec.value : null;
      return _GeneratedFieldTypeSpec(
        typeLiteral: _typeReferenceLiteral(type),
        typeId: _typeIdFor(type),
        nullable: nullable,
        ref: ref,
        dynamic: dynamic,
        arguments: <_GeneratedFieldTypeSpec>[
          _fieldTypeForType(
            arguments[0],
            nullable: true,
            ref: keySpec?.ref ?? false,
            dynamic: _autoDynamic(arguments[0]),
            typeSpec: keySpec,
          ),
          _fieldTypeForType(
            arguments[1],
            nullable: true,
            ref: valueSpec?.ref ?? false,
            dynamic: _autoDynamic(arguments[1]),
            typeSpec: valueSpec,
          ),
        ],
      );
    }
    return _GeneratedFieldTypeSpec(
      typeLiteral: _typeReferenceLiteral(type),
      typeId: _typeIdFor(type, integerAnnotation: integerAnnotation),
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
    final hasZeroArgConstructor =
        unnamedConstructor != null &&
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
        ? 'context.writeVarInt32(value.rawValue);'
        : 'context.writeVarUint32(value.index);';
    final readExpression = enumSpec.usesRawValue
        ? 'return ${enumSpec.name}.fromRawValue(context.readVarInt32());'
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
      output
        ..writeln(
          'void _write${structSpec.name}Field$index(WriteContext context, GeneratedStructFieldInfo field, ${structSpec.name} value) {',
        )
        ..writeln(
          '  writeGeneratedStructFieldInfoValue(context, field, value.${field.name});',
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
      } else {
        output.writeln(
          '      writeGeneratedStructFieldInfoValue(context, fields[$index], value.${field.name});',
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
    final annotation = _foryFieldChecker.firstAnnotationOf(field);
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
      typeId: fieldType.typeId,
      nullable: fieldType.nullable,
      ref: fieldType.ref,
      dynamic: fieldType.dynamic,
      arguments: fieldType.arguments
          .map(_fromDebugFieldType)
          .toList(growable: false),
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
    if (_isSet(type)) {
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
      if (fieldType.typeId == TypeIds.int32 ||
          fieldType.typeId == TypeIds.varInt32) {
        return '($valueExpression as Int32).value';
      }
      return '$valueExpression as int';
    }
    if (type.isDartCoreDouble) {
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
      return fieldType.typeId != TypeIds.int32 &&
          fieldType.typeId != TypeIds.varInt32;
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
    return field.fieldType.typeId == TypeIds.list ||
        field.fieldType.typeId == TypeIds.set ||
        field.fieldType.typeId == TypeIds.map;
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
        return 2;
      case TypeIds.int32:
      case TypeIds.uint32:
      case TypeIds.float32:
      case TypeIds.date:
        return 4;
      case TypeIds.int64:
      case TypeIds.uint64:
      case TypeIds.float64:
        return 8;
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
        return 'buffer.writeInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varInt64:
        return 'buffer.writeVarInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.taggedInt64:
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
      case TypeIds.float32:
        return 'buffer.writeFloat32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.float64:
        return 'buffer.writeFloat64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.string:
        return 'context.writeString($valueExpression)';
      case TypeIds.binary:
        return 'writeGeneratedBinaryValue(context, $valueExpression)';
      case TypeIds.date:
        return 'writeGeneratedLocalDateValue(context, $valueExpression)';
      case TypeIds.timestamp:
        return 'writeGeneratedTimestampValue(context, $valueExpression)';
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
        return '$cursorExpression.writeInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varInt64:
        return '$cursorExpression.writeVarInt64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.taggedInt64:
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
        return '$cursorExpression.writeUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.varUint64:
        return '$cursorExpression.writeVarUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.taggedUint64:
        return '$cursorExpression.writeTaggedUint64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.float16:
        return '$cursorExpression.writeFloat16($valueExpression)';
      case TypeIds.float32:
        return '$cursorExpression.writeFloat32(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.float64:
        return '$cursorExpression.writeFloat64(${_directGeneratedScalarExpression(field, valueExpression)})';
      case TypeIds.date:
        return '$cursorExpression.writeInt32($valueExpression.toEpochDay())';
      case TypeIds.timestamp:
        return '$cursorExpression.writeInt64($valueExpression.seconds); $cursorExpression.writeUint32($valueExpression.nanoseconds)';
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
        return 'Int8(buffer.readByte())';
      case TypeIds.int16:
        return 'Int16(buffer.readInt16())';
      case TypeIds.int32:
        return field.type.isDartCoreInt
            ? 'buffer.readInt32()'
            : 'Int32(buffer.readInt32())';
      case TypeIds.varInt32:
        return field.type.isDartCoreInt
            ? 'buffer.readVarInt32()'
            : 'Int32(buffer.readVarInt32())';
      case TypeIds.int64:
        return 'buffer.readInt64()';
      case TypeIds.varInt64:
        return 'buffer.readVarInt64()';
      case TypeIds.taggedInt64:
        return 'buffer.readTaggedInt64()';
      case TypeIds.uint8:
        return field.type.isDartCoreInt
            ? 'buffer.readUint8()'
            : 'UInt8(buffer.readUint8())';
      case TypeIds.uint16:
        return field.type.isDartCoreInt
            ? 'buffer.readUint16()'
            : 'UInt16(buffer.readUint16())';
      case TypeIds.uint32:
        return field.type.isDartCoreInt
            ? 'buffer.readUint32()'
            : 'UInt32(buffer.readUint32())';
      case TypeIds.varUint32:
        return field.type.isDartCoreInt
            ? 'buffer.readVarUint32()'
            : 'UInt32(buffer.readVarUint32())';
      case TypeIds.uint64:
        return 'buffer.readUint64()';
      case TypeIds.varUint64:
        return 'buffer.readVarUint64()';
      case TypeIds.taggedUint64:
        return 'buffer.readTaggedUint64()';
      case TypeIds.float16:
        return 'buffer.readFloat16()';
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
      case TypeIds.date:
        return 'readGeneratedLocalDateValue(context)';
      case TypeIds.timestamp:
        return 'readGeneratedTimestampValue(context)';
      case TypeIds.boolArray:
        return 'readGeneratedBoolArrayValue(context)';
      case TypeIds.int8Array:
        return 'readGeneratedTypedArrayValue<Int8List>(context, 1, (bytes) => bytes.buffer.asInt8List(bytes.offsetInBytes, bytes.lengthInBytes))';
      case TypeIds.int16Array:
        return 'readGeneratedTypedArrayValue<Int16List>(context, 2, (bytes) => bytes.buffer.asInt16List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 2))';
      case TypeIds.int32Array:
        return 'readGeneratedTypedArrayValue<Int32List>(context, 4, (bytes) => bytes.buffer.asInt32List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 4))';
      case TypeIds.int64Array:
        return 'readGeneratedTypedArrayValue<Int64List>(context, 8, (bytes) => bytes.buffer.asInt64List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 8))';
      case TypeIds.uint8Array:
        return 'readGeneratedBinaryValue(context)';
      case TypeIds.uint16Array:
      case TypeIds.float16Array:
        return 'readGeneratedTypedArrayValue<Uint16List>(context, 2, (bytes) => bytes.buffer.asUint16List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 2))';
      case TypeIds.uint32Array:
        return 'readGeneratedTypedArrayValue<Uint32List>(context, 4, (bytes) => bytes.buffer.asUint32List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 4))';
      case TypeIds.uint64Array:
        return 'readGeneratedTypedArrayValue<Uint64List>(context, 8, (bytes) => bytes.buffer.asUint64List(bytes.offsetInBytes, bytes.lengthInBytes ~/ 8))';
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
        return 'Int8($cursorExpression.readByte())';
      case TypeIds.int16:
        return 'Int16($cursorExpression.readInt16())';
      case TypeIds.int32:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readInt32()'
            : 'Int32($cursorExpression.readInt32())';
      case TypeIds.varInt32:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readVarInt32()'
            : 'Int32($cursorExpression.readVarInt32())';
      case TypeIds.int64:
        return '$cursorExpression.readInt64()';
      case TypeIds.varInt64:
        return '$cursorExpression.readVarInt64()';
      case TypeIds.taggedInt64:
        return '$cursorExpression.readTaggedInt64()';
      case TypeIds.uint8:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readUint8()'
            : 'UInt8($cursorExpression.readUint8())';
      case TypeIds.uint16:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readUint16()'
            : 'UInt16($cursorExpression.readUint16())';
      case TypeIds.uint32:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readUint32()'
            : 'UInt32($cursorExpression.readUint32())';
      case TypeIds.varUint32:
        return field.type.isDartCoreInt
            ? '$cursorExpression.readVarUint32()'
            : 'UInt32($cursorExpression.readVarUint32())';
      case TypeIds.uint64:
        return '$cursorExpression.readUint64()';
      case TypeIds.varUint64:
        return '$cursorExpression.readVarUint64()';
      case TypeIds.taggedUint64:
        return '$cursorExpression.readTaggedUint64()';
      case TypeIds.float16:
        return '$cursorExpression.readFloat16()';
      case TypeIds.float32:
        return field.type.isDartCoreDouble
            ? '$cursorExpression.readFloat32()'
            : 'Float32($cursorExpression.readFloat32())';
      case TypeIds.float64:
        return '$cursorExpression.readFloat64()';
      case TypeIds.date:
        return 'LocalDate.fromEpochDay($cursorExpression.readInt32())';
      case TypeIds.timestamp:
        return 'Timestamp($cursorExpression.readInt64(), $cursorExpression.readUint32())';
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
    if (field.type.isDartCoreInt ||
        field.type.isDartCoreDouble ||
        field.type.isDartCoreBool ||
        field.type.isDartCoreString) {
      return valueExpression;
    }
    switch (field.fieldType.typeId) {
      case TypeIds.float16:
        return valueExpression;
      default:
        return '$valueExpression.value';
    }
  }

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
    final sizeCompare =
        _primitiveSize(right.fieldType.typeId) -
        _primitiveSize(left.fieldType.typeId);
    if (sizeCompare != 0) {
      return sizeCompare;
    }
    final typeCompare = right.fieldType.typeId - left.fieldType.typeId;
    if (typeCompare != 0) {
      return typeCompare;
    }
    final keyCompare = left.sortKey.compareTo(right.sortKey);
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
    final keyCompare = left.sortKey.compareTo(right.sortKey);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return left.name.compareTo(right.name);
  }

  int _compareOtherFields(_GeneratedFieldSpec left, _GeneratedFieldSpec right) {
    final keyCompare = left.sortKey.compareTo(right.sortKey);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return left.name.compareTo(right.name);
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
      case TypeIds.date:
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
      case TypeIds.float32Array:
      case TypeIds.float64Array:
        return true;
      default:
        return false;
    }
  }

  _IntegerAnnotationSpec? _analyzeIntegerAnnotation(FieldElement field) {
    final int32Annotation = _int32Checker.firstAnnotationOf(field);
    if (int32Annotation != null) {
      final reader = ConstantReader(int32Annotation);
      final compress = reader.peek('compress')?.boolValue ?? true;
      return _IntegerAnnotationSpec(
        typeId: compress ? TypeIds.varInt32 : TypeIds.int32,
      );
    }
    final int64Annotation = _int64Checker.firstAnnotationOf(field);
    if (int64Annotation != null) {
      final reader = ConstantReader(int64Annotation);
      final encodingReader = reader.peek('encoding');
      final encodingValue = encodingReader == null || encodingReader.isNull
          ? 'varint'
          : encodingReader.revive().accessor.split('.').last;
      final typeId = switch (encodingValue) {
        'varint' => TypeIds.varInt64,
        'tagged' => TypeIds.taggedInt64,
        'fixed' => TypeIds.int64,
        _ => throw InvalidGenerationSourceError(
          'Unsupported Int64Type encoding: $encodingValue.',
          element: field,
        ),
      };
      return _IntegerAnnotationSpec(typeId: typeId);
    }
    final uint8Annotation = _uint8Checker.firstAnnotationOf(field);
    if (uint8Annotation != null) {
      return const _IntegerAnnotationSpec(typeId: TypeIds.uint8);
    }
    final uint16Annotation = _uint16Checker.firstAnnotationOf(field);
    if (uint16Annotation != null) {
      return const _IntegerAnnotationSpec(typeId: TypeIds.uint16);
    }
    final uint32Annotation = _uint32Checker.firstAnnotationOf(field);
    if (uint32Annotation != null) {
      final reader = ConstantReader(uint32Annotation);
      final compress = reader.peek('compress')?.boolValue ?? true;
      return _IntegerAnnotationSpec(
        typeId: compress ? TypeIds.varUint32 : TypeIds.uint32,
      );
    }
    final uint64Annotation = _uint64Checker.firstAnnotationOf(field);
    if (uint64Annotation != null) {
      final reader = ConstantReader(uint64Annotation);
      final encodingReader = reader.peek('encoding');
      final encodingValue = encodingReader == null || encodingReader.isNull
          ? 'varint'
          : encodingReader.revive().accessor.split('.').last;
      final typeId = switch (encodingValue) {
        'varint' => TypeIds.varUint64,
        'tagged' => TypeIds.taggedUint64,
        'fixed' => TypeIds.uint64,
        _ => throw InvalidGenerationSourceError(
          'Unsupported Uint64Type encoding: $encodingValue.',
          element: field,
        ),
      };
      return _IntegerAnnotationSpec(typeId: typeId);
    }
    return null;
  }

  _TypeSpecInfo? _analyzeTypeSpecAnnotation(FieldElement field) {
    final listAnnotation = _listTypeChecker.firstAnnotationOf(field);
    if (listAnnotation != null) {
      return _readListTypeSpec(ConstantReader(listAnnotation));
    }
    final mapAnnotation = _mapTypeChecker.firstAnnotationOf(field);
    if (mapAnnotation != null) {
      return _readMapTypeSpec(ConstantReader(mapAnnotation));
    }
    return null;
  }

  _ListTypeSpecInfo _readListTypeSpec(ConstantReader reader) {
    final options = _readTypeOptions(reader);
    final elementObj = reader.peek('element');
    final element = elementObj != null && !elementObj.isNull
        ? _readTypeSpecObj(elementObj)
        : null;
    return _ListTypeSpecInfo(
      ref: options.ref,
      nullable: options.nullable,
      element: element,
    );
  }

  _MapTypeSpecInfo _readMapTypeSpec(ConstantReader reader) {
    final options = _readTypeOptions(reader);
    final keyObj = reader.peek('key');
    final valueObj = reader.peek('value');
    final key = keyObj != null && !keyObj.isNull
        ? _readTypeSpecObj(keyObj)
        : null;
    final value = valueObj != null && !valueObj.isNull
        ? _readTypeSpecObj(valueObj)
        : null;
    return _MapTypeSpecInfo(
      ref: options.ref,
      nullable: options.nullable,
      key: key,
      value: value,
    );
  }

  _TypeSpecInfo _readTypeSpecObj(ConstantReader reader) {
    final objType = reader.objectValue.type;
    if (objType != null && _listTypeChecker.isExactlyType(objType)) {
      return _readListTypeSpec(reader);
    }
    if (objType != null && _mapTypeChecker.isExactlyType(objType)) {
      return _readMapTypeSpec(reader);
    }
    // ValueType or fallback
    final options = _readTypeOptions(reader);
    return _TypeSpecInfo(ref: options.ref, nullable: options.nullable);
  }

  ({bool? ref, bool? nullable}) _readTypeOptions(ConstantReader reader) {
    bool? ref;
    bool? nullable;
    final optionsReader = reader.peek('options');
    if (optionsReader != null && !optionsReader.isNull) {
      for (final optionObj in optionsReader.listValue) {
        final optionReader = ConstantReader(optionObj);
        final optionType = optionObj.type;
        if (optionType != null && _refOptionChecker.isExactlyType(optionType)) {
          ref = optionReader.peek('tracked')?.boolValue ?? true;
        } else if (optionType != null &&
            _nullableOptionChecker.isExactlyType(optionType)) {
          nullable = optionReader.peek('value')?.boolValue ?? true;
        }
      }
    }
    return (ref: ref, nullable: nullable);
  }

  int _typeIdFor(DartType type, {_IntegerAnnotationSpec? integerAnnotation}) {
    if (integerAnnotation != null) {
      return integerAnnotation.typeId;
    }
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
      case 'Uint8List':
        return TypeIds.binary;
      case 'List<bool>':
        return TypeIds.boolArray;
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
      case 'Int8':
        return TypeIds.int8;
      case 'Int16':
        return TypeIds.int16;
      case 'Int32':
        return TypeIds.varInt32;
      case 'UInt8':
        return TypeIds.uint8;
      case 'UInt16':
        return TypeIds.uint16;
      case 'UInt32':
        return TypeIds.uint32;
      case 'Float16':
        return TypeIds.float16;
      case 'Float32':
        return TypeIds.float32;
      case 'Timestamp':
        return TypeIds.timestamp;
      case 'LocalDate':
        return TypeIds.date;
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
      return 'buffer.writeVarInt32($valueExpression.rawValue)';
    }
    return 'buffer.writeVarUint32($valueExpression.index)';
  }

  String _enumCursorWriteExpression(
    DartType type,
    String cursorExpression,
    String valueExpression,
  ) {
    if (_enumUsesRawValue(type)) {
      return '$cursorExpression.writeVarInt32($valueExpression.rawValue)';
    }
    return '$cursorExpression.writeVarUint32($valueExpression.index)';
  }

  String _enumReadExpression(DartType type, String contextExpression) {
    final typeDisplay = _typeReferenceLiteral(type);
    if (_enumUsesRawValue(type)) {
      return '$typeDisplay.fromRawValue($contextExpression.readVarInt32())';
    }
    return '$typeDisplay.values[$contextExpression.readVarUint32()]';
  }

  String _enumCursorReadExpression(DartType type, String cursorExpression) {
    final typeDisplay = _typeReferenceLiteral(type);
    if (_enumUsesRawValue(type)) {
      return '$typeDisplay.fromRawValue($cursorExpression.readVarInt32())';
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

  bool _isList(DartType type) => type.isDartCoreList;

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
      final typeArguments = nonNullable.typeArguments
          .map(_typeCodeString)
          .join(', ');
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

  String get sortKey => id != null && id! >= 0 ? '$id' : identifier;

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
  final int typeId;
  final bool nullable;
  final bool ref;
  final bool? dynamic;
  final List<_GeneratedFieldTypeSpec> arguments;

  const _GeneratedFieldTypeSpec({
    required this.typeLiteral,
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

final class _IntegerAnnotationSpec {
  final int typeId;

  const _IntegerAnnotationSpec({required this.typeId});
}

/// Parsed representation of a [TypeSpec] annotation hierarchy.
class _TypeSpecInfo {
  final bool? ref;
  final bool? nullable;

  const _TypeSpecInfo({this.ref, this.nullable});
}

final class _ListTypeSpecInfo extends _TypeSpecInfo {
  final _TypeSpecInfo? element;

  const _ListTypeSpecInfo({super.ref, super.nullable, this.element});
}

final class _MapTypeSpecInfo extends _TypeSpecInfo {
  final _TypeSpecInfo? key;
  final _TypeSpecInfo? value;

  const _MapTypeSpecInfo({super.ref, super.nullable, this.key, this.value});
}
