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

part of 'fory_generator.dart';

final class _OrdinaryConstructorAnalyzer {
  final BuildStep buildStep;

  final Map<ConstructorElement, Future<AstNode?>> _astByConstructor =
      Map<ConstructorElement, Future<AstNode?>>.identity();
  final List<_ConstructorFrame> _frames = <_ConstructorFrame>[];

  _OrdinaryConstructorAnalyzer({required this.buildStep});

  Future<_ConstructionModel> build({
    required ClassElement declaration,
    required String targetTypeLiteral,
    required ConstructorElement constructor,
    required List<_GeneratedFieldSpec> fields,
  }) async {
    if (constructor.formalParameters.isEmpty) {
      var mutableConstruction = true;
      for (final field in fields) {
        if (!field.writable) {
          mutableConstruction = false;
          break;
        }
      }
      if (mutableConstruction) {
        return const _ConstructionModel.mutable(constructorName: null);
      }
    }

    for (final field in fields) {
      if (field.declaration.isFinal && field.declaration.hasInitializer) {
        throw InvalidGenerationSourceError(
          'Final field ${_fieldLabel(field)} has a declaration initializer '
          'and cannot receive its decoded value from '
          '$targetTypeLiteral(). Mark the field with '
          '@ForyField(ignore: true), remove the initializer and add an '
          'identity-preserving constructor path, or use a custom serializer.',
          element: field.declaration,
        );
      }
    }

    _frames.clear();
    final active = <_ConstructorKey>{};
    final rootFrame = await _buildFrame(
      constructor,
      fields,
      declaration,
      active,
    );
    final reachableByRoot = <_ParameterNode, Set<_GeneratedFieldSpec>>{
      for (final root in rootFrame.parameters) root: _reachableFields(root),
    };
    final bindingByRoot = <_ParameterNode, _GeneratedFieldSpec>{};
    final rootByArgumentField =
        Map<_GeneratedFieldSpec, _ParameterNode>.identity();

    for (final field in fields) {
      if (field.writable) {
        continue;
      }
      _ParameterNode? root;
      for (final candidate in rootFrame.parameters) {
        if (!reachableByRoot[candidate]!.contains(field)) {
          continue;
        }
        if (root != null) {
          throw InvalidGenerationSourceError(
            'Final field ${_fieldLabel(field)} is reached by multiple '
            'parameters of $targetTypeLiteral(). A serialized final field '
            'must have exactly one identity-preserving constructor root.',
            element: field.declaration,
          );
        }
        root = candidate;
      }
      if (root == null) {
        _throwMissingFinalFlow(declaration, targetTypeLiteral, field);
      }
      final existing = bindingByRoot[root];
      if (existing != null && !identical(existing, field)) {
        throw InvalidGenerationSourceError(
          'Constructor parameter ${root.parameter.displayName} on '
          '$targetTypeLiteral initializes multiple non-writable fields '
          '${_fieldLabel(existing)} and ${_fieldLabel(field)}. One decoded '
          'constructor argument may restore at most one final field.',
          element: declaration,
        );
      }
      bindingByRoot[root] = field;
      rootByArgumentField[field] = root;
    }

    for (final root in rootFrame.parameters) {
      if (bindingByRoot.containsKey(root)) {
        continue;
      }
      _GeneratedFieldSpec? candidate;
      List<String>? ambiguousLabels;
      for (final field in reachableByRoot[root]!) {
        if (!field.writable) {
          continue;
        }
        if (candidate == null) {
          candidate = field;
          continue;
        }
        ambiguousLabels ??= <String>[_fieldLabel(candidate)];
        ambiguousLabels.add(_fieldLabel(field));
      }
      if (candidate == null) {
        if (root.parameter.isRequired) {
          _throwMissingRequiredSource(
            declaration,
            targetTypeLiteral,
            root,
            fields,
          );
        }
        continue;
      }
      if (ambiguousLabels != null) {
        throw InvalidGenerationSourceError(
          'Constructor parameter ${root.parameter.displayName} on '
          '$targetTypeLiteral has multiple writable identity-flow sources: '
          '${ambiguousLabels.join(', ')}. Select a constructor '
          'whose parameter flow identifies exactly one serialized field.',
          element: declaration,
        );
      }
      final field = candidate;
      final existingRoot = rootByArgumentField[field];
      if (existingRoot != null && !identical(existingRoot, root)) {
        throw InvalidGenerationSourceError(
          'Serialized field ${_fieldLabel(field)} would supply constructor '
          'parameters ${existingRoot.parameter.displayName} and '
          '${root.parameter.displayName} on $targetTypeLiteral. A field may '
          'supply at most one constructor argument.',
          element: field.declaration,
        );
      }
      bindingByRoot[root] = field;
      rootByArgumentField[field] = root;
    }

    final arguments = <_ConstructorArgumentSpec>[];
    var omittedOptionalPositional = false;
    for (final root in rootFrame.parameters) {
      final parameter = root.parameter;
      final field = bindingByRoot[root];
      if (field == null) {
        if (parameter.isOptionalPositional) {
          omittedOptionalPositional = true;
        }
        continue;
      }
      if (parameter.isPositional && omittedOptionalPositional) {
        throw InvalidGenerationSourceError(
          'Constructor $targetTypeLiteral() cannot pass parameter '
          '${parameter.displayName} after an omitted optional positional '
          'parameter. Use named parameters, remove the positional gap, mark '
          'the final field with @ForyField(ignore: true), or use a custom '
          'serializer.',
          element: declaration,
        );
      }
      arguments.add(
        _ConstructorArgumentSpec(
          field: field,
          parameterName: parameter.displayName,
          named: parameter.isNamed,
        ),
      );
    }

    if (bindingByRoot.isEmpty) {
      return const _ConstructionModel.mutable(constructorName: null);
    }

    final postConstructionFields = Set<_GeneratedFieldSpec>.identity();
    for (final field in fields) {
      if (field.writable && !rootByArgumentField.containsKey(field)) {
        postConstructionFields.add(field);
      }
    }
    return _ConstructionModel.constructor(
      constructorName: null,
      arguments: arguments,
      postConstructionFields: postConstructionFields,
    );
  }

