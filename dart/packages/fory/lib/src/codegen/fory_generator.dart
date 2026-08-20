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
import 'dart:convert';

import 'package:analyzer/dart/ast/ast.dart';
import 'package:analyzer/dart/constant/value.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/dart/element/nullability_suffix.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:build/build.dart';
import 'package:fory/fory.dart';
import 'package:source_gen/source_gen.dart';

part 'fory_constructor_analysis.dart';

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
  static const int _referenceBytes = 4;
  // Conservative lower bound for a retained generated Dart struct object itself. Field reference
  // slots are added separately; this is not a Fory wire header or a Dart VM layout probe.
  static const int _structObjectOwnerBytes = 6 * _referenceBytes;

  static const TypeChecker _foryStructChecker = TypeChecker.typeNamed(
    ForyStruct,
    inPackage: 'fory',
  );
  static const TypeChecker _foryFieldChecker = TypeChecker.typeNamed(
    ForyField,
    inPackage: 'fory',
  );
  static const TypeChecker _listFieldChecker = TypeChecker.typeNamed(
    ListField,
    inPackage: 'fory',
  );
  static const TypeChecker _arrayFieldChecker = TypeChecker.typeNamed(
    ArrayField,
    inPackage: 'fory',
  );
  static const TypeChecker _setFieldChecker = TypeChecker.typeNamed(
    SetField,
    inPackage: 'fory',
  );
  static const TypeChecker _mapFieldChecker = TypeChecker.typeNamed(
    MapField,
    inPackage: 'fory',
  );
  static const TypeChecker _typeSpecChecker = TypeChecker.typeNamed(
    TypeSpec,
    inPackage: 'fory',
  );
  static const TypeChecker _foryUnionChecker = TypeChecker.typeNamed(
    ForyUnion,
    inPackage: 'fory',
  );

  late LibraryElement _sourceLibrary;
  final Map<Element, String?> _importPrefixByElement = <Element, String?>{};
  final Set<Element> _resolvedImportElements = <Element>{};
  final Map<InterfaceType, _HierarchyStorage> _hierarchyStorageByType =
      <InterfaceType, _HierarchyStorage>{};
  final Map<InterfaceElement, _StructOptions> _structOptionsByElement =
      <InterfaceElement, _StructOptions>{};
  Map<LibraryElement, String>? _canonicalLibraryUris;

  ForyGenerator();

  ForyGenerator._forInput();

  @override
  Future<String> generate(LibraryReader library, BuildStep buildStep) {
    return ForyGenerator._forInput()._generate(library, buildStep);
  }

  Future<String> _generate(LibraryReader library, BuildStep buildStep) async {
    _sourceLibrary = library.element;
    final annotatedClasses = <ClassElement>[];
    for (final element in library.classes) {
      if (_foryStructChecker.hasAnnotationOf(element)) {
        annotatedClasses.add(element);
      }
    }
    final annotatedMixins = <MixinElement>[];
    for (final element in library.element.mixins) {
      if (_foryStructChecker.hasAnnotationOf(element)) {
        annotatedMixins.add(element);
      }
    }
    final enumElements = library.enums;
    if (annotatedClasses.isEmpty &&
        annotatedMixins.isEmpty &&
        enumElements.isEmpty) {
      return '';
    }

    final helperBaseName = _toPascalCase(
      buildStep.inputId.pathSegments.last.split('.').first,
    );
    final generatedApiName = '${helperBaseName}ForyModule';
    final emitRegistrationHelper =
        !_declaresGeneratedApiOwner(library, buildStep, generatedApiName);

    final enumSpecs = <_GeneratedEnumSpec>[];
    for (final element in enumElements) {
      enumSpecs.add(_analyzeEnum(element));
    }
    final structSpecs = <_GeneratedStructSpec>[];
    final companionSpecs = <_PrivateAccessCompanionSpec>[];
    final constructorAnalyzer = _OrdinaryConstructorAnalyzer(
      buildStep: buildStep,
    );
    for (final element in annotatedClasses) {
      final options = _structOptions(element);
      if (options.target != null) {
        if (options.exposePrivateFields) {
          throw InvalidGenerationSourceError(
            'ForyStruct.exposePrivateFields cannot be used with '
            'ForyStruct.target. External structural declarations retain an '
            'explicit field model.',
            element: element,
          );
        }
        if (options.ignoreInheritedPrivateFields) {
          throw InvalidGenerationSourceError(
            'ForyStruct.ignoreInheritedPrivateFields cannot be used with '
            'ForyStruct.target. External structural declarations retain an '
            'explicit field model.',
            element: element,
          );
        }
      }
      final ownsSerializer =
          options.target != null ||
          (_ownsOrdinarySerializer(element) && element.typeParameters.isEmpty);
      if (!ownsSerializer) {
        _validateProviderOnly(element, options);
      }
      if (options.exposePrivateFields) {
        companionSpecs.add(
          await _analyzePrivateAccessCompanion(element, buildStep),
        );
      }
      if (!ownsSerializer) {
        continue;
      }
      final annotatedTarget = options.target;
      final _GeneratedStructSpec structSpec;
      if (annotatedTarget == null) {
        structSpec = await _analyzeOrdinaryStruct(
          element,
          options,
          constructorAnalyzer,
          buildStep,
        );
      } else {
        structSpec = _analyzeExternalStruct(element, options, annotatedTarget);
      }
      _GeneratedStructSpec? duplicate;
      for (final existing in structSpecs) {
        if (existing.targetType == structSpec.targetType) {
          duplicate = existing;
          break;
        }
      }
      if (duplicate != null) {
        throw InvalidGenerationSourceError(
          'Fory struct declarations ${duplicate.name} and ${structSpec.name} '
          'both target ${structSpec.targetTypeLiteral}. Declare exactly one '
          'structural schema for each target type in a library.',
          element: element,
        );
      }
      structSpecs.add(structSpec);
    }
    for (final element in annotatedMixins) {
      final options = _structOptions(element);
      _validateProviderOnly(element, options);
      companionSpecs.add(
        await _analyzePrivateAccessCompanion(element, buildStep),
      );
    }
    final output =
        StringBuffer()
          ..writeln(
            '// ignore_for_file: implementation_imports, invalid_use_of_internal_member, no_leading_underscores_for_local_identifiers, unreachable_switch_case, unused_element, unused_element_parameter, unnecessary_null_comparison',
          )
          ..writeln();
    for (final enumSpec in enumSpecs) {
      _writeEnum(output, enumSpec);
    }
    for (final companionSpec in companionSpecs) {
      _writePrivateAccessCompanion(output, companionSpec);
    }
    for (final structSpec in structSpecs) {
      _writeStruct(output, structSpec);
    }

    if (enumSpecs.isNotEmpty || structSpecs.isNotEmpty) {
      _writeGeneratedSupport(
        output,
        enumSpecs: enumSpecs,
        structSpecs: structSpecs,
        generatedApiName: generatedApiName,
        emitRegistrationHelper: emitRegistrationHelper,
      );
    }
    return output.toString();
  }

  bool _declaresGeneratedApiOwner(
    LibraryReader library,
    BuildStep buildStep,
    String generatedApiName,
  ) {
    final inputFileName = buildStep.inputId.pathSegments.last;
    for (final element in library.classes) {
      if (element.displayName == generatedApiName &&
          element.firstFragment.libraryFragment.source.shortName ==
              inputFileName) {
        return true;
      }
    }
    return false;
  }

  _StructOptions _structOptions(InterfaceElement element) {
    final cached = _structOptionsByElement[element];
    if (cached != null) {
      return cached;
    }
    final objectAnnotation = _foryStructChecker.firstAnnotationOf(element);
    final objectReader = ConstantReader(objectAnnotation);
    final evolving = objectReader.peek('evolving')?.boolValue ?? true;
    final exposePrivateFields =
        objectReader.peek('exposePrivateFields')?.boolValue ?? false;
    final ignoreInheritedPrivateFields =
        objectReader.peek('ignoreInheritedPrivateFields')?.boolValue ?? false;
    final targetReader = objectReader.peek('target');
    final annotatedTarget =
        targetReader == null || targetReader.isNull
            ? null
            : targetReader.typeValue;
    final constructorReader = objectReader.peek('constructor');
    final constructorName =
        constructorReader == null || constructorReader.isNull
            ? null
            : constructorReader.stringValue;
    final options = _StructOptions(
      evolving: evolving,
      target: annotatedTarget,
      constructorName: constructorName,
      exposePrivateFields: exposePrivateFields,
      ignoreInheritedPrivateFields: ignoreInheritedPrivateFields,
    );
    _structOptionsByElement[element] = options;
    return options;
  }

  bool _ownsOrdinarySerializer(ClassElement element) =>
      !element.isAbstract && element.isConstructable;

  void _validateProviderOnly(InterfaceElement element, _StructOptions options) {
    if (options.target != null) {
      throw InvalidGenerationSourceError(
        'ForyStruct.target is valid only on an external serializer '
        'declaration class.',
        element: element,
      );
    }
    if (options.ignoreInheritedPrivateFields) {
      throw InvalidGenerationSourceError(
        'ForyStruct.ignoreInheritedPrivateFields is valid only on an '
        'ordinary concrete declaration that owns a generated flattened '
        'schema. ${element.displayName} is provider-only.',
        element: element,
      );
    }
    if (!options.exposePrivateFields) {
      throw InvalidGenerationSourceError(
        '${element.displayName} does not own a concrete generated serializer. '
        'Annotate it with ForyStruct(exposePrivateFields: true) only when it '
        'is a public hierarchy boundary that exposes private state.',
        element: element,
      );
    }
    if (!options.evolving) {
      throw InvalidGenerationSourceError(
        'ForyStruct.evolving has no meaning on provider-only declaration '
        '${element.displayName}. Remove evolving: false.',
        element: element,
      );
    }
    if (options.constructorName != null) {
      throw InvalidGenerationSourceError(
        'ForyStruct.constructor has no meaning on provider-only declaration '
        '${element.displayName}.',
        element: element,
      );
    }
  }

  _GeneratedStructSpec _analyzeExternalStruct(
    ClassElement element,
    _StructOptions options,
    DartType annotatedTarget,
  ) {
    final constructorName = options.constructorName;
    final declarationFields = element.fields
        .where(_isStoredInstanceField)
        .toList(growable: false);

    final targetType = _validateExternalTarget(element, annotatedTarget);
    _validateExternalDeclaration(element, targetType, declarationFields);
    final targetTypeLiteral = _externalTargetTypeLiteral(targetType, element);
    final selectedConstructor = _selectConstructor(
      element,
      targetType,
      targetTypeLiteral,
      constructorName,
      external: true,
    );

    final indexedCodegenNames = _hasCodegenNameConflict(
      declarationFields
          .where((field) => !_isIgnored(field))
          .map((field) => field.displayName),
    );
    final fields = <_GeneratedFieldSpec>[];
    for (var index = 0; index < declarationFields.length; index += 1) {
      final field = declarationFields[index];
      if (_isIgnored(field)) {
        continue;
      }
      fields.add(
        _analyzeField(
          element,
          targetType,
          targetTypeLiteral,
          field,
          indexedCodegenNames ? 'field$index' : field.displayName,
        ),
      );
    }
    final analyzedConstruction = _buildExternalConstructionModel(
      element,
      targetTypeLiteral,
      selectedConstructor,
      constructorName,
      fields,
    );
    _validateConstructorSelfReference(
      declaration: element,
      targetType: targetType,
      targetTypeLiteral: targetTypeLiteral,
      fields: fields,
      constructionModel: analyzedConstruction,
    );
    _validateWireIdentities(element, fields);
    final sortedFields = _sortFields(fields);
    return _GeneratedStructSpec(
      name: element.displayName,
      targetType: targetType,
      targetTypeLiteral: targetTypeLiteral,
      evolving: options.evolving,
      fields: sortedFields,
      storageFieldCount: _externalGraphFieldCount(
        element,
        targetType,
        declarationFields,
      ),
      constructionModel: analyzedConstruction,
    );
  }

  Future<_GeneratedStructSpec> _analyzeOrdinaryStruct(
    ClassElement element,
    _StructOptions options,
    _OrdinaryConstructorAnalyzer constructorAnalyzer,
    BuildStep buildStep,
  ) async {
    if (options.constructorName != null) {
      throw InvalidGenerationSourceError(
        'ForyStruct.constructor is valid only when ForyStruct.target is set. '
        'Ordinary structs use their unnamed generative constructor.',
        element: element,
      );
    }
    final targetType = element.thisType;
    final targetTypeLiteral = _typeReferenceLiteral(targetType);
    final selectedConstructor = _selectConstructor(
      element,
      targetType,
      targetTypeLiteral,
      null,
      external: false,
    );
    final hierarchy = _discoverHierarchyStorage(targetType, element);
    final discovered = hierarchy.fields;
    final included = <_DiscoveredField>[];
    final includedNames = <String>[];
    for (final field in discovered) {
      if (_isIgnored(field.declaration)) {
        continue;
      }
      if (options.ignoreInheritedPrivateFields &&
          field.declaration.isPrivate &&
          // Declaration ownership determines whether storage is inherited;
          // an applied mixin layer is never owned by the concrete child.
          !identical(
            field.declaration.enclosingElement.baseElement,
            element.baseElement,
          )) {
        continue;
      }
      included.add(field);
      includedNames.add(field.declaration.displayName);
    }
    final indexedCodegenNames = _hasCodegenNameConflict(includedNames);
    final fields = <_GeneratedFieldSpec>[];
    for (final field in included) {
      final fieldLibrary = field.declaration.library;
      if (field.declaration.isPrivate &&
          fieldLibrary != element.library &&
          fieldLibrary.uri.scheme == 'file' &&
          _canonicalLibraryUris?.containsKey(fieldLibrary) != true) {
        try {
          final assetId = await buildStep.resolver.assetIdForElement(
            fieldLibrary,
          );
          final canonicalUris =
              _canonicalLibraryUris ??= Map<LibraryElement, String>.identity();
          canonicalUris[fieldLibrary] = assetId.uri.toString();
        } on Object catch (error) {
          throw InvalidGenerationSourceError(
            'Fory cannot derive a stable private-field companion identity '
            'from library ${fieldLibrary.uri}: $error',
            element: field.declaration,
          );
        }
      }
      final name = field.declaration.displayName;
      fields.add(
        _analyzeOrdinaryField(
          element,
          targetType,
          targetTypeLiteral,
          hierarchy,
          field,
          indexedCodegenNames ? 'field${field.storageIndex}' : name,
        ),
      );
    }
    final analyzedConstruction = await constructorAnalyzer.build(
      declaration: element,
      targetTypeLiteral: targetTypeLiteral,
      constructor: selectedConstructor,
      fields: fields,
    );
    _validateConstructorSelfReference(
      declaration: element,
      targetType: targetType,
      targetTypeLiteral: targetTypeLiteral,
      fields: fields,
      constructionModel: analyzedConstruction,
    );
    _validateWireIdentities(element, fields);
    final sortedFields = _sortFields(fields);
    return _GeneratedStructSpec(
      name: element.displayName,
      targetType: targetType,
      targetTypeLiteral: targetTypeLiteral,
      evolving: options.evolving,
      fields: sortedFields,
      storageFieldCount: hierarchy.fields.length,
      constructionModel: analyzedConstruction,
    );
  }

  bool _hasCodegenNameConflict(Iterable<String> names) {
    final sourceNames = <String>{};
    final capitalizedNames = <String>{};
    for (final name in names) {
      if (!sourceNames.add(name)) {
        return true;
      }
      final capitalized = '${name[0].toUpperCase()}${name.substring(1)}';
      if (!capitalizedNames.add(capitalized)) {
        return true;
      }
    }
    return false;
  }

  InterfaceType _validateExternalTarget(
    ClassElement declaration,
    DartType annotatedTarget,
  ) {
    if (annotatedTarget is! InterfaceType ||
        annotatedTarget.element is! ClassElement) {
      throw InvalidGenerationSourceError(
        'ForyStruct.target on ${declaration.displayName} must name a concrete '
        'Dart class. Built-in values, carriers, enums, unions, records, '
        'functions, extension types, and type parameters are not external '
        'struct targets.',
        element: declaration,
      );
    }
    final targetType = annotatedTarget;
    final targetElement = targetType.element as ClassElement;
    final targetName = targetType.getDisplayString();
    if (targetType == declaration.thisType) {
      throw InvalidGenerationSourceError(
        'ForyStruct.target on ${declaration.displayName} must name another '
        'class. Remove target to serialize the annotated class itself.',
        element: declaration,
      );
    }
    if (targetType.nullabilitySuffix != NullabilitySuffix.none) {
      throw InvalidGenerationSourceError(
        'ForyStruct.target on ${declaration.displayName} must be non-nullable; '
        '$targetName is not a valid structural target.',
        element: declaration,
      );
    }
    if (_containsTypeParameter(targetType)) {
      throw InvalidGenerationSourceError(
        'ForyStruct.target $targetName on ${declaration.displayName} must be a '
        'closed type with no unresolved type parameters.',
        element: declaration,
      );
    }
    final targetLibrary = targetElement.library;
    final targetUri = targetLibrary.uri;
    final foryBuiltin =
        targetUri.scheme == 'package' &&
        targetUri.pathSegments.firstOrNull == 'fory' &&
        _typeIdFor(targetType) != TypeIds.compatibleStruct;
    if (targetLibrary.isDartCore ||
        targetUri.toString() == 'dart:typed_data' ||
        foryBuiltin ||
        _isList(targetType) ||
        _isSet(targetType) ||
        _isMap(targetType) ||
        _foryUnionChecker.hasAnnotationOf(targetElement)) {
      throw InvalidGenerationSourceError(
        'ForyStruct.target $targetName on ${declaration.displayName} is owned '
        'by an existing built-in, carrier, enum, or union serializer. Use that '
        'serializer directly.',
        element: declaration,
      );
    }
    if (targetElement.isAbstract || !targetElement.isConstructable) {
      throw InvalidGenerationSourceError(
        'ForyStruct.target $targetName on ${declaration.displayName} must be a '
        'concrete constructable Dart class.',
        element: declaration,
      );
    }
    return targetType;
  }

  void _validateExternalDeclaration(
    ClassElement declaration,
    InterfaceType targetType,
    List<FieldElement> declarationFields,
  ) {
    final targetName = targetType.getDisplayString();
    if (!declaration.isAbstract || !declaration.isFinal) {
      throw InvalidGenerationSourceError(
        'External structural serializer declaration '
        '${declaration.displayName} for $targetName must be declared '
        'abstract final.',
        element: declaration,
      );
    }
    if (declaration.typeParameters.isNotEmpty) {
      throw InvalidGenerationSourceError(
        'External structural serializer declaration '
        '${declaration.displayName} for $targetName cannot declare type '
        'parameters. Target one closed generic instantiation instead.',
        element: declaration,
      );
    }
    for (final field in declarationFields) {
      if (!field.isLate || !field.isFinal || field.hasInitializer) {
        throw InvalidGenerationSourceError(
          'Schema field ${declaration.displayName}.${field.displayName} for '
          '$targetName must be a late final field without an initializer.',
          element: field,
        );
      }
    }
  }

  ConstructorElement _selectConstructor(
    ClassElement declaration,
    InterfaceType targetType,
    String targetTypeLiteral,
    String? constructorName, {
    required bool external,
  }) {
    if (constructorName != null &&
        (constructorName.isEmpty || constructorName.startsWith('_'))) {
      throw InvalidGenerationSourceError(
        'ForyStruct.constructor on ${declaration.displayName} must name a '
        'public named generative constructor on $targetTypeLiteral.',
        element: declaration,
      );
    }
    final constructor = targetType.lookUpConstructor(
      constructorName,
      declaration.library,
    );
    if (constructor == null) {
      final selected =
          constructorName == null ? 'unnamed constructor' : '.$constructorName';
      final remedy =
          external
              ? 'Expose that constructor, select another public named '
                  'generative constructor, or use a custom serializer.'
              : 'Add an accessible generative constructor.';
      throw InvalidGenerationSourceError(
        'Target $targetTypeLiteral for ${declaration.displayName} has no '
        'accessible $selected. $remedy',
        element: declaration,
      );
    }
    if (!constructor.isGenerative || constructor.isFactory) {
      final selected =
          constructorName == null ? 'unnamed constructor' : '.$constructorName';
      throw InvalidGenerationSourceError(
        'Target constructor $targetTypeLiteral$selected selected by '
        '${declaration.displayName} must be generative, not a factory. Select '
        'a public generative constructor or use a custom serializer.',
        element: declaration,
      );
    }
    return constructor;
  }

  bool _containsTypeParameter(DartType type) {
    if (type is TypeParameterType || type is InvalidType) {
      return true;
    }
    if (type is InterfaceType) {
      return type.typeArguments.any(_containsTypeParameter);
    }
    if (type is FunctionType) {
      if (type.typeParameters.isNotEmpty ||
          _containsTypeParameter(type.returnType)) {
        return true;
      }
      return type.formalParameters.any(
        (parameter) => _containsTypeParameter(parameter.type),
      );
    }
    if (type is RecordType) {
      return type.positionalFields.any(
            (field) => _containsTypeParameter(field.type),
          ) ||
          type.namedFields.any((field) => _containsTypeParameter(field.type));
    }
    return false;
  }

  String _externalTargetTypeLiteral(
    InterfaceType targetType,
    ClassElement declaration,
  ) {
    String render(DartType type) {
      final alias = type.alias;
      if (alias != null) {
        final base = _visibleTypeName(alias.element, declaration, targetType);
        final arguments =
            alias.typeArguments.isEmpty
                ? ''
                : '<${alias.typeArguments.map(render).join(', ')}>';
        final nullable =
            type.nullabilitySuffix == NullabilitySuffix.question ? '?' : '';
        return '$base$arguments$nullable';
      }
      if (type is DynamicType) {
        return 'dynamic';
      }
      if (type is NeverType) {
        return 'Never';
      }
      if (type is VoidType) {
        return 'void';
      }
      if (type is InterfaceType) {
        final base = _visibleTypeName(type.element, declaration, targetType);
        final arguments =
            type.typeArguments.isEmpty
                ? ''
                : '<${type.typeArguments.map(render).join(', ')}>';
        final nullable =
            type.nullabilitySuffix == NullabilitySuffix.question ? '?' : '';
        return '$base$arguments$nullable';
      }
      throw InvalidGenerationSourceError(
        'ForyStruct.target ${targetType.getDisplayString()} on '
        '${declaration.displayName} contains a type that cannot be rendered '
        'from the declaration library. Import a public closed class type '
        'directly or use a custom serializer.',
        element: declaration,
      );
    }

    return render(targetType);
  }

  String _visibleTypeName(
    Element typeElement,
    ClassElement declaration,
    InterfaceType targetType,
  ) {
    if (typeElement.library == declaration.library ||
        typeElement.library?.isDartCore == true) {
      return typeElement.displayName;
    }
    if (_hasImportReference(typeElement)) {
      final prefix = _importPrefixFor(typeElement);
      return prefix == null
          ? typeElement.displayName
          : '$prefix.${typeElement.displayName}';
    }
    throw InvalidGenerationSourceError(
      'ForyStruct.target ${targetType.getDisplayString()} on '
      '${declaration.displayName} cannot be rendered from this library. '
      'Import the public target and every generic argument directly.',
      element: declaration,
    );
  }

  bool _hasImportReference(Element element) {
    final baseElement = element.baseElement;
    if (!_resolvedImportElements.add(baseElement)) {
      return _importPrefixByElement.containsKey(baseElement);
    }
    final name = element.displayName;
    final sourceFragment = _sourceLibrary.firstFragment;
    final unprefixed = sourceFragment.scope.lookup(name).getter;
    if (unprefixed != null && identical(unprefixed.baseElement, baseElement)) {
      _importPrefixByElement[baseElement] = null;
      return true;
    }

    String? selectedPrefix;
    for (final import in sourceFragment.libraryImports) {
      if (import.prefix?.isDeferred == true) {
        continue;
      }
      final prefix = import.prefix?.element;
      if (prefix == null || prefix.displayName.isEmpty) {
        continue;
      }
      final importedElement = import.namespace.definedNames2[name];
      if (importedElement == null ||
          !identical(importedElement.baseElement, baseElement)) {
        continue;
      }
      final resolved = prefix.scope.lookup(name).getter;
      if (resolved == null || !identical(resolved.baseElement, baseElement)) {
        continue;
      }
      final prefixName = prefix.displayName;
      if (selectedPrefix == null || prefixName.compareTo(selectedPrefix) < 0) {
        selectedPrefix = prefixName;
      }
    }
    if (selectedPrefix == null) {
      return false;
    }
    _importPrefixByElement[baseElement] = selectedPrefix;
    return true;
  }

  String? _importPrefixFor(Element element) {
    final library = element.library;
    if (library == _sourceLibrary || library?.isDartCore == true) {
      return null;
    }
    _hasImportReference(element);
    return _importPrefixByElement[element.baseElement];
  }

  _GeneratedEnumSpec _analyzeEnum(EnumElement element) {
    return _GeneratedEnumSpec(
      name: element.displayName,
      usesRawValue: _enumUsesRawValueElement(element),
    );
  }

  _HierarchyStorage _discoverHierarchyStorage(
    InterfaceType targetType,
    Element errorElement,
  ) {
    final cached = _hierarchyStorageByType[targetType];
    if (cached != null) {
      return cached;
    }
    final layers = <_HierarchyLayer>[];
    final fields = <_DiscoveredField>[];
    final visiting = <InterfaceElement>{};

    void appendLayer(InterfaceType type) {
      if (type.element is ClassElement &&
          (type.element as ClassElement).isDartCoreObject) {
        return;
      }
      final layer = _HierarchyLayer(type: type, index: layers.length);
      layers.add(layer);
      for (final declaration in type.element.fields) {
        if (!_isStoredInstanceField(declaration)) {
          continue;
        }
        fields.add(
          _DiscoveredField(
            declaration: declaration.baseElement,
            layer: layer,
            storageIndex: fields.length,
          ),
        );
      }
    }

    void visitClass(InterfaceType type) {
      final element = type.element;
      if (element is ClassElement && element.isDartCoreObject) {
        return;
      }
      if (!visiting.add(element)) {
        throw InvalidGenerationSourceError(
          'Fory cannot flatten a cyclic superclass or mixin storage chain for '
          '${targetType.getDisplayString()}.',
          element: errorElement,
        );
      }
      final superclass = type.superclass;
      if (superclass != null) {
        visitClass(superclass);
      }
      for (final mixin in type.mixins) {
        appendLayer(mixin);
      }
      appendLayer(type);
      visiting.remove(element);
    }

    if (targetType.element is MixinElement) {
      appendLayer(targetType);
    } else {
      visitClass(targetType);
    }
    final hierarchy = _HierarchyStorage(layers: layers, fields: fields);
    _hierarchyStorageByType[targetType] = hierarchy;
    return hierarchy;
  }

  _GeneratedFieldSpec _analyzeOrdinaryField(
    ClassElement declaration,
    InterfaceType targetType,
    String targetTypeLiteral,
    _HierarchyStorage hierarchy,
    _DiscoveredField discovered,
    String codegenName,
  ) {
    final field = discovered.declaration;
    final effectiveType = _effectiveFieldType(discovered);
    _validateOrdinaryFieldType(effectiveType, field, targetTypeLiteral);
    _validateTypeVisibleFrom(
      effectiveType,
      declaration.library,
      errorElement: field,
      context:
          'Storage field ${field.enclosingElement.displayName}.'
          '${field.displayName}',
    );
    final access = _resolveOrdinaryFieldAccess(
      declaration,
      targetType,
      hierarchy,
      discovered,
      effectiveType,
    );
    return _createGeneratedFieldSpec(
      annotationField: field,
      effectiveType: effectiveType,
      codegenName: codegenName,
      writable: !field.isFinal,
      access: access,
    );
  }

  void _validateOrdinaryFieldType(
    DartType type,
    FieldElement field,
    String targetTypeLiteral,
  ) {
    void validate(DartType current) {
      if (current is DynamicType) {
        return;
      }
      if (current is InvalidType || current is TypeParameterType) {
        throw InvalidGenerationSourceError(
          'Storage field ${field.enclosingElement.displayName}.'
          '${field.displayName} has unresolved effective type '
          '${type.getDisplayString()} while generating $targetTypeLiteral.',
          element: field,
        );
      }
      if (current is FunctionType ||
          current is RecordType ||
          current is VoidType ||
          current is NeverType ||
          current.isDartCoreFunction ||
          current.isDartCoreRecord ||
          current.isDartCoreNull) {
        throw InvalidGenerationSourceError(
          'Storage field ${field.enclosingElement.displayName}.'
          '${field.displayName} has unsupported effective type '
          '${type.getDisplayString()}. Mark the field with '
          '@ForyField(ignore: true), use a supported serializable type, or '
          'use a custom serializer.',
          element: field,
        );
      }
      if (current is InterfaceType) {
        for (final argument in current.typeArguments) {
          validate(argument);
        }
        return;
      }
      throw InvalidGenerationSourceError(
        'Storage field ${field.enclosingElement.displayName}.'
        '${field.displayName} has unsupported effective type '
        '${type.getDisplayString()}. Mark the field with '
        '@ForyField(ignore: true), use a supported serializable type, or use '
        'a custom serializer.',
        element: field,
      );
    }

    validate(type);
  }

  DartType _effectiveFieldType(_DiscoveredField discovered) {
    final declaration = discovered.declaration;
    final getter = discovered.layer.type.getGetter(declaration.displayName);
    final variable = getter?.variable;
    if (getter == null ||
        variable is! FieldElement ||
        !identical(variable.baseElement, declaration.baseElement)) {
      throw InvalidGenerationSourceError(
        'Fory could not substitute storage field '
        '${discovered.layer.type.element.displayName}.'
        '${declaration.displayName} in its instantiated hierarchy layer.',
        element: declaration,
      );
    }
    return getter.returnType;
  }

  _FieldAccessPlan _resolveOrdinaryFieldAccess(
    ClassElement child,
    InterfaceType targetType,
    _HierarchyStorage hierarchy,
    _DiscoveredField discovered,
    DartType effectiveType,
  ) {
    final field = discovered.declaration;
    final sameLibrary = field.library == child.library;
    if (field.isPublic || sameLibrary) {
      _validateExactFieldAccess(
        targetType: targetType,
        lookupLibrary: child.library,
        discovered: discovered,
        effectiveType: effectiveType,
        hierarchy: hierarchy,
        requireSetter: !field.isFinal,
        child: child,
      );
      return _FieldAccessPlan.direct(field.displayName);
    }

    final boundary = _resolvePrivateAccessBoundary(
      child: child,
      hierarchy: hierarchy,
      discovered: discovered,
    );
    _validateExactFieldAccess(
      targetType: targetType,
      lookupLibrary: field.library,
      discovered: discovered,
      effectiveType: effectiveType,
      hierarchy: hierarchy,
      requireSetter: !field.isFinal,
      child: child,
    );
    final identity = _privateFieldIdentity(field);
    final digest = _privateFieldDigest(identity);
    return _FieldAccessPlan.companion(
      fieldName: field.displayName,
      companion: boundary,
      getter: '\$g$digest',
      setter: field.isFinal ? null : '\$s$digest',
    );
  }

  String _resolvePrivateAccessBoundary({
    required ClassElement child,
    required _HierarchyStorage hierarchy,
    required _DiscoveredField discovered,
  }) {
    final field = discovered.declaration;
    final authorizedHelpers = <String>{};
    for (final layer in hierarchy.layers.reversed) {
      if (layer.index < discovered.layer.index) {
        continue;
      }
      final boundary = layer.type.element;
      if (boundary.library != field.library ||
          boundary.isPrivate ||
          !_foryStructChecker.hasAnnotationOf(boundary)) {
        continue;
      }
      final options = _structOptions(boundary);
      if (!options.exposePrivateFields || options.target != null) {
        continue;
      }
      final boundaryHierarchy = _discoverHierarchyStorage(
        boundary.thisType,
        boundary,
      );
      var carriesField = false;
      for (final candidate in boundaryHierarchy.fields) {
        if (identical(candidate.declaration.baseElement, field.baseElement)) {
          carriesField = true;
          break;
        }
      }
      if (!carriesField) {
        continue;
      }
      final helperName = _privateAccessHelperName(boundary);
      authorizedHelpers.add(helperName);
      final reference = _companionReferenceFromChild(
        child.library,
        boundary,
        helperName,
      );
      if (reference != null) {
        return reference;
      }
    }

    final helperNames = authorizedHelpers.toList()..sort();
    final authorization =
        helperNames.isNotEmpty
            ? 'An authorized public boundary exists, but its generated '
                'companion is not visible through the child library imports '
                'and re-exports. Expected ${helperNames.join(' or ')}.'
            : 'No public hierarchy boundary in the declaring library is '
                'annotated with '
                '@ForyStruct(exposePrivateFields: true).';
    final consumerPermission =
        _structOptions(child).exposePrivateFields
            ? ' The annotation on ${child.displayName} authorizes only '
                'private storage declared by ${child.library.uri}; it cannot '
                'authorize this field.'
            : '';
    throw InvalidGenerationSourceError(
      'Fory discovered inherited private field '
      '${field.enclosingElement.displayName}.${field.displayName}, declared '
      'by ${field.library.uri}, while generating ${child.displayName} in '
      '${child.library.uri}. $authorization$consumerPermission Import a '
      'qualifying boundary together with its generated companion, mark the '
      'field with @ForyField(ignore: true), set '
      '@ForyStruct(ignoreInheritedPrivateFields: true) on '
      '${child.displayName} to omit all inherited private storage, or use a '
      'custom serializer.',
      element: field,
    );
  }

  String? _companionReferenceFromChild(
    LibraryElement childLibrary,
    InterfaceElement boundary,
    String helperName,
  ) {
    Element? localCollision = childLibrary.getClass(helperName);
    localCollision ??= childLibrary.getMixin(helperName);
    localCollision ??= childLibrary.getEnum(helperName);
    localCollision ??= childLibrary.getExtension(helperName);
    localCollision ??= childLibrary.getExtensionType(helperName);
    localCollision ??= childLibrary.getTypeAlias(helperName);
    localCollision ??= childLibrary.getTopLevelFunction(helperName);
    localCollision ??= childLibrary.getTopLevelVariable(helperName);
    localCollision ??= childLibrary.getGetter(helperName);
    localCollision ??= childLibrary.getSetter(helperName);

    final validPrefixes = <String?>{};
    final ambiguousPrefixes = <String?>{};
    var blockedByLocalCollision = false;
    final importsByPrefix = <String?, List<LibraryImport>>{};
    for (final import in childLibrary.firstFragment.libraryImports) {
      final prefix = import.prefix?.element.displayName;
      final normalizedPrefix = prefix == null || prefix.isEmpty ? null : prefix;
      if (normalizedPrefix == helperName) {
        localCollision ??= import.prefix!.element;
      }
      if (import.prefix?.isDeferred == true) {
        continue;
      }
      var imports = importsByPrefix[normalizedPrefix];
      if (imports == null) {
        imports = <LibraryImport>[];
        importsByPrefix[normalizedPrefix] = imports;
      }
      imports.add(import);
    }
    for (final MapEntry(key: prefix, value: imports)
        in importsByPrefix.entries) {
      final resolvedBoundary =
          prefix == null
              ? childLibrary.firstFragment.scope
                  .lookup(boundary.displayName)
                  .getter
              : imports.first.prefix!.element.scope
                  .lookup(boundary.displayName)
                  .getter;
      if (resolvedBoundary == null ||
          !identical(resolvedBoundary.baseElement, boundary.baseElement)) {
        continue;
      }
      final helperOwners = <LibraryElement>{};
      for (final import in imports) {
        final importedLibrary = import.importedLibrary;
        if (importedLibrary == null) {
          continue;
        }
        if (!_combinatorsAllow(import.combinators, helperName)) {
          continue;
        }
        helperOwners.addAll(
          _companionOwners(importedLibrary, helperName, <LibraryElement>{}),
        );
      }
      if (prefix == null &&
          localCollision != null &&
          localCollision.library != boundary.library &&
          helperOwners.contains(boundary.library)) {
        blockedByLocalCollision = true;
        continue;
      }
      if (helperOwners.contains(boundary.library) && helperOwners.length > 1) {
        ambiguousPrefixes.add(prefix);
        continue;
      }
      if (helperOwners.length == 1 && helperOwners.single == boundary.library) {
        validPrefixes.add(prefix);
      }
    }
    if (validPrefixes.isEmpty) {
      if (blockedByLocalCollision) {
        throw InvalidGenerationSourceError(
          'Generated companion name $helperName from ${boundary.library.uri} '
          'collides with ${localCollision!.displayName} in '
          '${childLibrary.uri}.',
          element: boundary,
        );
      }
      if (ambiguousPrefixes.isNotEmpty) {
        final names = <String>[];
        for (final prefix in ambiguousPrefixes) {
          names.add(prefix ?? '<unprefixed>');
        }
        names.sort();
        throw InvalidGenerationSourceError(
          'Generated companion $helperName is ambiguous in import namespace '
          '${names.join(', ')}.',
          element: boundary,
        );
      }
      return null;
    }
    if (validPrefixes.contains(null)) {
      return helperName;
    }
    String? prefix;
    for (final candidate in validPrefixes) {
      if (candidate != null &&
          (prefix == null || candidate.compareTo(prefix) < 0)) {
        prefix = candidate;
      }
    }
    return prefix == null ? helperName : '$prefix.$helperName';
  }

  Set<LibraryElement> _companionOwners(
    LibraryElement current,
    String name,
    Set<LibraryElement> visiting,
  ) {
    if (!visiting.add(current)) {
      return const <LibraryElement>{};
    }
    final owners = <LibraryElement>{};
    if (_generatesCompanion(current, name)) {
      visiting.remove(current);
      return <LibraryElement>{current};
    }
    final existing = current.publicNamespace.definedNames2[name];
    final existingLibrary = existing?.library;
    if (existingLibrary == current) {
      visiting.remove(current);
      return <LibraryElement>{current};
    }
    if (existingLibrary != null) {
      owners.add(existingLibrary);
    }
    for (final export in current.firstFragment.libraryExports) {
      final exported = export.exportedLibrary;
      if (exported == null || !_combinatorsAllow(export.combinators, name)) {
        continue;
      }
      owners.addAll(_companionOwners(exported, name, visiting));
    }
    visiting.remove(current);
    return owners;
  }

  bool _generatesCompanion(LibraryElement library, String helperName) {
    const suffix = 'ForyFieldAccess';
    if (!helperName.startsWith(r'$') || !helperName.endsWith(suffix)) {
      return false;
    }
    final boundaryName = helperName.substring(
      1,
      helperName.length - suffix.length,
    );
    final boundary =
        library.getClass(boundaryName) ?? library.getMixin(boundaryName);
    if (boundary == null ||
        boundary.isPrivate ||
        !_foryStructChecker.hasAnnotationOf(boundary)) {
      return false;
    }
    final options = _structOptions(boundary);
    return options.target == null && options.exposePrivateFields;
  }

  bool _combinatorsAllow(List<NamespaceCombinator> combinators, String name) {
    var visible = true;
    for (final combinator in combinators) {
      if (combinator is ShowElementCombinator) {
        visible = visible && combinator.shownNames.contains(name);
      } else if (combinator is HideElementCombinator) {
        visible = visible && !combinator.hiddenNames.contains(name);
      }
    }
    return visible;
  }

  Future<_PrivateAccessCompanionSpec> _analyzePrivateAccessCompanion(
    InterfaceElement boundary,
    BuildStep buildStep,
  ) async {
    if (boundary.isPrivate) {
      throw InvalidGenerationSourceError(
        'ForyStruct.exposePrivateFields requires a public hierarchy boundary; '
        '${boundary.displayName} is private.',
        element: boundary,
      );
    }
    if (boundary is ClassElement &&
        !boundary.isExtendableOutside &&
        !boundary.isMixableOutside) {
      throw InvalidGenerationSourceError(
        '${boundary.displayName} cannot be extended or mixed in outside '
        '${boundary.library.uri}, so it cannot expose inherited private '
        'state to consumer subclasses.',
        element: boundary,
      );
    }
    final boundaryType = boundary.thisType;
    final hierarchy = _discoverHierarchyStorage(boundaryType, boundary);
    final fields = <_DiscoveredField>[];
    for (final field in hierarchy.fields) {
      if (field.declaration.isPrivate &&
          field.declaration.library == boundary.library &&
          !_isIgnored(field.declaration)) {
        fields.add(field);
      }
    }
    if (fields.isEmpty) {
      throw InvalidGenerationSourceError(
        'ForyStruct.exposePrivateFields on ${boundary.displayName} has no '
        'non-ignored private storage owned by ${boundary.library.uri} to '
        'expose.',
        element: boundary,
      );
    }

    final helperName = _privateAccessHelperName(boundary);
    final existing = boundary.library.publicNamespace.definedNames2[helperName];
    if (existing != null) {
      final existingAsset = await buildStep.resolver.assetIdForElement(
        existing,
      );
      if (!buildStep.allowedOutputs.contains(existingAsset)) {
        throw InvalidGenerationSourceError(
          'Generated companion name $helperName collides with an existing '
          'top-level declaration in ${boundary.library.uri}.',
          element: boundary,
        );
      }
    }

    final typeParameters = boundary.typeParameters.toSet();
    final receiverType = _companionBoundaryType(boundary);
    final methodTypeParameters = _companionMethodTypeParameters(boundary);
    if (boundary.library != _sourceLibrary) {
      throw StateError(
        'Private-field companion boundary ${boundary.displayName} is not '
        'owned by the current input library.',
      );
    }
    if (boundary.library.uri.scheme == 'file') {
      final canonicalUris =
          _canonicalLibraryUris ??= Map<LibraryElement, String>.identity();
      canonicalUris[boundary.library] = buildStep.inputId.uri.toString();
    }
    final methods = <_PrivateAccessMethodSpec>[];
    final identitiesByDigest = <String, String>{};
    for (final discovered in fields) {
      final field = discovered.declaration;
      final effectiveType = _effectiveFieldType(discovered);
      _validateExactFieldAccess(
        targetType: boundaryType,
        lookupLibrary: boundary.library,
        discovered: discovered,
        effectiveType: effectiveType,
        hierarchy: hierarchy,
        requireSetter: !field.isFinal,
        child: boundary,
      );
      final identity = _privateFieldIdentity(field);
      final digest = _privateFieldDigest(identity);
      final previous = identitiesByDigest[digest];
      if (previous != null && previous != identity) {
        throw InvalidGenerationSourceError(
          'Private-field access digest collision between $previous and '
          '$identity.',
          element: field,
        );
      }
      identitiesByDigest[digest] = identity;
      methods.add(
        _PrivateAccessMethodSpec(
          fieldName: field.displayName,
          fieldType: _companionTypeCode(
            effectiveType,
            boundary,
            allowedTypeParameters: typeParameters,
          ),
          getterName: '\$g$digest',
          setterName: field.isFinal ? null : '\$s$digest',
        ),
      );
    }
    methods.sort((left, right) => left.getterName.compareTo(right.getterName));
    return _PrivateAccessCompanionSpec(
      name: helperName,
      receiverType: receiverType,
      methodTypeParameters: methodTypeParameters,
      methods: methods,
    );
  }

  String _companionBoundaryType(InterfaceElement boundary) {
    if (boundary.typeParameters.isEmpty) {
      return boundary.displayName;
    }
    final arguments = <String>[];
    for (final parameter in boundary.typeParameters) {
      arguments.add(parameter.displayName);
    }
    return '${boundary.displayName}<${arguments.join(', ')}>';
  }

  String _companionMethodTypeParameters(InterfaceElement boundary) {
    if (boundary.typeParameters.isEmpty) {
      return '';
    }
    final allowed = boundary.typeParameters.toSet();
    final parameters = <String>[];
    for (final parameter in boundary.typeParameters) {
      final bound = parameter.bound;
      parameters.add(
        bound == null
            ? parameter.displayName
            : '${parameter.displayName} extends '
                '${_companionTypeCode(bound, boundary, allowedTypeParameters: allowed)}',
      );
    }
    return '<${parameters.join(', ')}>';
  }

  String _companionTypeCode(
    DartType type,
    InterfaceElement boundary, {
    required Set<TypeParameterElement> allowedTypeParameters,
    bool requireNamespace = true,
    bool useAlias = true,
  }) {
    if (type is DynamicType ||
        type is FunctionType ||
        type is InvalidType ||
        type.isDartCoreFunction) {
      throw InvalidGenerationSourceError(
        'Private-field companion for ${boundary.displayName} cannot expose '
        '${type.getDisplayString()}. Companion signatures must be exact, '
        'public, and non-callback.',
        element: boundary,
      );
    }
    if (type is RecordType || type.isDartCoreRecord || type.isDartCoreNull) {
      throw InvalidGenerationSourceError(
        'Private-field companion for ${boundary.displayName} cannot expose '
        '${type.getDisplayString()} because ordinary ForyStruct fields do '
        'not support that type.',
        element: boundary,
      );
    }
    final nullable =
        type.nullabilitySuffix == NullabilitySuffix.question ? '?' : '';
    final alias = type.alias;
    if (useAlias && alias != null && alias.element.isPublic) {
      final base = _companionTypeName(
        alias.element,
        boundary,
        requireNamespace: requireNamespace,
      );
      final arguments = _companionTypeArguments(
        alias.typeArguments,
        boundary,
        allowedTypeParameters: allowedTypeParameters,
        requireNamespace: requireNamespace,
        useAlias: true,
      );
      // A public alias supplies the emitted name, but it cannot conceal a
      // private nominal type or callback in its underlying signature.
      _companionTypeCode(
        type,
        boundary,
        allowedTypeParameters: allowedTypeParameters,
        requireNamespace: false,
        useAlias: false,
      );
      return arguments.isEmpty
          ? '$base$nullable'
          : '$base<$arguments>$nullable';
    }
    if (type is InterfaceType) {
      final element = type.element;
      final base = _companionTypeName(
        element,
        boundary,
        requireNamespace: requireNamespace,
      );
      final arguments = _companionTypeArguments(
        type.typeArguments,
        boundary,
        allowedTypeParameters: allowedTypeParameters,
        requireNamespace: requireNamespace,
        useAlias: useAlias,
      );
      return arguments.isEmpty
          ? '$base$nullable'
          : '$base<$arguments>$nullable';
    }
    if (type is TypeParameterType) {
      if (!allowedTypeParameters.contains(type.element)) {
        throw InvalidGenerationSourceError(
          'Private-field companion for ${boundary.displayName} contains '
          'unbound type parameter ${type.element.displayName}.',
          element: boundary,
        );
      }
      return '${type.element.displayName}$nullable';
    }
    throw InvalidGenerationSourceError(
      'Private-field companion for ${boundary.displayName} cannot render '
      '${type.getDisplayString()} as an exact public Dart type.',
      element: boundary,
    );
  }

  String _companionTypeArguments(
    List<DartType> arguments,
    InterfaceElement boundary, {
    required Set<TypeParameterElement> allowedTypeParameters,
    required bool requireNamespace,
    required bool useAlias,
  }) {
    if (arguments.isEmpty) {
      return '';
    }
    final rendered = <String>[];
    for (final argument in arguments) {
      rendered.add(
        _companionTypeCode(
          argument,
          boundary,
          allowedTypeParameters: allowedTypeParameters,
          requireNamespace: requireNamespace,
          useAlias: useAlias,
        ),
      );
    }
    return rendered.join(', ');
  }

  String _companionTypeName(
    Element element,
    InterfaceElement boundary, {
    required bool requireNamespace,
  }) {
    if (element.isPrivate) {
      throw InvalidGenerationSourceError(
        'Private-field companion for ${boundary.displayName} would expose '
        'private nominal type ${element.displayName}.',
        element: boundary,
      );
    }
    final library = element.library;
    if (library != null &&
        library != boundary.library &&
        !library.isDartCore &&
        requireNamespace &&
        !_hasImportReference(element)) {
      throw InvalidGenerationSourceError(
        'Private-field companion for ${boundary.displayName} uses public type '
        '${element.displayName}, but that exact type is not nameable from '
        '${boundary.library.uri}.',
        element: boundary,
      );
    }
    if (element.library == boundary.library ||
        element.library?.isDartCore == true) {
      return element.displayName;
    }
    final prefix = _importPrefixFor(element);
    return prefix == null
        ? element.displayName
        : '$prefix.${element.displayName}';
  }

  String _privateAccessHelperName(InterfaceElement boundary) =>
      '\$${boundary.displayName}ForyFieldAccess';

  String _privateFieldIdentity(FieldElement field) =>
      '${_canonicalLibraryUri(field.library)}\u0000'
      '${field.enclosingElement.displayName}\u0000${field.displayName}';

  String _privateFieldDigest(String identity) {
    var hash = BigInt.parse('cbf29ce484222325', radix: 16);
    final prime = BigInt.parse('100000001b3', radix: 16);
    final mask = (BigInt.one << 64) - BigInt.one;
    for (final byte in utf8.encode(identity)) {
      hash = ((hash ^ BigInt.from(byte)) * prime) & mask;
    }
    return hash.toRadixString(16).padLeft(16, '0');
  }

  String _canonicalLibraryUri(LibraryElement library) {
    final uri = library.uri;
    if (uri.scheme != 'file') {
      return uri.toString();
    }
    final canonical = _canonicalLibraryUris?[library];
    if (canonical == null) {
      throw StateError(
        'Canonical asset URI was not prepared for ${library.uri}.',
      );
    }
    return canonical;
  }

  void _writePrivateAccessCompanion(
    StringBuffer output,
    _PrivateAccessCompanionSpec companion,
  ) {
    output
      ..writeln('/// @nodoc')
      ..writeln('abstract final class ${companion.name} {');
    for (final method in companion.methods) {
      output
        ..writeln("  @pragma('vm:prefer-inline')")
        ..writeln(
          '  static ${method.fieldType} ${method.getterName}'
          '${companion.methodTypeParameters}('
          '${companion.receiverType} value) => value.${method.fieldName};',
        )
        ..writeln();
      final setterName = method.setterName;
      if (setterName != null) {
        output
          ..writeln("  @pragma('vm:prefer-inline')")
          ..writeln(
            '  static void $setterName${companion.methodTypeParameters}(',
          )
          ..writeln('    ${companion.receiverType} value,')
          ..writeln('    ${method.fieldType} fieldValue,')
          ..writeln('  ) {')
          ..writeln('    value.${method.fieldName} = fieldValue;')
          ..writeln('  }')
          ..writeln();
      }
    }
    output
      ..writeln('}')
      ..writeln();
  }

  void _validateExactFieldAccess({
    required InterfaceType targetType,
    required LibraryElement lookupLibrary,
    required _DiscoveredField discovered,
    required DartType effectiveType,
    required _HierarchyStorage hierarchy,
    required bool requireSetter,
    required InterfaceElement child,
  }) {
    final field = discovered.declaration;
    _DiscoveredField? laterApplication;
    for (
      var index = discovered.storageIndex + 1;
      index < hierarchy.fields.length;
      index += 1
    ) {
      final candidate = hierarchy.fields[index];
      if (identical(candidate.declaration.baseElement, field.baseElement)) {
        laterApplication = candidate;
        break;
      }
    }
    if (laterApplication != null) {
      throw InvalidGenerationSourceError(
        'Fory cannot address storage field '
        '${field.enclosingElement.displayName}.${field.displayName} in '
        '${child.displayName}: the same field declaration is applied more '
        'than once and this slot is hidden by a later application.',
        element: field,
      );
    }

    final getter = targetType.lookUpGetter(
      field.displayName,
      lookupLibrary,
      concrete: true,
    );
    final getterVariable = getter?.variable;
    if (getter == null ||
        getterVariable is! FieldElement ||
        !identical(getterVariable.baseElement, field.baseElement) ||
        getter.returnType != effectiveType) {
      throw InvalidGenerationSourceError(
        'Fory discovered storage field '
        '${field.enclosingElement.displayName}.${field.displayName}, but '
        '${child.displayName} does not expose that exact slot through its '
        'effective getter. A later field or accessor hides the storage.',
        element: field,
      );
    }
    if (!requireSetter) {
      return;
    }
    final setter = targetType.lookUpSetter(
      field.displayName,
      lookupLibrary,
      concrete: true,
    );
    final setterVariable = setter?.variable;
    final parameters = setter?.formalParameters;
    if (setter == null ||
        setterVariable is! FieldElement ||
        !identical(setterVariable.baseElement, field.baseElement) ||
        parameters == null ||
        parameters.length != 1 ||
        parameters.single.type != effectiveType) {
      throw InvalidGenerationSourceError(
        'Mutable storage field ${field.enclosingElement.displayName}.'
        '${field.displayName} on ${child.displayName} does not expose an '
        'exact setter for ${_typeCodeString(effectiveType)}.',
        element: field,
      );
    }
  }

  void _validateTypeVisibleFrom(
    DartType type,
    LibraryElement library, {
    required Element errorElement,
    required String context,
  }) {
    void validateElement(Element element, {required bool requireNamespace}) {
      final elementLibrary = element.library;
      if (elementLibrary == null ||
          elementLibrary == library ||
          elementLibrary.isDartCore) {
        return;
      }
      if (element.isPrivate ||
          (requireNamespace && !_hasImportReference(element))) {
        throw InvalidGenerationSourceError(
          '$context uses type ${element.displayName}, which is not nameable '
          'from ${library.uri}. Import the exact public type or ignore the '
          'field explicitly.',
          element: errorElement,
        );
      }
    }

    void validate(
      DartType current, {
      bool requireNamespace = true,
      bool useAlias = true,
    }) {
      if (current is InvalidType) {
        throw InvalidGenerationSourceError(
          '$context has an invalid or unresolved Dart type.',
          element: errorElement,
        );
      }
      final alias = current.alias;
      if (useAlias && alias != null) {
        final aliasElement = alias.element;
        final aliasVisible =
            aliasElement.library == library ||
            (aliasElement.isPublic && _hasImportReference(aliasElement));
        if (aliasVisible) {
          validateElement(aliasElement, requireNamespace: requireNamespace);
          for (final argument in alias.typeArguments) {
            validate(argument);
          }
          // The visible alias is sufficient for generated source. Continue
          // only to reject private nominal types hidden under that alias.
          validate(current, requireNamespace: false, useAlias: false);
          return;
        }
      }
      if (current is InterfaceType) {
        validateElement(current.element, requireNamespace: requireNamespace);
        for (final argument in current.typeArguments) {
          validate(
            argument,
            requireNamespace: requireNamespace,
            useAlias: useAlias,
          );
        }
        return;
      }
      if (current is TypeParameterType) {
        throw InvalidGenerationSourceError(
          '$context contains unresolved type parameter '
          '${current.element.displayName}.',
          element: errorElement,
        );
      }
      if (current is FunctionType) {
        validate(
          current.returnType,
          requireNamespace: requireNamespace,
          useAlias: useAlias,
        );
        for (final parameter in current.formalParameters) {
          validate(
            parameter.type,
            requireNamespace: requireNamespace,
            useAlias: useAlias,
          );
        }
        return;
      }
      if (current is RecordType) {
        for (final field in current.positionalFields) {
          validate(
            field.type,
            requireNamespace: requireNamespace,
            useAlias: useAlias,
          );
        }
        for (final field in current.namedFields) {
          validate(
            field.type,
            requireNamespace: requireNamespace,
            useAlias: useAlias,
          );
        }
      }
    }

    validate(type);
  }

  void _validateWireIdentities(
    ClassElement declaration,
    List<_GeneratedFieldSpec> fields,
  ) {
    final ids = <int, _GeneratedFieldSpec>{};
    final names = <String, _GeneratedFieldSpec>{};
    for (final field in fields) {
      final id = field.id;
      if (id != null) {
        final previous = ids[id];
        if (previous != null) {
          throw InvalidGenerationSourceError(
            'Fory struct ${declaration.displayName} has duplicate field id '
            '$id on ${previous.name} and ${field.name}.',
            element: field.declaration,
          );
        }
        ids[id] = field;
      } else {
        final wireName = field.wireName!;
        final previous = names[wireName];
        if (previous != null) {
          throw InvalidGenerationSourceError(
            'Fory struct ${declaration.displayName} has duplicate canonical '
            'field name $wireName on ${previous.name} and ${field.name}. '
            'Assign distinct numeric ids or ignore one field explicitly.',
            element: field.declaration,
          );
        }
        names[wireName] = field;
      }
    }
  }

  _GeneratedFieldSpec _analyzeField(
    ClassElement declaration,
    InterfaceType targetType,
    String targetTypeLiteral,
    FieldElement field,
    String codegenName,
  ) {
    final getter = targetType.lookUpGetter(
      field.displayName,
      declaration.library,
    );
    if (getter == null || getter.isStatic) {
      throw InvalidGenerationSourceError(
        'Fory struct declaration ${declaration.displayName} targets '
        '$targetTypeLiteral, which must expose an accessible '
        'instance getter named ${field.displayName}. Expose the getter or use '
        'a custom serializer.',
        element: field,
      );
    }
    if (getter.returnType != field.type) {
      throw InvalidGenerationSourceError(
        'Getter ${field.displayName} on target '
        '$targetTypeLiteral has type '
        '${_typeCodeString(getter.returnType)}, but serializer declaration '
        '${declaration.displayName}.${field.displayName} has type '
        '${_typeCodeString(field.type)}. The types must match exactly.',
        element: field,
      );
    }
    final setter = targetType.lookUpSetter(
      field.displayName,
      declaration.library,
    );
    if (setter != null) {
      final parameters = setter.formalParameters;
      if (parameters.length != 1 || parameters.single.type != field.type) {
        final setterType =
            parameters.length == 1
                ? _typeCodeString(parameters.single.type)
                : 'an invalid parameter list';
        throw InvalidGenerationSourceError(
          'Setter ${field.displayName} on target '
          '$targetTypeLiteral accepts $setterType, but '
          'serializer declaration '
          '${declaration.displayName}.${field.displayName} has type '
          '${_typeCodeString(field.type)}. Getter, setter, and declaration '
          'types must match exactly.',
          element: field,
        );
      }
    }

    return _createGeneratedFieldSpec(
      annotationField: field,
      effectiveType: field.type,
      codegenName: codegenName,
      writable: setter != null,
      access: _FieldAccessPlan.direct(field.displayName),
    );
  }

  _GeneratedFieldSpec _createGeneratedFieldSpec({
    required FieldElement annotationField,
    required DartType effectiveType,
    required String codegenName,
    required bool writable,
    required _FieldAccessPlan access,
  }) {
    final annotation = _fieldAnnotationOf(annotationField);
    final reader = annotation == null ? null : ConstantReader(annotation);
    final idValue = reader?.peek('id');
    final nullableValue = reader?.peek('nullable');
    final dynamicValue = reader?.peek('dynamic');
    final rawFieldId =
        idValue == null || idValue.isNull ? null : idValue.intValue;
    if (rawFieldId != null && rawFieldId < 0) {
      throw InvalidGenerationSourceError(
        'Fory field id must be non-negative.',
        element: annotationField,
      );
    }
    final nullable =
        nullableValue == null || nullableValue.isNull
            ? _isNullable(effectiveType)
            : nullableValue.boolValue;
    final dynamic =
        dynamicValue == null || dynamicValue.isNull
            ? _autoDynamic(effectiveType)
            : dynamicValue.boolValue;
    final ref = reader?.peek('ref')?.boolValue ?? false;
    final typeSpec = _analyzeTypeSpecAnnotation(annotationField, reader);
    return _GeneratedFieldSpec(
      name: annotationField.displayName,
      type: effectiveType,
      displayType: _typeCodeString(effectiveType),
      wireName:
          rawFieldId == null ? _toSnakeCase(annotationField.displayName) : null,
      id: rawFieldId,
      writable: writable,
      codegenName: codegenName,
      declaration: annotationField.baseElement,
      access: access,
      fieldType: _fieldTypeForType(
        effectiveType,
        nullable: nullable,
        ref: ref,
        dynamic: dynamic,
        typeSpec: typeSpec,
        errorElement: annotationField,
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
        return _fieldTypeForArrayListCarrier(errorElement);
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
    _validateScalarTypeOverride(type, typeId, dynamic, typeSpec, errorElement);
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

  _ConstructionModel _buildExternalConstructionModel(
    ClassElement declaration,
    String targetTypeLiteral,
    ConstructorElement constructor,
    String? constructorName,
    List<_GeneratedFieldSpec> fields,
  ) {
    final hasZeroArgConstructor = constructor.formalParameters.every(
      (parameter) => parameter.isOptional,
    );
    if (hasZeroArgConstructor && fields.every((field) => field.writable)) {
      return _ConstructionModel.mutable(constructorName: constructorName);
    }

    final fieldByName = <String, _GeneratedFieldSpec>{
      for (final field in fields) field.name: field,
    };
    final arguments = <_ConstructorArgumentSpec>[];
    final constructorFields = <_GeneratedFieldSpec>{};
    var omittedOptionalPositional = false;
    for (final parameter in constructor.formalParameters) {
      final parameterName = parameter.displayName;
      final field = fieldByName[parameterName];
      if (field == null) {
        if (parameter.isRequiredNamed || parameter.isRequiredPositional) {
          throw InvalidGenerationSourceError(
            'Required constructor parameter $parameterName on '
            '$targetTypeLiteral does not match a field in serializer '
            'declaration ${declaration.displayName}. Add an exact matching '
            'schema field or select another constructor.',
            element: declaration,
          );
        }
        if (parameter.isOptionalPositional) {
          omittedOptionalPositional = true;
        }
        continue;
      }
      if (parameter.isPositional && omittedOptionalPositional) {
        if (!field.writable) {
          throw InvalidGenerationSourceError(
            'Constructor ${_constructorReference(targetTypeLiteral, constructorName)} '
            'cannot map positional field $parameterName after an omitted '
            'optional positional parameter, and the target has no matching '
            'setter. Add the earlier schema field, expose a setter, or select '
            'another constructor.',
            element: declaration,
          );
        }
        continue;
      }
      if (parameter.type != field.type) {
        throw InvalidGenerationSourceError(
          'Constructor parameter $parameterName on $targetTypeLiteral has type '
          '${_typeCodeString(parameter.type)}, but serializer declaration '
          '${declaration.displayName}.$parameterName has type '
          '${_typeCodeString(field.type)}. The types must match exactly.',
          element: declaration,
        );
      }
      constructorFields.add(field);
      arguments.add(
        _ConstructorArgumentSpec(
          field: field,
          parameterName: parameterName,
          named: parameter.isNamed,
        ),
      );
    }

    for (final field in fields) {
      if (!constructorFields.contains(field) && !field.writable) {
        throw InvalidGenerationSourceError(
          'Field ${declaration.displayName}.${field.name} is not consumed by '
          '${_constructorReference(targetTypeLiteral, constructorName)} and '
          'target $targetTypeLiteral has no accessible exact-type setter. '
          'Expose a matching setter, map the field through the constructor, or '
          'use a custom serializer.',
          element: declaration,
        );
      }
    }

    final postConstructionFields = Set<_GeneratedFieldSpec>.identity();
    for (final field in fields) {
      if (!constructorFields.contains(field)) {
        postConstructionFields.add(field);
      }
    }

    return _ConstructionModel.constructor(
      constructorName: constructorName,
      arguments: arguments,
      postConstructionFields: postConstructionFields,
    );
  }

  void _validateConstructorSelfReference({
    required ClassElement declaration,
    required InterfaceType targetType,
    required String targetTypeLiteral,
    required List<_GeneratedFieldSpec> fields,
    required _ConstructionModel constructionModel,
  }) {
    if (constructionModel.mode != _ConstructorMode.constructor) {
      return;
    }
    // Constructor arguments are read before the target can be published, so a
    // tracked path through carrier metadata cannot resolve back to this target.
    _GeneratedFieldSpec? selfRefField;
    for (final field in fields) {
      if (_hasTrackedTargetReference(field.type, field.fieldType, targetType)) {
        selfRefField = field;
        break;
      }
    }
    if (selfRefField == null) {
      return;
    }
    final fieldOwner = selfRefField.declaration.enclosingElement.displayName;
    throw InvalidGenerationSourceError(
      'Constructor-based generated serializers cannot bind '
      'reference-tracked self paths before construction. Use a writable '
      'zero-parameter generative constructor for $targetTypeLiteral '
      'or a custom serializer. Offending field: '
      '$fieldOwner.${selfRefField.name}.',
      element: declaration,
    );
  }

  String _constructorReference(
    String targetTypeLiteral,
    String? constructorName,
  ) =>
      constructorName == null
          ? targetTypeLiteral
          : '$targetTypeLiteral.$constructorName';

  void _writeEnum(StringBuffer output, _GeneratedEnumSpec enumSpec) {
    final serializerClassName = '_${enumSpec.name}ForySerializer';
    final writeExpression =
        enumSpec.usesRawValue
            ? 'context.writeVarUint32(value.rawValue);'
            : 'context.writeVarUint32(value.index);';
    final readExpression =
        enumSpec.usesRawValue
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
    final schemaName = '_${_toCamelCase(structSpec.name)}ForySchema';
    final hasRuntimeFastPath = structSpec.fields.any(
      (field) => !_usesDirectGeneratedBasicFastPath(field),
    );
    final writeNeedsFieldDescriptors = structSpec.fields.any(
      _writeUsesFieldDescriptor,
    );
    final writeUsesBuffer = structSpec.fields.any(
      _directGeneratedBasicWriteNeedsBuffer,
    );
    final readUsesBuffer = structSpec.fields.any(
      _directGeneratedBasicReadNeedsBuffer,
    );
    final directPrimitiveRuns = _directGeneratedPrimitiveRuns(
      structSpec.fields,
    );
    final directPrimitiveRunByStart = <int, _DirectGeneratedPrimitiveRun>{
      for (final run in directPrimitiveRuns) run.start: run,
    };
    final directPrimitiveRunByEnd = <int, _DirectGeneratedPrimitiveRun>{
      for (final run in directPrimitiveRuns) run.end: run,
    };
    final directPrimitiveRunStartByIndex = <int, int>{
      for (final run in directPrimitiveRuns)
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
        'final GeneratedStructSchema<${structSpec.targetTypeLiteral}> $schemaName = GeneratedStructSchema<${structSpec.targetTypeLiteral}>(',
      );
    output
      ..writeln('  type: ${structSpec.targetTypeLiteral},')
      ..writeln('  serializerFactory: _${structSpec.name}ForySerializer.new,')
      ..writeln('  evolving: ${structSpec.evolving},')
      ..writeln(
        '  needsRootRef: ${_structNeedsEarlyReadReference(structSpec)},',
      )
      ..writeln(
        '  usesNestedTypeDefinitions: ${_structUsesNestedTypeDefinitions(structSpec)},',
      )
      ..writeln(
        '  readDataAlwaysAdvances: ${_structReadDataAlwaysAdvances(structSpec)},',
      );
    output
      ..writeln('  fields: $metadataListName,')
      ..writeln(');')
      ..writeln()
      ..writeln(
        'final class $serializerClassName extends Serializer<${structSpec.targetTypeLiteral}> implements GeneratedStructSerializer<${structSpec.targetTypeLiteral}> {',
      )
      ..writeln('  List<GeneratedStructFieldDescriptor>? _fieldDescriptors;')
      ..writeln()
      ..writeln('  $serializerClassName();')
      ..writeln();
    output
      ..writeln(
        '  List<GeneratedStructFieldDescriptor> _writeFields(WriteContext context) {',
      )
      ..writeln(
        '    return _fieldDescriptors ??= buildGeneratedStructFieldDescriptors(',
      )
      ..writeln('      context.typeResolver,')
      ..writeln('      $schemaName,')
      ..writeln('    );')
      ..writeln('  }')
      ..writeln()
      ..writeln(
        '  List<GeneratedStructFieldDescriptor> _readFields(ReadContext context) {',
      )
      ..writeln(
        '    return _fieldDescriptors ??= buildGeneratedStructFieldDescriptors(',
      )
      ..writeln('      context.typeResolver,')
      ..writeln('      $schemaName,')
      ..writeln('    );')
      ..writeln('  }')
      ..writeln('  @override')
      ..writeln(
        '  void write(WriteContext context, ${structSpec.targetTypeLiteral} value) {',
      );
    if (writeUsesBuffer) {
      output.writeln('      final buffer = context.buffer;');
    }
    if (writeNeedsFieldDescriptors) {
      output.writeln('      final fields = _writeFields(context);');
    }
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      final field = structSpec.fields[index];
      final directPrimitiveRun = directPrimitiveRunByStart[index];
      if (directPrimitiveRun != null) {
        _writeDirectGeneratedWriteRunStart(
          output,
          structSpec.fields,
          directPrimitiveRun,
          '      ',
        );
      }
      if (_usesReservedGeneratedFastPath(field)) {
        _writeDirectGeneratedBufferWriteStatement(
          output,
          field,
          directPrimitiveRunStartByIndex[index]!,
          index,
          field.access.read('value'),
          '      ',
        );
      } else if (_usesDirectGeneratedBasicFastPath(field)) {
        output.writeln(
          '      ${_directGeneratedWriteStatement(field, field.access.read('value'))};',
        );
      } else if (_usesDirectGeneratedTypedContainerWriteFastPath(field)) {
        output.writeln(
          '      ${_directGeneratedTypedContainerWriteStatement(field, index, field.access.read('value'))};',
        );
      } else {
        _writeGeneratedDescriptorValue(
          output,
          field,
          index,
          field.access.read('value'),
          '      ',
        );
      }
      final directPrimitiveEndRun = directPrimitiveRunByEnd[index];
      if (directPrimitiveEndRun != null) {
        _writeDirectGeneratedWriteRunEnd(
          output,
          directPrimitiveEndRun,
          '      ',
        );
      }
    }
    output
      ..writeln('  }')
      ..writeln()
      ..writeln('  @override')
      ..writeln(
        '  ${structSpec.targetTypeLiteral} read(ReadContext context) {',
      );
    final graphObjectBytes = _graphObjectBytes(structSpec);

    switch (structSpec.constructionModel.mode) {
      case _ConstructorMode.mutable:
        output.writeln('    context.reserveGraphMemory($graphObjectBytes);');
        output.writeln(
          '    final value = ${_constructorReference(structSpec.targetTypeLiteral, structSpec.constructionModel.constructorName)}();',
        );
        if (_structNeedsEarlyReadReference(structSpec)) {
          output
            ..writeln('    if (context.hasPreservedRefId) {')
            ..writeln('      context.reference(value);')
            ..writeln('    }');
        }
        if (readUsesBuffer) {
          output.writeln('      final buffer = context.buffer;');
        }
        if (hasRuntimeFastPath) {
          output.writeln('      final fields = _readFields(context);');
        }
        for (var index = 0; index < structSpec.fields.length; index += 1) {
          final field = structSpec.fields[index];
          final readerFunctionName = field.readerFunctionName(structSpec.name);
          final directPrimitiveRun = directPrimitiveRunByStart[index];
          if (directPrimitiveRun != null) {
            _writeDirectGeneratedReadRunStart(
              output,
              structSpec.fields,
              directPrimitiveRun,
              '      ',
            );
          }
          _writeMutableFieldRead(
            output,
            structSpec,
            field,
            index,
            directPrimitiveRunStartByIndex[index],
            readerFunctionName,
            '      ',
          );
          final directPrimitiveEndRun = directPrimitiveRunByEnd[index];
          if (directPrimitiveEndRun != null) {
            _writeDirectGeneratedReadRunEnd(
              output,
              directPrimitiveEndRun,
              '      ',
            );
          }
        }
        output.writeln('    return value;');
      case _ConstructorMode.constructor:
        if (readUsesBuffer) {
          output.writeln('      final buffer = context.buffer;');
        }
        if (hasRuntimeFastPath) {
          output.writeln('      final fields = _readFields(context);');
        }
        for (var index = 0; index < structSpec.fields.length; index += 1) {
          final field = structSpec.fields[index];
          final readerFunctionName = field.readerFunctionName(structSpec.name);
          final directPrimitiveRun = directPrimitiveRunByStart[index];
          if (directPrimitiveRun != null) {
            _writeDirectGeneratedReadRunStart(
              output,
              structSpec.fields,
              directPrimitiveRun,
              '      ',
            );
          }
          if (_usesReservedGeneratedFastPath(field)) {
            _writeDirectGeneratedBufferReadStatement(
              output,
              field,
              directPrimitiveRunStartByIndex[index]!,
              index,
              'final ${field.displayType} ${field.localName}',
              '      ',
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
              '      final ${field.displayType} ${field.localName} = $readerFunctionName(readGeneratedStructDescriptorValue(context, fields[$index]));',
            );
          }
          final directPrimitiveEndRun = directPrimitiveRunByEnd[index];
          if (directPrimitiveEndRun != null) {
            _writeDirectGeneratedReadRunEnd(
              output,
              directPrimitiveEndRun,
              '      ',
            );
          }
        }
        final constructorInvocation = _constructorInvocation(structSpec);
        output.writeln('      context.reserveGraphMemory($graphObjectBytes);');
        output.writeln('      final value = $constructorInvocation;');
        for (final field in structSpec.fields) {
          if (structSpec.constructionModel.postConstructionFields.contains(
            field,
          )) {
            output.writeln(
              '      ${field.access.write('value', field.localName)};',
            );
          }
        }
        output.writeln('    return value;');
    }

    output.writeln('  }');
    _writeCompatibleStructReadMethod(
      output,
      structSpec,
      readUsesBuffer: readUsesBuffer,
      hasRuntimeFastPath: hasRuntimeFastPath,
    );
    output
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

  void _writeMutableFieldRead(
    StringBuffer output,
    _GeneratedStructSpec structSpec,
    _GeneratedFieldSpec field,
    int index,
    int? primitiveRunStart,
    String readerFunctionName,
    String indent,
  ) {
    final currentValue = field.access.read('value');
    if (_usesReservedGeneratedFastPath(field)) {
      final target =
          field.access.isDirect
              ? currentValue
              : 'final ${field.displayType} ${field.localName}';
      _writeDirectGeneratedBufferReadStatement(
        output,
        field,
        primitiveRunStart!,
        index,
        target,
        indent,
      );
      if (!field.access.isDirect) {
        output.writeln(
          '$indent${field.access.write('value', field.localName)};',
        );
      }
      return;
    }

    late final String decoded;
    if (_usesDirectGeneratedBasicFastPath(field)) {
      decoded = _directGeneratedReadExpression(field);
    } else if (_usesDirectGeneratedTypedContainerReadFastPath(field)) {
      decoded = _directGeneratedTypedContainerReadExpression(
        structSpec.name,
        field,
        'fields[$index]',
      );
    } else if (_usesDirectGeneratedStructFieldFastPath(field)) {
      decoded =
          '$readerFunctionName(readGeneratedStructDirectValue(context, fields[$index]), $currentValue)';
    } else if (_usesDirectGeneratedDeclaredReadFastPath(field)) {
      decoded =
          '$readerFunctionName(readGeneratedStructDeclaredValue(context, fields[$index]), $currentValue)';
    } else {
      decoded =
          '$readerFunctionName(readGeneratedStructDescriptorValue(context, fields[$index], $currentValue), $currentValue)';
    }
    output.writeln('$indent${field.access.write('value', decoded)};');
  }

  void _writeCompatibleStructReadMethod(
    StringBuffer output,
    _GeneratedStructSpec structSpec, {
    required bool readUsesBuffer,
    required bool hasRuntimeFastPath,
  }) {
    final splitFallback = structSpec.fields.length >= 16;
    output
      ..writeln()
      ..writeln('  @override')
      ..writeln(
        '  ${structSpec.targetTypeLiteral} readCompatibleStruct(ReadContext context, CompatibleStructReadLayout layout) {',
      );
    switch (structSpec.constructionModel.mode) {
      case _ConstructorMode.mutable:
        output.writeln(
          '    context.reserveGraphMemory(${_graphObjectBytes(structSpec)});',
        );
        output.writeln(
          '    final value = ${_constructorReference(structSpec.targetTypeLiteral, structSpec.constructionModel.constructorName)}();',
        );
        if (_structNeedsEarlyReadReference(structSpec)) {
          output
            ..writeln('    if (context.hasPreservedRefId) {')
            ..writeln('      context.reference(value);')
            ..writeln('    }');
        }
        if (!splitFallback && readUsesBuffer) {
          output.writeln('    final buffer = context.buffer;');
        }
        if (!splitFallback && hasRuntimeFastPath) {
          output.writeln('    final fields = _readFields(context);');
        }
        if (splitFallback) {
          output.writeln(
            '    return _readCompatibleStructFallback(context, layout, value);',
          );
        } else {
          _writeMutableCompatibleSwitchFallback(output, structSpec);
        }
      case _ConstructorMode.constructor:
        if (!splitFallback && readUsesBuffer) {
          output.writeln('    final buffer = context.buffer;');
        }
        if (!splitFallback && hasRuntimeFastPath) {
          output.writeln('    final fields = _readFields(context);');
        }
        if (splitFallback) {
          output.writeln(
            '    return _readCompatibleStructFallback(context, layout);',
          );
        } else {
          _writeCtorCompatSwitch(output, structSpec);
        }
    }
    output.writeln('  }');
    if (splitFallback) {
      _writeCompatibleStructFallbackMethod(
        output,
        structSpec,
        readUsesBuffer: readUsesBuffer,
        hasRuntimeFastPath: hasRuntimeFastPath,
      );
    }
  }

  void _writeCompatibleStructFallbackMethod(
    StringBuffer output,
    _GeneratedStructSpec structSpec, {
    required bool readUsesBuffer,
    required bool hasRuntimeFastPath,
  }) {
    final mutable =
        structSpec.constructionModel.mode == _ConstructorMode.mutable;
    final valueParameter =
        mutable ? ', ${structSpec.targetTypeLiteral} value' : '';
    output
      ..writeln()
      ..writeln("  @pragma('vm:never-inline')")
      ..writeln(
        '  ${structSpec.targetTypeLiteral} _readCompatibleStructFallback(ReadContext context, CompatibleStructReadLayout layout$valueParameter) {',
      );
    if (readUsesBuffer) {
      output.writeln('    final buffer = context.buffer;');
    }
    if (hasRuntimeFastPath) {
      output.writeln('    final fields = _readFields(context);');
    }
    if (mutable) {
      _writeMutableCompatibleSwitchFallback(output, structSpec);
    } else {
      _writeCtorCompatSwitch(output, structSpec);
    }
    output.writeln('  }');
  }

  void _writeMutableCompatibleSwitchFallback(
    StringBuffer output,
    _GeneratedStructSpec structSpec,
  ) {
    output
      ..writeln(
        '    for (var index = 0; index < layout.fieldCount; index += 1) {',
      )
      ..writeln('      final field = layout.fieldAt(index);')
      ..writeln('      switch (field.matchedId) {')
      ..writeln('        case -1:')
      ..writeln('          skipGeneratedCompatibleStructField(context, field);')
      ..writeln('          break;');
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      final field = structSpec.fields[index];
      output.writeln('        case ${index * 2}:');
      _writeMutableExactCompatRead(
        output,
        structSpec,
        field,
        index,
        '          ',
      );
      output
        ..writeln('          break;')
        ..writeln('        case ${index * 2 + 1}:');
      _writeMutableCompatConversionRead(
        output,
        structSpec,
        field,
        index,
        'field',
        '          ',
      );
      output.writeln('          break;');
    }
    output
      ..writeln('        default:')
      ..writeln(
        "          throw StateError('Compatible matched id is out of range for ${structSpec.targetTypeLiteral}.');",
      )
      ..writeln('      }')
      ..writeln('    }')
      ..writeln('    return value;');
  }

  void _writeMutableExactCompatRead(
    StringBuffer output,
    _GeneratedStructSpec structSpec,
    _GeneratedFieldSpec field,
    int index,
    String indent,
  ) {
    final currentValue = field.access.read('value');
    if (field.access.isDirect) {
      _writeExactCompatRead(
        output,
        structSpec,
        field,
        index,
        currentValue,
        currentValue,
        indent,
      );
      return;
    }
    final decoded = '${field.localName}Exact';
    _writeExactCompatRead(
      output,
      structSpec,
      field,
      index,
      'final ${field.displayType} $decoded',
      currentValue,
      indent,
    );
    output.writeln('$indent${field.access.write('value', decoded)};');
  }

  void _writeMutableCompatConversionRead(
    StringBuffer output,
    _GeneratedStructSpec structSpec,
    _GeneratedFieldSpec field,
    int index,
    String readField,
    String indent,
  ) {
    final currentValue = field.access.read('value');
    if (field.access.isDirect) {
      _writeCompatConversionRead(
        output,
        structSpec,
        field,
        index,
        currentValue,
        currentValue,
        readField,
        indent,
      );
      return;
    }
    final decoded = '${field.localName}Converted';
    _writeCompatConversionRead(
      output,
      structSpec,
      field,
      index,
      'final ${field.displayType} $decoded',
      currentValue,
      readField,
      indent,
    );
    output.writeln('$indent${field.access.write('value', decoded)};');
  }

  void _writeCtorCompatSwitch(
    StringBuffer output,
    _GeneratedStructSpec structSpec,
  ) {
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      final field = structSpec.fields[index];
      output
        ..writeln('    late final ${field.displayType} ${field.localName};')
        ..writeln('    var hasField$index = false;');
    }
    output
      ..writeln(
        '    for (var index = 0; index < layout.fieldCount; index += 1) {',
      )
      ..writeln('      final field = layout.fieldAt(index);')
      ..writeln('      switch (field.matchedId) {')
      ..writeln('        case -1:')
      ..writeln('          skipGeneratedCompatibleStructField(context, field);')
      ..writeln('          break;');
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      final field = structSpec.fields[index];
      output.writeln('        case ${index * 2}:');
      _writeExactCompatRead(
        output,
        structSpec,
        field,
        index,
        field.localName,
        null,
        '          ',
      );
      output
        ..writeln('          hasField$index = true;')
        ..writeln('          break;')
        ..writeln('        case ${index * 2 + 1}:');
      _writeCompatConversionRead(
        output,
        structSpec,
        field,
        index,
        field.localName,
        null,
        'field',
        '          ',
      );
      output
        ..writeln('          hasField$index = true;')
        ..writeln('          break;');
    }
    output
      ..writeln('        default:')
      ..writeln(
        "          throw StateError('Compatible matched id is out of range for ${structSpec.targetTypeLiteral}.');",
      )
      ..writeln('      }')
      ..writeln('    }');
    for (var index = 0; index < structSpec.fields.length; index += 1) {
      final field = structSpec.fields[index];
      final readerFunctionName = field.readerFunctionName(structSpec.name);
      output
        ..writeln('    if (!hasField$index) {')
        ..writeln('      ${field.localName} = $readerFunctionName(null);')
        ..writeln('    }');
    }
    final constructorInvocation = _constructorInvocation(structSpec);
    output.writeln(
      '    context.reserveGraphMemory(${_graphObjectBytes(structSpec)});',
    );
    output.writeln('    final value = $constructorInvocation;');
    for (final field in structSpec.fields) {
      if (structSpec.constructionModel.postConstructionFields.contains(field)) {
        output.writeln('    ${field.access.write('value', field.localName)};');
      }
    }
    output.writeln('    return value;');
  }

  void _writeCompatConversionRead(
    StringBuffer output,
    _GeneratedStructSpec structSpec,
    _GeneratedFieldSpec field,
    int index,
    String target,
    String? fallback,
    String readField,
    String indent,
  ) {
    final directScalarRead = _directCompatibleScalarRead(field);
    if (directScalarRead != null) {
      final scalarRead = 'scalarRead$index';
      final fallbackArg = fallback == null ? '' : ', $fallback';
      output.writeln('${indent}final $scalarRead = $readField.scalarRead!;');
      output.writeln(
        '$indent$target = ${directScalarRead.method}(context, $scalarRead$fallbackArg);',
      );
      return;
    }
    final readerFunctionName = field.readerFunctionName(structSpec.name);
    output.writeln(
      '$indent$target = ${_readerCall(readerFunctionName, 'readGeneratedCompatibleStructField(context, $readField)', fallback)};',
    );
  }

  void _writeExactCompatRead(
    StringBuffer output,
    _GeneratedStructSpec structSpec,
    _GeneratedFieldSpec field,
    int index,
    String target,
    String? fallback,
    String indent,
  ) {
    final readerFunctionName = field.readerFunctionName(structSpec.name);
    if (_usesDirectGeneratedBasicFastPath(field)) {
      output.writeln(
        '$indent$target = ${_directGeneratedReadExpression(field)};',
      );
      return;
    }
    if (_usesDirectGeneratedTypedContainerReadFastPath(field)) {
      output.writeln(
        '$indent$target = ${_directGeneratedTypedContainerReadExpression(structSpec.name, field, 'fields[$index]')};',
      );
      return;
    }
    if (_usesDirectGeneratedStructFieldFastPath(field)) {
      output.writeln(
        '$indent$target = ${_readerCall(readerFunctionName, 'readGeneratedStructDirectValue(context, fields[$index])', fallback)};',
      );
      return;
    }
    if (_usesDirectGeneratedDeclaredReadFastPath(field)) {
      output.writeln(
        '$indent$target = ${_readerCall(readerFunctionName, 'readGeneratedStructDeclaredValue(context, fields[$index])', fallback)};',
      );
      return;
    }
    final valueExpression =
        fallback == null
            ? 'readGeneratedStructDescriptorValue(context, fields[$index])'
            : 'readGeneratedStructDescriptorValue(context, fields[$index], $fallback)';
    output.writeln('$indent$target = $readerFunctionName($valueExpression);');
  }

  _DirectCompatibleScalarRead? _directCompatibleScalarRead(
    _GeneratedFieldSpec field,
  ) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        _isGeneratedDynamicField(field)) {
      return null;
    }
    final typeLiteral = _typeLiteral(_withoutNullability(field.type));
    if (typeLiteral == 'int' &&
        (field.fieldType.typeId == TypeIds.int8 ||
            field.fieldType.typeId == TypeIds.int16 ||
            field.fieldType.typeId == TypeIds.int32 ||
            field.fieldType.typeId == TypeIds.varInt32 ||
            field.fieldType.typeId == TypeIds.int64 ||
            field.fieldType.typeId == TypeIds.varInt64 ||
            field.fieldType.typeId == TypeIds.taggedInt64 ||
            field.fieldType.typeId == TypeIds.uint8 ||
            field.fieldType.typeId == TypeIds.uint16 ||
            field.fieldType.typeId == TypeIds.uint32 ||
            field.fieldType.typeId == TypeIds.varUint32)) {
      return const _DirectCompatibleScalarRead('readGenCompatScalarAsInt');
    }
    if (typeLiteral == 'double' &&
        (field.fieldType.typeId == TypeIds.float32 ||
            field.fieldType.typeId == TypeIds.float64)) {
      return const _DirectCompatibleScalarRead('readGenCompatScalarAsDouble');
    }
    if (typeLiteral == 'Int64' && _isSigned64TypeId(field.fieldType.typeId)) {
      return const _DirectCompatibleScalarRead('readGenCompatScalarAsInt64');
    }
    if (typeLiteral == 'Uint64' &&
        _isUnsigned64TypeId(field.fieldType.typeId)) {
      return const _DirectCompatibleScalarRead('readGenCompatScalarAsUint64');
    }
    if (typeLiteral == 'Float32' && field.fieldType.typeId == TypeIds.float32) {
      return const _DirectCompatibleScalarRead('readGenCompatScalarAsFloat32');
    }
    return null;
  }

  String _readerCall(
    String functionName,
    String valueExpression,
    String? fallback,
  ) {
    if (fallback == null) {
      return '$functionName($valueExpression)';
    }
    return '$functionName($valueExpression, $fallback)';
  }

  void _writeGeneratedSupport(
    StringBuffer output, {
    required List<_GeneratedEnumSpec> enumSpecs,
    required List<_GeneratedStructSpec> structSpecs,
    required String generatedApiName,
    required bool emitRegistrationHelper,
  }) {
    for (final enumSpec in enumSpecs) {
      final schemaName = '_${_toCamelCase(enumSpec.name)}ForySchema';
      output
        ..writeln(
          'final GeneratedEnumSchema $schemaName = GeneratedEnumSchema(',
        )
        ..writeln('  type: ${enumSpec.name},')
        ..writeln('  serializerFactory: _${enumSpec.name}ForySerializer.new,')
        ..writeln(');')
        ..writeln();
    }
    if (enumSpecs.isNotEmpty && structSpecs.isNotEmpty) {
      output.writeln();
    }

    if (!emitRegistrationHelper) {
      return;
    }

    output
      ..writeln('abstract final class $generatedApiName {')
      ..writeln('  static void register(')
      ..writeln('    Fory fory,')
      ..writeln('    Type type, {')
      ..writeln('    int? id,')
      ..writeln('    String? name,')
      ..writeln('  }) {');

    for (final enumSpec in enumSpecs) {
      final schemaName = '_${_toCamelCase(enumSpec.name)}ForySchema';
      output.writeln('  if (type == ${enumSpec.name}) {');
      output.writeln('    registerGeneratedEnum(');
      output.writeln('      fory,');
      output.writeln('      $schemaName,');
      output.writeln('      id: id,');
      output.writeln('      name: name,');
      output.writeln('    );');
      output.writeln('    return;');
      output.writeln('  }');
    }
    for (final structSpec in structSpecs) {
      final schemaName = '_${_toCamelCase(structSpec.name)}ForySchema';
      output.writeln('  if (type == ${structSpec.targetTypeLiteral}) {');
      output.writeln('    registerGeneratedStruct(');
      output.writeln('      fory,');
      output.writeln('      $schemaName,');
      output.writeln('      id: id,');
      output.writeln('      name: name,');
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
    for (final argument in structSpec.constructionModel.arguments) {
      final field = argument.field;
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
    return '${_constructorReference(structSpec.targetTypeLiteral, structSpec.constructionModel.constructorName)}($arguments)';
  }

  int _graphObjectBytes(_GeneratedStructSpec structSpec) =>
      _structObjectOwnerBytes + structSpec.storageFieldCount * _referenceBytes;

  int _externalGraphFieldCount(
    ClassElement declaration,
    InterfaceType targetType,
    List<FieldElement> declarationFields,
  ) {
    final publicFields = <FieldElement>[];
    // Analyzer keeps each class's direct mixins separate from its superclass,
    // so this visits every applied mixin storage slot exactly once.
    for (
      InterfaceType? current = targetType;
      current != null;
      current = current.superclass
    ) {
      _addPublicStoredFields(publicFields, current);
      for (final mixin in current.mixins) {
        _addPublicStoredFields(publicFields, mixin);
      }
    }

    final claimed = <int>{};
    for (final declarationField in declarationFields) {
      final getter = targetType.lookUpGetter(
        declarationField.displayName,
        declaration.library,
      );
      final variable = getter?.variable;
      if (variable is! FieldElement || !_isPublicStoredField(variable)) {
        continue;
      }
      for (var index = 0; index < publicFields.length; index += 1) {
        if (!claimed.contains(index) &&
            identical(publicFields[index].baseElement, variable.baseElement)) {
          claimed.add(index);
          break;
        }
      }
    }
    return publicFields.length + declarationFields.length - claimed.length;
  }

  void _addPublicStoredFields(List<FieldElement> fields, InterfaceType type) {
    fields.addAll(type.element.fields.where(_isPublicStoredField));
  }

  bool _isStoredInstanceField(FieldElement field) {
    final baseField = field.baseElement;
    return !field.isStatic &&
        !field.isAbstract &&
        !field.isExternal &&
        (identical(baseField.nonSynthetic, baseField) ||
            baseField.nonSynthetic is FieldFormalParameterElement);
  }

  bool _isPublicStoredField(FieldElement field) =>
      field.isPublic && _isStoredInstanceField(field);

  bool _isIgnored(FieldElement field) {
    final annotation = _fieldAnnotationOf(field);
    final annotationType = annotation?.type;
    if (annotation == null ||
        annotationType == null ||
        !_foryFieldChecker.isExactlyType(annotationType)) {
      return false;
    }
    return ConstantReader(annotation).peek('ignore')?.boolValue ?? false;
  }

  String _fieldInfoLiteral(_GeneratedFieldSpec field) {
    final identifier = field.id?.toString() ?? field.wireName!;
    return '''
  GeneratedFieldInfo(
    name: '${field.name}',
    identifier: '$identifier',
    id: ${field.id},
    fieldType: ${_fieldTypeLiteral(field.fieldType)},
  ),''';
  }

  String _fieldTypeLiteral(_GeneratedFieldTypeSpec fieldType) {
    final argumentsLiteral =
        fieldType.arguments.isEmpty
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
      return '$valueExpression ?? $nullExpression';
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
        _isGeneratedDynamicField(field)) {
      return false;
    }
    return _isPrimitiveTypeId(field.fieldType.typeId) ||
        field.fieldType.typeId == TypeIds.string ||
        _isBuiltInTypeId(field.fieldType.typeId) ||
        field.fieldType.typeId == TypeIds.enumById;
  }

  bool _directGeneratedBasicWriteNeedsBuffer(_GeneratedFieldSpec field) {
    if (!_usesDirectGeneratedBasicFastPath(field)) {
      return false;
    }
    switch (field.fieldType.typeId) {
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
        return false;
      default:
        return true;
    }
  }

  bool _directGeneratedBasicReadNeedsBuffer(_GeneratedFieldSpec field) {
    if (!_usesDirectGeneratedBasicFastPath(field)) {
      return false;
    }
    switch (field.fieldType.typeId) {
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
        return false;
      default:
        return true;
    }
  }

  bool _usesDirectGeneratedDeclaredReadFastPath(_GeneratedFieldSpec field) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        _isGeneratedDynamicField(field)) {
      return false;
    }
    final typeId = field.fieldType.typeId;
    return typeId == TypeIds.ext || typeId == TypeIds.namedExt;
  }

  bool _usesDirectGeneratedStructFieldFastPath(_GeneratedFieldSpec field) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        _isGeneratedDynamicField(field)) {
      return false;
    }
    if (!_isGeneratedStructType(field.type)) {
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
        _isGeneratedDynamicField(field)) {
      return false;
    }
    if (_isBoolList(field.type)) {
      return false;
    }
    return field.fieldType.typeId == TypeIds.list ||
        field.fieldType.typeId == TypeIds.set ||
        field.fieldType.typeId == TypeIds.map;
  }

  bool _isGeneratedStructType(DartType type) {
    final element = _withoutNullability(type).element;
    if (element is! ClassElement ||
        !_foryStructChecker.hasAnnotationOf(element)) {
      return false;
    }
    final options = _structOptions(element);
    return options.target == null &&
        _ownsOrdinarySerializer(element) &&
        element.typeParameters.isEmpty;
  }

  bool _usesDirectGeneratedTypedContainerWriteFastPath(
    _GeneratedFieldSpec field,
  ) {
    if (field.fieldType.nullable ||
        field.fieldType.ref ||
        _isGeneratedDynamicField(field)) {
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
    if (elementFieldType.ref || _isGeneratedDynamicType(elementFieldType)) {
      return false;
    }
    final elementType = (field.type as InterfaceType).typeArguments.single;
    return !_isNullable(elementType);
  }

  bool _writeUsesFieldDescriptor(_GeneratedFieldSpec field) {
    if (_usesDirectGeneratedBasicFastPath(field)) {
      return false;
    }
    if (_usesDirectGeneratedTypedContainerWriteFastPath(field)) {
      return true;
    }
    return !_isGeneratedDynamicField(field);
  }

  bool _isGeneratedDynamicField(_GeneratedFieldSpec field) =>
      _isGeneratedDynamicType(field.fieldType);

  bool _isGeneratedDynamicType(_GeneratedFieldTypeSpec fieldType) =>
      fieldType.dynamic == true || fieldType.typeId == TypeIds.unknown;

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

  List<_DirectGeneratedPrimitiveRun> _directGeneratedPrimitiveRuns(
    List<_GeneratedFieldSpec> fields, {
    bool includeCoreInt64Varints = false,
  }) {
    final runs = <_DirectGeneratedPrimitiveRun>[];
    int? start;
    var bytes = 0;
    for (var index = 0; index < fields.length; index += 1) {
      final fieldBytes = _directGeneratedPrimitiveReservationBytes(
        fields[index],
        includeCoreInt64Varints: includeCoreInt64Varints,
      );
      if (fieldBytes == null) {
        if (start != null) {
          runs.add(_DirectGeneratedPrimitiveRun(start, index - 1, bytes));
          start = null;
          bytes = 0;
        }
        continue;
      }
      start ??= index;
      bytes += fieldBytes;
    }
    if (start != null) {
      runs.add(_DirectGeneratedPrimitiveRun(start, fields.length - 1, bytes));
    }
    return runs;
  }

  bool _usesReservedGeneratedFastPath(
    _GeneratedFieldSpec field, {
    bool includeCoreInt64Varints = false,
  }) {
    return _directGeneratedPrimitiveReservationBytes(
          field,
          includeCoreInt64Varints: includeCoreInt64Varints,
        ) !=
        null;
  }

  int? _directGeneratedPrimitiveReservationBytes(
    _GeneratedFieldSpec field, {
    bool includeCoreInt64Varints = false,
  }) {
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
      case TypeIds.float64:
        return 8;
      case TypeIds.varInt32:
      case TypeIds.varUint32:
        return 5;
      case TypeIds.varInt64:
      case TypeIds.varUint64:
        return includeCoreInt64Varints && field.type.isDartCoreInt ? 9 : null;
      default:
        return null;
    }
  }

  bool _directGeneratedRunUsesBytes(
    List<_GeneratedFieldSpec> fields,
    _DirectGeneratedPrimitiveRun run,
  ) {
    for (var index = run.start; index <= run.end; index += 1) {
      switch (fields[index].fieldType.typeId) {
        case TypeIds.boolType:
        case TypeIds.varInt32:
        case TypeIds.varInt64:
        case TypeIds.varUint32:
        case TypeIds.varUint64:
          return true;
      }
    }
    return false;
  }

  bool _directGeneratedRunUsesView(
    List<_GeneratedFieldSpec> fields,
    _DirectGeneratedPrimitiveRun run,
  ) {
    for (var index = run.start; index <= run.end; index += 1) {
      switch (fields[index].fieldType.typeId) {
        case TypeIds.boolType:
        case TypeIds.varInt32:
        case TypeIds.varInt64:
        case TypeIds.varUint32:
        case TypeIds.varUint64:
          break;
        default:
          return true;
      }
    }
    return false;
  }

  void _writeDirectGeneratedWriteRunStart(
    StringBuffer output,
    List<_GeneratedFieldSpec> fields,
    _DirectGeneratedPrimitiveRun run,
    String indent,
  ) {
    output.writeln(
      '${indent}var offset${run.start} = bufferReserveBytes(buffer, ${run.bytes});',
    );
    if (_directGeneratedRunUsesBytes(fields, run)) {
      output.writeln('${indent}final bytes${run.start} = bufferBytes(buffer);');
    }
    if (_directGeneratedRunUsesView(fields, run)) {
      output.writeln(
        '${indent}final view${run.start} = bufferByteData(buffer);',
      );
    }
  }

  void _writeDirectGeneratedReadRunStart(
    StringBuffer output,
    List<_GeneratedFieldSpec> fields,
    _DirectGeneratedPrimitiveRun run,
    String indent,
  ) {
    output.writeln(
      '${indent}var offset${run.start} = bufferReaderIndex(buffer);',
    );
    if (_directGeneratedRunUsesBytes(fields, run)) {
      output.writeln('${indent}final bytes${run.start} = bufferBytes(buffer);');
    }
    if (_directGeneratedRunUsesView(fields, run)) {
      output.writeln(
        '${indent}final view${run.start} = bufferByteData(buffer);',
      );
    }
  }

  void _writeDirectGeneratedWriteRunEnd(
    StringBuffer output,
    _DirectGeneratedPrimitiveRun run,
    String indent,
  ) {
    output.writeln(
      '${indent}bufferSetWriterIndex(buffer, offset${run.start});',
    );
  }

  void _writeDirectGeneratedReadRunEnd(
    StringBuffer output,
    _DirectGeneratedPrimitiveRun run,
    String indent,
  ) {
    output.writeln(
      '${indent}bufferSetReaderIndex(buffer, offset${run.start});',
    );
  }

  void _writeDirectGeneratedBufferWriteStatement(
    StringBuffer output,
    _GeneratedFieldSpec field,
    int runStart,
    int fieldIndex,
    String valueExpression,
    String indent,
  ) {
    final offset = 'offset$runStart';
    final bytes = 'bytes$runStart';
    final view = 'view$runStart';
    final scalar = _directGeneratedScalarExpression(field, valueExpression);
    switch (field.fieldType.typeId) {
      case TypeIds.boolType:
        output
          ..writeln('$indent$bytes[$offset] = $valueExpression ? 1 : 0;')
          ..writeln('$indent$offset += 1;');
      case TypeIds.int8:
        output
          ..writeln('$indent$view.setInt8($offset, $scalar);')
          ..writeln('$indent$offset += 1;');
      case TypeIds.uint8:
        output
          ..writeln('$indent$view.setUint8($offset, $scalar);')
          ..writeln('$indent$offset += 1;');
      case TypeIds.int16:
        output
          ..writeln(
            '$indent$view.setInt16($offset, $scalar, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.uint16:
        output
          ..writeln(
            '$indent$view.setUint16($offset, $scalar, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.int32:
        output
          ..writeln(
            '$indent$view.setInt32($offset, $scalar, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 4;');
      case TypeIds.uint32:
        output
          ..writeln(
            '$indent$view.setUint32($offset, $scalar, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 4;');
      case TypeIds.float16:
        output
          ..writeln(
            '$indent$view.setUint16($offset, toFloat16Bits($valueExpression), generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.bfloat16:
        output
          ..writeln(
            '$indent$view.setUint16($offset, toBfloat16Bits($valueExpression), generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.float32:
        output
          ..writeln(
            '$indent$view.setFloat32($offset, $scalar, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 4;');
      case TypeIds.float64:
        output
          ..writeln(
            '$indent$view.setFloat64($offset, $scalar, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 8;');
      case TypeIds.varInt32:
        final checked = 'value$fieldIndex';
        final remaining = 'remaining$fieldIndex';
        output
          ..writeln('$indent final $checked = $scalar;')
          ..writeln(
            '$indent var $remaining = (($checked << 1) ^ ($checked >> 31)).toUnsigned(32);',
          )
          ..writeln('$indent while ($remaining >= 0x80) {')
          ..writeln('$indent   $bytes[$offset] = ($remaining & 0x7f) | 0x80;')
          ..writeln('$indent   $offset += 1;')
          ..writeln('$indent   $remaining >>>= 7;')
          ..writeln('$indent }')
          ..writeln('$indent $bytes[$offset] = $remaining;')
          ..writeln('$indent $offset += 1;');
      case TypeIds.varUint32:
        final remaining = 'remaining$fieldIndex';
        output
          ..writeln('$indent var $remaining = $scalar;')
          ..writeln('$indent while ($remaining >= 0x80) {')
          ..writeln('$indent   $bytes[$offset] = ($remaining & 0x7f) | 0x80;')
          ..writeln('$indent   $offset += 1;')
          ..writeln('$indent   $remaining >>>= 7;')
          ..writeln('$indent }')
          ..writeln('$indent $bytes[$offset] = $remaining;')
          ..writeln('$indent $offset += 1;');
      default:
        throw StateError(
          'Unsupported generated direct buffer write fast path for ${field.name}.',
        );
    }
  }

  void _writeDirectGeneratedBufferReadStatement(
    StringBuffer output,
    _GeneratedFieldSpec field,
    int runStart,
    int fieldIndex,
    String target,
    String indent,
  ) {
    final offset = 'offset$runStart';
    final bytes = 'bytes$runStart';
    final view = 'view$runStart';
    switch (field.fieldType.typeId) {
      case TypeIds.boolType:
        output
          ..writeln('$indent$target = $bytes[$offset] != 0;')
          ..writeln('$indent$offset += 1;');
      case TypeIds.int8:
        output
          ..writeln('$indent$target = $view.getInt8($offset);')
          ..writeln('$indent$offset += 1;');
      case TypeIds.uint8:
        output
          ..writeln('$indent$target = $view.getUint8($offset);')
          ..writeln('$indent$offset += 1;');
      case TypeIds.int16:
        output
          ..writeln(
            '$indent$target = $view.getInt16($offset, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.uint16:
        output
          ..writeln(
            '$indent$target = $view.getUint16($offset, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.int32:
        output
          ..writeln(
            '$indent$target = $view.getInt32($offset, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 4;');
      case TypeIds.uint32:
        output
          ..writeln(
            '$indent$target = $view.getUint32($offset, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 4;');
      case TypeIds.float16:
        output
          ..writeln(
            '$indent$target = fromFloat16Bits($view.getUint16($offset, generatedLittleEndian));',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.bfloat16:
        output
          ..writeln(
            '$indent$target = fromBfloat16Bits($view.getUint16($offset, generatedLittleEndian));',
          )
          ..writeln('$indent$offset += 2;');
      case TypeIds.float32:
        final value = '$view.getFloat32($offset, generatedLittleEndian)';
        output
          ..writeln(
            field.type.isDartCoreDouble
                ? '$indent$target = $value;'
                : '$indent$target = Float32($value);',
          )
          ..writeln('$indent$offset += 4;');
      case TypeIds.float64:
        output
          ..writeln(
            '$indent$target = $view.getFloat64($offset, generatedLittleEndian);',
          )
          ..writeln('$indent$offset += 8;');
      case TypeIds.varInt32:
        final result = 'result$fieldIndex';
        _writeDirectGeneratedVarUint32Read(
          output,
          result,
          bytes,
          offset,
          indent,
        );
        output.writeln(
          '$indent$target = (($result >>> 1) ^ -($result & 1)).toSigned(32);',
        );
      case TypeIds.varInt64:
        if (!field.type.isDartCoreInt) {
          throw StateError(
            'Generated varint64 cursor read is only supported for Dart int fields.',
          );
        }
        final assignTarget = _writeGeneratedReadAssignmentTarget(
          output,
          target,
          indent,
        );
        final result = 'result$fieldIndex';
        output
          ..writeln('$indent if (generatedIsWeb) {')
          ..writeln('$indent   bufferSetReaderIndex(buffer, $offset);')
          ..writeln('$indent   $assignTarget = buffer.readVarInt64AsInt();')
          ..writeln('$indent   $offset = bufferReaderIndex(buffer);')
          ..writeln('$indent } else {');
        _writeDirectGeneratedVarUint64Read(
          output,
          result,
          bytes,
          offset,
          '$indent   ',
        );
        output
          ..writeln(
            '$indent   $assignTarget = ($result >>> 1) ^ -($result & 1);',
          )
          ..writeln('$indent }');
      case TypeIds.varUint32:
        final result = 'result$fieldIndex';
        _writeDirectGeneratedVarUint32Read(
          output,
          result,
          bytes,
          offset,
          indent,
        );
        output.writeln('$indent$target = $result;');
      case TypeIds.varUint64:
        if (!field.type.isDartCoreInt) {
          throw StateError(
            'Generated varuint64 cursor read is only supported for Dart int fields.',
          );
        }
        final assignTarget = _writeGeneratedReadAssignmentTarget(
          output,
          target,
          indent,
        );
        final result = 'result$fieldIndex';
        output
          ..writeln('$indent if (generatedIsWeb) {')
          ..writeln('$indent   bufferSetReaderIndex(buffer, $offset);')
          ..writeln('$indent   $assignTarget = buffer.readVarUint64().toInt();')
          ..writeln('$indent   $offset = bufferReaderIndex(buffer);')
          ..writeln('$indent } else {');
        _writeDirectGeneratedVarUint64Read(
          output,
          result,
          bytes,
          offset,
          '$indent   ',
        );
        output
          ..writeln('$indent   $assignTarget = $result;')
          ..writeln('$indent }');
      default:
        throw StateError(
          'Unsupported generated direct buffer read fast path for ${field.name}.',
        );
    }
  }

  String _writeGeneratedReadAssignmentTarget(
    StringBuffer output,
    String target,
    String indent,
  ) {
    if (!target.startsWith('final ')) {
      return target;
    }
    final nameStart = target.lastIndexOf(' ') + 1;
    final name = target.substring(nameStart);
    final typePrefix = target
        .substring(0, nameStart)
        .replaceFirst('final ', 'late final ');
    output.writeln('$indent$typePrefix$name;');
    return name;
  }

  void _writeDirectGeneratedVarUint32Read(
    StringBuffer output,
    String result,
    String bytes,
    String offset,
    String indent,
  ) {
    final shift = '${result}Shift';
    final byte = '${result}Byte';
    output
      ..writeln('$indent var $shift = 0;')
      ..writeln('$indent var $result = 0;')
      ..writeln('$indent while (true) {')
      ..writeln('$indent   final $byte = $bytes[$offset];')
      ..writeln('$indent   $offset += 1;')
      ..writeln('$indent   $result |= ($byte & 0x7f) << $shift;')
      ..writeln('$indent   if (($byte & 0x80) == 0) {')
      ..writeln('$indent     break;')
      ..writeln('$indent   }')
      ..writeln('$indent   $shift += 7;')
      ..writeln('$indent }');
  }

  void _writeDirectGeneratedVarUint64Read(
    StringBuffer output,
    String result,
    String bytes,
    String offset,
    String indent,
  ) {
    final shift = '${result}Shift';
    final byte = '${result}Byte';
    output
      ..writeln('$indent var $shift = 0;')
      ..writeln('$indent var $result = 0;')
      ..writeln('$indent while ($shift < 56) {')
      ..writeln('$indent   final $byte = $bytes[$offset];')
      ..writeln('$indent   $offset += 1;')
      ..writeln('$indent   $result |= ($byte & 0x7f) << $shift;')
      ..writeln('$indent   if (($byte & 0x80) == 0) {')
      ..writeln('$indent     break;')
      ..writeln('$indent   }')
      ..writeln('$indent   $shift += 7;')
      ..writeln('$indent }')
      ..writeln('$indent if ($shift == 56) {')
      ..writeln('$indent   final $byte = $bytes[$offset];')
      ..writeln('$indent   $offset += 1;')
      ..writeln('$indent   $result |= $byte << 56;')
      ..writeln('$indent }');
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
        return _enumReadExpression(field.type, 'buffer');
      default:
        throw StateError(
          'Unsupported generated direct read fast path for ${field.name}.',
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
          return 'generatedCheckedUint64Int($valueExpression)';
        default:
          return _checkedGeneratedScalarExpression(
            field.fieldType.typeId,
            valueExpression,
          );
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

  void _writeGeneratedDescriptorValue(
    StringBuffer output,
    _GeneratedFieldSpec field,
    int index,
    String valueExpression,
    String indent,
  ) {
    final value = 'field${index}Value';
    output.writeln('${indent}final $value = $valueExpression;');
    if (_isGeneratedDynamicField(field)) {
      _writeGeneratedDynamicValue(output, field, value, indent);
      return;
    }
    final descriptor = 'field$index';
    final fieldType = 'field${index}Type';
    output
      ..writeln('${indent}final $descriptor = fields[$index];')
      ..writeln('${indent}final $fieldType = $descriptor.fieldType;')
      ..writeln(
        '${indent}final field${index}Declared = $descriptor.declaredTypeInfo;',
      )
      ..writeln(
        '${indent}if (field${index}Declared != null && $descriptor.usesDeclaredType) {',
      );
    _writeGeneratedDeclaredValue(
      output,
      field,
      resolved: 'field${index}Declared',
      fieldType: fieldType,
      value: value,
      indent: '$indent  ',
    );
    output.writeln('$indent} else {');
    _writeGeneratedUndeclaredValue(
      output,
      field,
      fieldType: fieldType,
      value: value,
      indent: '$indent  ',
    );
    output.writeln('$indent}');
  }

  void _writeGeneratedDynamicValue(
    StringBuffer output,
    _GeneratedFieldSpec field,
    String valueExpression,
    String indent,
  ) {
    if (field.fieldType.ref) {
      output.writeln('${indent}context.writeRef($valueExpression);');
      return;
    }
    output
      ..writeln(
        '${indent}if (!context.refWriter.writeRefOrNull(context.buffer, $valueExpression, trackRef: false)) {',
      )
      ..writeln(
        '$indent  context.writeNonRef(${_nonNullObjectExpression(field, valueExpression)});',
      )
      ..writeln('$indent}');
  }

  void _writeGeneratedDeclaredValue(
    StringBuffer output,
    _GeneratedFieldSpec field, {
    required String resolved,
    required String fieldType,
    required String value,
    required String indent,
  }) {
    if (field.fieldType.nullable || field.fieldType.ref) {
      final trackRef = field.fieldType.ref ? '$resolved.supportsRef' : 'false';
      output
        ..writeln(
          '${indent}if (!context.refWriter.writeRefOrNull(context.buffer, $value, trackRef: $trackRef)) {',
        )
        ..writeln(
          '$indent  context.writeResolvedValue($resolved, $value as Object, $fieldType);',
        )
        ..writeln('$indent}');
      return;
    }
    output
      ..writeln('${indent}if ($value == null) {')
      ..writeln(
        "$indent  throw StateError('Field ${field.name} is not nullable.');",
      )
      ..writeln('$indent}')
      ..writeln(
        '${indent}context.writeResolvedValue($resolved, $value as Object, $fieldType);',
      );
  }

  void _writeGeneratedUndeclaredValue(
    StringBuffer output,
    _GeneratedFieldSpec field, {
    required String fieldType,
    required String value,
    required String indent,
  }) {
    if (field.fieldType.ref) {
      output.writeln('${indent}context.writeRef($value);');
      return;
    }
    if (field.fieldType.nullable) {
      output
        ..writeln(
          '${indent}if (!context.refWriter.writeRefOrNull(context.buffer, $value, trackRef: false)) {',
        )
        ..writeln('$indent  context.writeNonRef($value as Object);')
        ..writeln('$indent}');
      return;
    }
    output
      ..writeln('${indent}if ($value == null) {')
      ..writeln(
        "$indent  throw StateError('Field ${field.name} is not nullable.');",
      )
      ..writeln('$indent}')
      ..writeln(
        '${indent}final actualResolved = context.typeResolver.resolveValue($value as Object);',
      )
      ..writeln('${indent}context.writeTypeMetaValue(actualResolved);')
      ..writeln(
        '${indent}context.writeResolvedValue(actualResolved, $value, $fieldType);',
      );
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
      if (_withoutNullability(type).isDartCoreObject) {
        return '($fallbackExpression ?? (throw StateError(\'Received null for non-nullable $errorTarget.\')))';
      }
      return '($fallbackExpression != null ? $fallbackExpression as $displayType : (throw StateError(\'Received null for non-nullable $errorTarget.\')))';
    }
    return '(throw StateError(\'Received null for non-nullable $errorTarget.\'))';
  }

  String _nonNullObjectExpression(
    _GeneratedFieldSpec field,
    String valueExpression,
  ) {
    if (_withoutNullability(field.type).isDartCoreObject &&
        !_isNullable(field.type)) {
      return valueExpression;
    }
    return '$valueExpression as Object';
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
  ) => '_read$structName${field.capitalizedCodegenName}Element';

  String _containerKeyReaderFunctionName(
    String structName,
    _GeneratedFieldSpec field,
  ) => '_read$structName${field.capitalizedCodegenName}Key';

  String _containerValueReaderFunctionName(
    String structName,
    _GeneratedFieldSpec field,
  ) => '_read$structName${field.capitalizedCodegenName}Value';

  List<_GeneratedFieldSpec> _sortFields(List<_GeneratedFieldSpec> fields) {
    final primitiveFields = <_GeneratedFieldSpec>[];
    final boxedPrimitiveFields = <_GeneratedFieldSpec>[];
    final nonPrimitiveFields = <_GeneratedFieldSpec>[];

    for (final field in fields) {
      if (_isPrimitiveTypeId(field.fieldType.typeId)) {
        if (field.fieldType.nullable) {
          boxedPrimitiveFields.add(field);
        } else {
          primitiveFields.add(field);
        }
      } else {
        nonPrimitiveFields.add(field);
      }
    }

    primitiveFields.sort(_comparePrimitiveFields);
    boxedPrimitiveFields.sort(_comparePrimitiveFields);
    nonPrimitiveFields.sort(_compareOtherFields);

    return <_GeneratedFieldSpec>[
      ...primitiveFields,
      ...boxedPrimitiveFields,
      ...nonPrimitiveFields,
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
    final typeCompare = left.fieldType.typeId - right.fieldType.typeId;
    if (typeCompare != 0) {
      return typeCompare;
    }
    final keyCompare = _compareFieldIdentity(left, right);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return 0;
  }

  int _compareOtherFields(_GeneratedFieldSpec left, _GeneratedFieldSpec right) {
    final keyCompare = _compareFieldIdentity(left, right);
    if (keyCompare != 0) {
      return keyCompare;
    }
    return 0;
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
    if (leftId != null && leftId >= 0 && (rightId == null || rightId < 0)) {
      return -1;
    }
    if ((leftId == null || leftId < 0) && rightId != null && rightId >= 0) {
      return 1;
    }
    final keyCompare = left.wireName!.compareTo(right.wireName!);
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
    for (final metadata in field.metadata.annotations) {
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
      return _TypeSpecInfo(typeId: _arrayTypeIdForElementSpec(element, field));
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
        return _TypeSpecInfo(nullable: nullable, ref: ref, dynamic: dynamic);
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
    final encodingValue =
        encodingReader == null || encodingReader.isNull
            ? 'varint'
            : encodingReader.revive().accessor.split('.').last;
    return switch (encodingValue) {
      'fixed' => fixed,
      'varint' => varint,
      'tagged' when tagged != null => tagged,
      _ =>
        throw InvalidGenerationSourceError(
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
      _ =>
        throw InvalidGenerationSourceError(
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
      'double' =>
        typeId == TypeIds.float16 ||
            typeId == TypeIds.bfloat16 ||
            typeId == TypeIds.float32 ||
            typeId == TypeIds.float64,
      'String' => typeId == TypeIds.string,
      'Int64' => _isSigned64TypeId(typeId),
      'Uint64' => _isUnsigned64TypeId(typeId),
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
          // A declared union field gets its schema from the owning field TypeDef.
          // Root/dynamic Any paths still use TYPED_UNION or NAMED_UNION when
          // they need to identify the union independently.
          return TypeIds.union;
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
        method.formalParameters.length != 1 ||
        !method.formalParameters.single.type.isDartCoreInt) {
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

  String _enumReadExpression(DartType type, String contextExpression) {
    final typeDisplay = _typeReferenceLiteral(type);
    if (_enumUsesRawValue(type)) {
      return '$typeDisplay.fromRawValue($contextExpression.readVarUint32())';
    }
    return '$typeDisplay.values[$contextExpression.readVarUint32()]';
  }

  bool _sameType(DartType left, DartType right) =>
      _withoutNullability(left) == _withoutNullability(right);

  bool _hasTrackedTargetReference(
    DartType type,
    _GeneratedFieldTypeSpec fieldType,
    InterfaceType targetType,
  ) {
    if (fieldType.ref && _sameType(type, targetType)) {
      return true;
    }
    final nonNullable = _withoutNullability(type);
    if (nonNullable is! InterfaceType) {
      return false;
    }
    final typeArguments = nonNullable.typeArguments;
    final argumentCount =
        typeArguments.length < fieldType.arguments.length
            ? typeArguments.length
            : fieldType.arguments.length;
    for (var index = 0; index < argumentCount; index++) {
      if (_hasTrackedTargetReference(
        typeArguments[index],
        fieldType.arguments[index],
        targetType,
      )) {
        return true;
      }
    }
    return false;
  }

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
    if (type is DynamicType || type is InvalidType) {
      return 'Object';
    }
    // Rebuilding a nullable interface type drops its alias, so preserve the
    // visible alias before removing nullability from the underlying type.
    final alias = type.alias;
    if (alias != null &&
        (alias.element.library == _sourceLibrary ||
            (alias.element.isPublic && _hasImportReference(alias.element)))) {
      final aliasElement = alias.element;
      final prefix = _importPrefixFor(aliasElement);
      final elementName = aliasElement.displayName;
      final baseName = prefix == null ? elementName : '$prefix.$elementName';
      if (alias.typeArguments.isEmpty) {
        return baseName;
      }
      final typeArguments = alias.typeArguments.map(_typeCodeString).join(', ');
      return '$baseName<$typeArguments>';
    }
    final nonNullable = _withoutNullability(type);
    if (nonNullable is InterfaceType) {
      final element = nonNullable.element;
      final prefix = _importPrefixFor(element);
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

  String _toPascalCase(String value) =>
      value
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

  bool _structNeedsEarlyReadReference(_GeneratedStructSpec structSpec) {
    for (final field in structSpec.fields) {
      if (_fieldTypeNeedsEarlyReadReference(field.fieldType)) {
        return true;
      }
    }
    return false;
  }

  bool _fieldTypeNeedsEarlyReadReference(_GeneratedFieldTypeSpec fieldType) {
    if (fieldType.ref) {
      return true;
    }
    for (final argument in fieldType.arguments) {
      if (_fieldTypeNeedsEarlyReadReference(argument)) {
        return true;
      }
    }
    return false;
  }

  bool _structUsesNestedTypeDefinitions(_GeneratedStructSpec structSpec) {
    for (final field in structSpec.fields) {
      if (_fieldTypeUsesNestedTypeDefinitions(field.fieldType)) {
        return true;
      }
    }
    return false;
  }

  bool _structReadDataAlwaysAdvances(_GeneratedStructSpec structSpec) {
    for (final field in structSpec.fields) {
      if (field.fieldType.nullable ||
          field.fieldType.ref ||
          _isGeneratedDynamicField(field) ||
          _fieldReadAlwaysAdvances(field.fieldType)) {
        return true;
      }
    }
    return false;
  }

  bool _fieldReadAlwaysAdvances(_GeneratedFieldTypeSpec fieldType) {
    final typeId = fieldType.typeId;
    return _isPrimitiveTypeId(typeId) ||
        _isBuiltInTypeId(typeId) ||
        TypeIds.isContainer(typeId) ||
        typeId == TypeIds.enumById ||
        typeId == TypeIds.namedEnum ||
        typeId == TypeIds.union ||
        typeId == TypeIds.typedUnion ||
        typeId == TypeIds.namedUnion;
  }

  bool _fieldTypeUsesNestedTypeDefinitions(_GeneratedFieldTypeSpec fieldType) {
    if (_isGeneratedDynamicType(fieldType) ||
        TypeIds.isUserType(fieldType.typeId)) {
      return true;
    }
    for (final argument in fieldType.arguments) {
      if (_fieldTypeUsesNestedTypeDefinitions(argument)) {
        return true;
      }
    }
    return false;
  }
}

final class _GeneratedEnumSpec {
  final String name;
  final bool usesRawValue;

  const _GeneratedEnumSpec({required this.name, required this.usesRawValue});
}

final class _StructOptions {
  final bool evolving;
  final DartType? target;
  final String? constructorName;
  final bool exposePrivateFields;
  final bool ignoreInheritedPrivateFields;

  const _StructOptions({
    required this.evolving,
    required this.target,
    required this.constructorName,
    required this.exposePrivateFields,
    required this.ignoreInheritedPrivateFields,
  });
}

final class _HierarchyStorage {
  final List<_HierarchyLayer> layers;
  final List<_DiscoveredField> fields;

  const _HierarchyStorage({required this.layers, required this.fields});
}

final class _HierarchyLayer {
  final InterfaceType type;
  final int index;

  const _HierarchyLayer({required this.type, required this.index});
}

final class _DiscoveredField {
  final FieldElement declaration;
  final _HierarchyLayer layer;
  final int storageIndex;

  const _DiscoveredField({
    required this.declaration,
    required this.layer,
    required this.storageIndex,
  });
}

final class _PrivateAccessCompanionSpec {
  final String name;
  final String receiverType;
  final String methodTypeParameters;
  final List<_PrivateAccessMethodSpec> methods;

  const _PrivateAccessCompanionSpec({
    required this.name,
    required this.receiverType,
    required this.methodTypeParameters,
    required this.methods,
  });
}

final class _PrivateAccessMethodSpec {
  final String fieldName;
  final String fieldType;
  final String getterName;
  final String? setterName;

  const _PrivateAccessMethodSpec({
    required this.fieldName,
    required this.fieldType,
    required this.getterName,
    required this.setterName,
  });
}

final class _GeneratedStructSpec {
  final String name;
  final InterfaceType targetType;
  final String targetTypeLiteral;
  final bool evolving;
  final List<_GeneratedFieldSpec> fields;
  final int storageFieldCount;
  final _ConstructionModel constructionModel;

  const _GeneratedStructSpec({
    required this.name,
    required this.targetType,
    required this.targetTypeLiteral,
    required this.evolving,
    required this.fields,
    required this.storageFieldCount,
    required this.constructionModel,
  });
}

final class _DirectCompatibleScalarRead {
  final String method;

  const _DirectCompatibleScalarRead(this.method);
}

final class _GeneratedFieldSpec {
  final String name;
  final DartType type;
  final String displayType;
  final String? wireName;
  final int? id;
  final bool writable;
  final String codegenName;
  final FieldElement declaration;
  final _FieldAccessPlan access;
  final _GeneratedFieldTypeSpec fieldType;

  const _GeneratedFieldSpec({
    required this.name,
    required this.type,
    required this.displayType,
    required this.wireName,
    required this.id,
    required this.writable,
    required this.codegenName,
    required this.declaration,
    required this.access,
    required this.fieldType,
  });

  String readerFunctionName(String structName) {
    return '_read$structName$capitalizedCodegenName';
  }

  String get capitalizedCodegenName =>
      '${codegenName[0].toUpperCase()}${codegenName.substring(1)}';

  String get localName => '_${codegenName}Value';
}

final class _FieldAccessPlan {
  final String fieldName;
  final String? companion;
  final String? getter;
  final String? setter;

  const _FieldAccessPlan.direct(this.fieldName)
    : companion = null,
      getter = null,
      setter = null;

  const _FieldAccessPlan.companion({
    required this.fieldName,
    required this.companion,
    required this.getter,
    required this.setter,
  });

  bool get isDirect => companion == null;

  String read(String receiver) {
    final companion = this.companion;
    return companion == null
        ? '$receiver.$fieldName'
        : '$companion.${getter!}($receiver)';
  }

  String write(String receiver, String value) {
    final companion = this.companion;
    if (companion == null) {
      return '$receiver.$fieldName = $value';
    }
    return '$companion.${setter!}($receiver, $value)';
  }
}

final class _DirectGeneratedPrimitiveRun {
  final int start;
  final int end;
  final int bytes;

  const _DirectGeneratedPrimitiveRun(this.start, this.end, this.bytes);
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

final class _ConstructionModel {
  final _ConstructorMode mode;
  final String? constructorName;
  final List<_ConstructorArgumentSpec> arguments;
  final Set<_GeneratedFieldSpec> postConstructionFields;

  const _ConstructionModel.mutable({required this.constructorName})
    : mode = _ConstructorMode.mutable,
      arguments = const <_ConstructorArgumentSpec>[],
      postConstructionFields = const <_GeneratedFieldSpec>{};

  const _ConstructionModel.constructor({
    required this.constructorName,
    required this.arguments,
    required this.postConstructionFields,
  }) : mode = _ConstructorMode.constructor;
}

final class _ConstructorArgumentSpec {
  final _GeneratedFieldSpec field;
  final String parameterName;
  final bool named;

  const _ConstructorArgumentSpec({
    required this.field,
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