  Future<_ConstructorFrame> _buildFrame(
    ConstructorElement constructor,
    List<_GeneratedFieldSpec> fields,
    ClassElement declaration,
    Set<_ConstructorKey> active,
  ) async {
    final key = _ConstructorKey(
      constructor.baseElement,
      constructor.returnType,
    );
    if (active.contains(key)) {
      throw InvalidGenerationSourceError(
        'Constructor redirection or super-constructor flow for '
        '${declaration.displayName} is cyclic at '
        '${_constructorLabel(constructor)}.',
        element: declaration,
      );
    }
    final frame = _ConstructorFrame(constructor);
    _frames.add(frame);
    active.add(key);
    try {
      final redirected = constructor.redirectedConstructor;
      final superConstructor = constructor.superConstructor;
      if (redirected != null && superConstructor != null) {
        throw InvalidGenerationSourceError(
          'Constructor ${_constructorLabel(constructor)} resolves both a '
          'redirect target and a super constructor, so Fory cannot prove one '
          'construction path.',
          element: declaration,
        );
      }
      final nextConstructor = redirected ?? superConstructor;
      final nextFrame =
          nextConstructor == null || _isObjectConstructor(nextConstructor)
              ? null
              : await _buildFrame(nextConstructor, fields, declaration, active);

      _addSummaryEdges(frame, nextFrame, fields);
      if (_isMixinApplicationConstructor(constructor)) {
        _addMixinForwardingEdges(frame, nextFrame, declaration);
      } else if (_isDeclaredConstructor(constructor)) {
        // Summary elements completely prove a leaf constructor only when it
        // has no optional parameters and every required argument and final
        // field already has an exact storage edge. An explicit initializer
        // may connect an optional parameter to mutable storage that must
        // receive its decoded value during construction.
        if (nextFrame != null || !_summaryProvesLeaf(frame, fields)) {
          final node = await _astFor(constructor);
          if (node is ConstructorDeclaration) {
            _addAstEdges(
              frame,
              nextFrame,
              redirected != null
                  ? _ConstructorHop.redirect
                  : _ConstructorHop.superCall,
              node,
              fields,
            );
          } else {
            frame.unavailableProof =
                node == null
                    ? 'resolved source AST is unavailable'
                    : 'resolved node ${node.runtimeType} is not a supported '
                        'constructor declaration';
          }
        }
      } else if (frame.parameters.isNotEmpty) {
        frame.unavailableProof =
            'synthetic constructor parameters do not expose an exact '
            'forwarding relationship';
      }
      return frame;
    } finally {
      active.remove(key);
    }
  }

  bool _summaryProvesLeaf(
    _ConstructorFrame frame,
    List<_GeneratedFieldSpec> fields,
  ) {
    for (final parameter in frame.parameters) {
      if (parameter.parameter.isOptional || parameter.fieldTargets.isEmpty) {
        return false;
      }
    }
    for (final field in fields) {
      if (field.writable) {
        continue;
      }
      var hasFieldTarget = false;
      for (final parameter in frame.parameters) {
        if (parameter.fieldTargets.contains(field)) {
          hasFieldTarget = true;
          break;
        }
      }
      if (!hasFieldTarget) {
        return false;
      }
    }
    return true;
  }

  void _addSummaryEdges(
    _ConstructorFrame frame,
    _ConstructorFrame? nextFrame,
    List<_GeneratedFieldSpec> fields,
  ) {
    for (final source in frame.parameters) {
      final parameter = source.parameter;
      if (parameter is FieldFormalParameterElement) {
        final fieldElement = parameter.field;
        if (fieldElement == null) {
          frame.recordIssue(
            'field formal ${parameter.displayName} has no resolved storage '
            'field',
          );
        } else {
          final field = _fieldFor(fieldElement, frame.constructor, fields);
          if (field != null) {
            _addFieldEdge(frame, source, field);
          }
        }
      }
      if (parameter is SuperFormalParameterElement) {
        final targetElement = parameter.superConstructorParameter;
        if (nextFrame == null || targetElement == null) {
          frame.recordIssue(
            'super formal ${parameter.displayName} has no resolved target '
            'parameter',
          );
          continue;
        }
        final target = nextFrame.parameterFor(targetElement);
        if (target == null) {
          frame.recordIssue(
            'super formal ${parameter.displayName} does not identify a '
            'parameter on ${_constructorLabel(nextFrame.constructor)}',
          );
          continue;
        }
        _addParameterEdge(frame, source, target);
      }
    }
  }

  void _addMixinForwardingEdges(
    _ConstructorFrame frame,
    _ConstructorFrame? nextFrame,
    ClassElement declaration,
  ) {
    if (nextFrame == null ||
        frame.parameters.length != nextFrame.parameters.length) {
      throw InvalidGenerationSourceError(
        'Synthetic mixin-application constructor '
        '${_constructorLabel(frame.constructor)} does not have the exact '
        'forwarding signature of its super constructor.',
        element: declaration,
      );
    }
    for (var index = 0; index < frame.parameters.length; index += 1) {
      final source = frame.parameters[index];
      final target = nextFrame.parameters[index];
      final sourceParameter = source.parameter;
      final targetParameter = target.parameter;
      final sameKind =
          sourceParameter.isNamed == targetParameter.isNamed &&
          sourceParameter.isRequired == targetParameter.isRequired &&
          sourceParameter.isOptional == targetParameter.isOptional &&
          sourceParameter.isPositional == targetParameter.isPositional;
      final sameName =
          !sourceParameter.isNamed ||
          sourceParameter.displayName == targetParameter.displayName;
      if (!sameKind ||
          !sameName ||
          sourceParameter.type != targetParameter.type) {
        throw InvalidGenerationSourceError(
          'Synthetic mixin-application constructor parameter '
          '${sourceParameter.displayName} on '
          '${_constructorLabel(frame.constructor)} does not exactly forward '
          'the corresponding super-constructor parameter.',
          element: declaration,
        );
      }
      source.addParameterTarget(target);
    }
  }

  void _addAstEdges(
    _ConstructorFrame frame,
    _ConstructorFrame? nextFrame,
    _ConstructorHop hop,
    ConstructorDeclaration node,
    List<_GeneratedFieldSpec> fields,
  ) {
    final declaredConstructor = node.declaredFragment?.element;
    if (declaredConstructor == null ||
        !identical(
          declaredConstructor.baseElement,
          frame.constructor.baseElement,
        )) {
      frame.recordIssue(
        'resolved constructor AST does not identify '
        '${_constructorLabel(frame.constructor)}',
      );
      return;
    }

    RedirectingConstructorInvocation? redirectInvocation;
    SuperConstructorInvocation? superInvocation;
    for (final initializer in node.initializers) {
      if (initializer is ConstructorFieldInitializer) {
        _addFieldInitializerEdge(frame, initializer, fields);
      } else if (initializer is RedirectingConstructorInvocation) {
        if (redirectInvocation != null) {
          frame.recordIssue('multiple redirecting constructor invocations');
        }
        redirectInvocation = initializer;
      } else if (initializer is SuperConstructorInvocation) {
        if (superInvocation != null) {
          frame.recordIssue('multiple super-constructor invocations');
        }
        superInvocation = initializer;
      }
    }

    if (nextFrame == null) {
      return;
    }
    switch (hop) {
      case _ConstructorHop.redirect:
        final invocation = redirectInvocation;
        if (invocation == null) {
          frame.recordIssue(
            'redirect target ${_constructorLabel(nextFrame.constructor)} '
            'requires an explicit resolved invocation',
          );
          return;
        }
        _addInvocationEdges(
          frame,
          nextFrame,
          invocation.element,
          invocation.argumentList.arguments,
        );
      case _ConstructorHop.superCall:
        final invocation = superInvocation;
        if (invocation == null) {
          return;
        }
        _addInvocationEdges(
          frame,
          nextFrame,
          invocation.element,
          invocation.argumentList.arguments,
        );
    }
  }

  void _addFieldInitializerEdge(
    _ConstructorFrame frame,
    ConstructorFieldInitializer initializer,
    List<_GeneratedFieldSpec> fields,
  ) {
    final fieldElement = initializer.fieldName.element;
    if (fieldElement is! FieldElement) {
      frame.recordIssue(
        'field initializer ${initializer.fieldName.name} has no resolved '
        'storage field',
      );
      return;
    }
    final field = _fieldFor(fieldElement, frame.constructor, fields);
    if (field == null) {
      return;
    }
    final source = _directParameter(initializer.expression, frame);
    if (source == null) {
      if (!field.writable) {
        frame.recordIssue(
          'initializer for ${_fieldLabel(field)} transforms or does not '
          'directly reference a constructor parameter',
        );
      }
      return;
    }
    _addFieldEdge(frame, source, field);
  }

  void _addInvocationEdges(
    _ConstructorFrame frame,
    _ConstructorFrame targetFrame,
    ConstructorElement? invokedConstructor,
    Iterable<AstNode> arguments,
  ) {
    if (invokedConstructor == null ||
        !identical(
          invokedConstructor.baseElement,
          targetFrame.constructor.baseElement,
        )) {
      frame.recordIssue(
        'resolved invocation does not identify '
        '${_constructorLabel(targetFrame.constructor)}',
      );
      return;
    }
    var positionalIndex = 0;
    for (final argument in arguments) {
      final firstToken = argument.beginToken;
      final named = firstToken.next?.lexeme == ':';
      final target = _invocationTarget(
        targetFrame,
        firstToken.lexeme,
        named,
        positionalIndex,
      );
      if (!named) {
        positionalIndex += 1;
      }
      if (target == null) {
        frame.recordIssue(
          'constructor argument has no resolved target parameter',
        );
        continue;
      }
      final value = _invocationValue(argument, named);
      if (value == null) {
        frame.recordIssue(
          'constructor argument has no resolved value expression',
        );
        continue;
      }
      final source = _directParameter(value, frame);
      if (source == null) {
        frame.recordIssue(
          'argument for ${target.parameter.displayName} transforms or does '
          'not directly reference a constructor parameter',
        );
        continue;
      }
      _addParameterEdge(frame, source, target);
    }
  }

  _ParameterNode? _invocationTarget(
    _ConstructorFrame frame,
    String argumentName,
    bool named,
    int positionalIndex,
  ) {
    var currentPositionalIndex = 0;
    for (final parameter in frame.parameters) {
      if (named) {
        if (parameter.parameter.isNamed &&
            parameter.parameter.displayName == argumentName) {
          return parameter;
        }
        continue;
      }
      if (!parameter.parameter.isPositional) {
        continue;
      }
      if (currentPositionalIndex == positionalIndex) {
        return parameter;
      }
      currentPositionalIndex += 1;
    }
    return null;
  }

  Expression? _invocationValue(AstNode argument, bool named) {
    if (!named) {
      return argument is Expression ? argument : null;
    }
    // Supported analyzer versions use different public node types for named
    // arguments. Dart grammar still gives both representations exactly one
    // direct value expression after the name and colon.
    Expression? value;
    for (final child in argument.childEntities) {
      if (child is! Expression) {
        continue;
      }
      if (value != null) {
        return null;
      }
      value = child;
    }
    return value;
  }

  void _addFieldEdge(
    _ConstructorFrame frame,
    _ParameterNode source,
    _GeneratedFieldSpec field,
  ) {
    if (source.parameter.type != field.type) {
      frame.recordIssue(
        'parameter ${source.parameter.displayName} has type '
        '${source.parameter.type.getDisplayString()}, but '
        '${_fieldLabel(field)} has type ${field.type.getDisplayString()}',
      );
      return;
    }
    source.addFieldTarget(field);
  }

  void _addParameterEdge(
    _ConstructorFrame frame,
    _ParameterNode source,
    _ParameterNode target,
  ) {
    if (source.parameter.type != target.parameter.type) {
      frame.recordIssue(
        'parameter ${source.parameter.displayName} on '
        '${_constructorLabel(frame.constructor)} has type '
        '${source.parameter.type.getDisplayString()}, but target parameter '
        '${target.parameter.displayName} has type '
        '${target.parameter.type.getDisplayString()}',
      );
      return;
    }
    source.addParameterTarget(target);
  }

  _GeneratedFieldSpec? _fieldFor(
    FieldElement fieldElement,
    ConstructorElement constructor,
    List<_GeneratedFieldSpec> fields,
  ) {
    final fieldBase = fieldElement.baseElement;
    final ownerBase = fieldBase.enclosingElement.baseElement;
    final constructorOwner = constructor.enclosingElement.baseElement;
    _GeneratedFieldSpec? match;
    for (final field in fields) {
      if (!identical(field.declaration.baseElement, fieldBase) ||
          !identical(
            field.declaration.enclosingElement.baseElement,
            ownerBase,
          ) ||
          !identical(ownerBase, constructorOwner)) {
        continue;
      }
      if (match != null) {
        throw InvalidGenerationSourceError(
          'Constructor ${_constructorLabel(constructor)} cannot identify one '
          'applied storage slot for ${fieldBase.displayName}.',
          element: fieldBase,
        );
      }
      match = field;
    }
    return match;
  }

  _ParameterNode? _directParameter(
    Expression expression,
    _ConstructorFrame frame,
  ) {
    final unwrapped = expression.unParenthesized;
    if (unwrapped is! SimpleIdentifier) {
      return null;
    }
    final element = unwrapped.element;
    return element is FormalParameterElement
        ? frame.parameterFor(element)
        : null;
  }

  Set<_GeneratedFieldSpec> _reachableFields(_ParameterNode root) {
    final fields = Set<_GeneratedFieldSpec>.identity();
    final visited = Set<_ParameterNode>.identity();

    void visit(_ParameterNode parameter) {
      if (!visited.add(parameter)) {
        return;
      }
      fields.addAll(parameter.fieldTargets);
      for (final target in parameter.parameterTargets) {
        visit(target);
      }
    }

    visit(root);
    return fields;
  }

  Future<AstNode?> _astFor(ConstructorElement constructor) {
    final base = constructor.baseElement;
    final cached = _astByConstructor[base];
    if (cached != null) {
      return cached;
    }
    final ast = buildStep.resolver.astNodeFor(
      base.firstFragment,
      resolve: true,
    );
    _astByConstructor[base] = ast;
    return ast;
  }

  Never _throwMissingFinalFlow(
    ClassElement declaration,
    String targetTypeLiteral,
    _GeneratedFieldSpec field,
  ) {
    final proofProblem = _firstProofProblem();
    final detail =
        proofProblem == null
            ? ''
            : ' Constructor proof could not use $proofProblem.';
    throw InvalidGenerationSourceError(
      'Final field ${_fieldLabel(field)} is not initialized from a parameter '
      'of selected constructor $targetTypeLiteral(). A serialized final field '
      'must receive its decoded value unchanged through a field formal, '
      'constructor initializer, redirect, or super-constructor forwarding.'
      '$detail Mark the field with @ForyField(ignore: true), add an exact '
      'identity path, or use a custom serializer.',
      element: field.declaration,
    );
  }

  Never _throwMissingRequiredSource(
    ClassElement declaration,
    String targetTypeLiteral,
    _ParameterNode root,
    List<_GeneratedFieldSpec> fields,
  ) {
    _GeneratedFieldSpec? sameName;
    for (final field in fields) {
      if (field.name == root.parameter.displayName && field.writable) {
        sameName = field;
        break;
      }
    }
    final nameDetail =
        sameName == null
            ? ''
            : ' A same-name field ${_fieldLabel(sameName)} exists, but name '
                'equality is not constructor identity proof.';
    final proofProblem = _firstProofProblem();
    final proofDetail =
        proofProblem == null
            ? ''
            : ' Constructor proof could not use $proofProblem.';
    throw InvalidGenerationSourceError(
      'Required constructor parameter ${root.parameter.displayName} on '
      '$targetTypeLiteral has no serialized field connected by an exact '
      'identity-preserving path.$nameDetail$proofDetail Add a direct '
      'parameter-to-field flow, select a zero-required-argument constructor, '
      'or use a custom serializer.',
      element: declaration,
    );
  }

  String? _firstProofProblem() {
    String? firstIssue;
    for (final frame in _frames) {
      final unavailable = frame.unavailableProof;
      if (unavailable != null) {
        return '$unavailable for ${_constructorLabel(frame.constructor)}';
      }
      final issue = frame.firstIssue;
      if (firstIssue == null && issue != null) {
        firstIssue = '$issue in ${_constructorLabel(frame.constructor)}';
      }
    }
    return firstIssue;
  }

  bool _isDeclaredConstructor(ConstructorElement constructor) =>
      constructor.baseElement.nonSynthetic is ConstructorElement;

  bool _isMixinApplicationConstructor(ConstructorElement constructor) {
    final owner = constructor.enclosingElement;
    return owner is ClassElement && owner.isMixinApplication;
  }

  bool _isObjectConstructor(ConstructorElement constructor) {
    final owner = constructor.enclosingElement;
    return owner is ClassElement && owner.isDartCoreObject;
  }

  String _constructorLabel(ConstructorElement constructor) =>
      '${constructor.enclosingElement.displayName}.'
      '${constructor.displayName}';

  String _fieldLabel(_GeneratedFieldSpec field) =>
      '${field.declaration.enclosingElement.displayName}.${field.name}';
}

enum _ConstructorHop { redirect, superCall }

final class _ConstructorKey {
  final ConstructorElement declaration;
  final InterfaceType returnType;

  const _ConstructorKey(this.declaration, this.returnType);

  @override
  int get hashCode => Object.hash(identityHashCode(declaration), returnType);

  @override
  bool operator ==(Object other) =>
      other is _ConstructorKey &&
      identical(declaration, other.declaration) &&
      returnType == other.returnType;
}

final class _ConstructorFrame {
  final ConstructorElement constructor;
  final List<_ParameterNode> parameters;
  String? firstIssue;
  String? unavailableProof;

  _ConstructorFrame(this.constructor)
    : parameters = <_ParameterNode>[
        for (final parameter in constructor.formalParameters)
          _ParameterNode(parameter),
      ];

  void recordIssue(String issue) {
    firstIssue ??= issue;
  }

  _ParameterNode? parameterFor(FormalParameterElement element) {
    final base = element.baseElement;
    for (final parameter in parameters) {
      if (identical(parameter.parameter.baseElement, base)) {
        return parameter;
      }
    }
    return null;
  }
}

final class _ParameterNode {
  final FormalParameterElement parameter;
  final List<_ParameterNode> parameterTargets = <_ParameterNode>[];
  final List<_GeneratedFieldSpec> fieldTargets = <_GeneratedFieldSpec>[];

  _ParameterNode(this.parameter);

  void addParameterTarget(_ParameterNode target) {
    for (final existing in parameterTargets) {
      if (identical(existing, target)) {
        return;
      }
    }
    parameterTargets.add(target);
  }

  void addFieldTarget(_GeneratedFieldSpec target) {
    for (final existing in fieldTargets) {
      if (identical(existing, target)) {
        return;
      }
    }
    fieldTargets.add(target);
  }
}
