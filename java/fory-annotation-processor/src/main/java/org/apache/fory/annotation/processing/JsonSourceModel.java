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

package org.apache.fory.annotation.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/** Immutable-enough processor model for one generated JSON metadata companion. */
final class JsonSourceModel {
  final String packageName;
  final String targetBinaryName;
  final String metadataSimpleName;
  final String metadataBinaryName;
  final Section declarations;
  final Section subtypes;
  final Section object;

  JsonSourceModel(
      String packageName,
      String targetBinaryName,
      String metadataSimpleName,
      Section declarations,
      Section subtypes,
      Section object) {
    this.packageName = packageName;
    this.targetBinaryName = targetBinaryName;
    this.metadataSimpleName = metadataSimpleName;
    metadataBinaryName =
        packageName.isEmpty() ? metadataSimpleName : packageName + "." + metadataSimpleName;
    this.declarations = declarations;
    this.subtypes = subtypes;
    this.object = object;
  }

  Section section(int id) {
    if (id == 0) {
      return declarations;
    }
    if (id == 1) {
      return subtypes;
    }
    if (id == 2) {
      return object;
    }
    throw new IllegalArgumentException("Unknown section " + id);
  }

  static final class Section {
    final int id;
    final List<Token> tokens = new ArrayList<>();
    final List<TypeNode> typeNodes = new ArrayList<>();
    final List<TypeParameter> typeParameters = new ArrayList<>();
    final List<Operation> operations = new ArrayList<>();
    final List<Fact> facts = new ArrayList<>();
    final Map<String, Token> tokensByKey = new LinkedHashMap<>();
    int directTokenCount;
    int directOperationCount;

    Section(int id) {
      this.id = id;
    }

    boolean empty() {
      return facts.isEmpty() && tokens.isEmpty() && typeNodes.isEmpty();
    }
  }

  static final class Token {
    final int id;
    final TypeMirror type;
    final String binaryName;
    final boolean direct;
    final int directIndex;
    final int primitiveKind;
    int nameChunk = -1;
    int nameSlot = -1;

    Token(
        int id,
        TypeMirror type,
        String binaryName,
        boolean direct,
        int directIndex,
        int primitiveKind) {
      this.id = id;
      this.type = type;
      this.binaryName = binaryName;
      this.direct = direct;
      this.directIndex = directIndex;
      this.primitiveKind = primitiveKind;
    }

    boolean primitive() {
      return primitiveKind >= 0;
    }
  }

  static final class TypeNode {
    final int id;
    final int kind;
    final int token;
    final int ownerNode;
    final List<Integer> children;
    final List<Integer> lowerBounds;
    final int variableKey;
    final int codecToken;
    final int codecOperation;
    final String codecSource;

    TypeNode(
        int id,
        int kind,
        int token,
        int ownerNode,
        List<Integer> children,
        List<Integer> lowerBounds,
        int variableKey,
        int codecToken,
        int codecOperation,
        String codecSource) {
      this.id = id;
      this.kind = kind;
      this.token = token;
      this.ownerNode = ownerNode;
      this.children = immutable(children);
      this.lowerBounds = immutable(lowerBounds);
      this.variableKey = variableKey;
      this.codecToken = codecToken;
      this.codecOperation = codecOperation;
      this.codecSource = codecSource == null ? "" : codecSource;
    }
  }

  static final class TypeParameter {
    final int id;
    final int ownerKind;
    final int ownerToken;
    final String memberName;
    final String descriptor;
    final int parameterIndex;
    final List<Integer> bounds;

    TypeParameter(
        int id,
        int ownerKind,
        int ownerToken,
        String memberName,
        String descriptor,
        int parameterIndex,
        List<Integer> bounds) {
      this.id = id;
      this.ownerKind = ownerKind;
      this.ownerToken = ownerToken;
      this.memberName = memberName;
      this.descriptor = descriptor;
      this.parameterIndex = parameterIndex;
      this.bounds = immutable(bounds);
    }
  }

  static final class Operation {
    final int id;
    final int shape;
    final int directionMask;
    final boolean direct;
    final int directIndex;
    final Element member;
    final int ownerToken;
    final String memberName;
    final String descriptor;
    final TypeMirror valueType;
    final String ownerSourceName;

    Operation(
        int id,
        int shape,
        int directionMask,
        boolean direct,
        int directIndex,
        Element member,
        int ownerToken,
        String memberName,
        String descriptor,
        TypeMirror valueType,
        String ownerSourceName) {
      this.id = id;
      this.shape = shape;
      this.directionMask = directionMask;
      this.direct = direct;
      this.directIndex = directIndex;
      this.member = member;
      this.ownerToken = ownerToken;
      this.memberName = memberName;
      this.descriptor = descriptor;
      this.valueType = valueType;
      this.ownerSourceName = ownerSourceName;
    }
  }

  static final class Property {
    final boolean present;
    final String name;
    final int index;
    final int include;

    Property(boolean present, String name, int index, int include) {
      this.present = present;
      this.name = name;
      this.index = index;
      this.include = include;
    }
  }

  abstract static class Fact {
    final int tag;

    Fact(int tag) {
      this.tag = tag;
    }
  }

  static final class DeclarationFact extends Fact {
    final int typeToken;
    final int kind;
    final int modifiers;
    final int superclassToken;
    final List<Integer> interfaceTokens;
    final int codecToken;
    final int codecOperation;

    DeclarationFact(
        int tag,
        int typeToken,
        int kind,
        int modifiers,
        int superclassToken,
        List<Integer> interfaceTokens,
        int codecToken,
        int codecOperation) {
      super(tag);
      this.typeToken = typeToken;
      this.kind = kind;
      this.modifiers = modifiers;
      this.superclassToken = superclassToken;
      this.interfaceTokens = immutable(interfaceTokens);
      this.codecToken = codecToken;
      this.codecOperation = codecOperation;
    }
  }

  static final class SubtypesFact extends Fact {
    final int inclusion;
    final String property;
    final List<SubtypeEntry> entries;

    SubtypesFact(int tag, int inclusion, String property, List<SubtypeEntry> entries) {
      super(tag);
      this.inclusion = inclusion;
      this.property = property;
      this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }
  }

  static final class SubtypeEntry {
    final int typeToken;
    final String className;
    final String name;

    SubtypeEntry(int typeToken, String className, String name) {
      this.typeToken = typeToken;
      this.className = className;
      this.name = name;
    }
  }

  static final class FieldFact extends Fact {
    final int declaringToken;
    final String name;
    final String descriptor;
    final int modifiers;
    final int typeNode;
    final int flags;
    final Property property;
    final int operation;

    FieldFact(
        int tag,
        int declaringToken,
        String name,
        String descriptor,
        int modifiers,
        int typeNode,
        int flags,
        Property property,
        int operation) {
      super(tag);
      this.declaringToken = declaringToken;
      this.name = name;
      this.descriptor = descriptor;
      this.modifiers = modifiers;
      this.typeNode = typeNode;
      this.flags = flags;
      this.property = property;
      this.operation = operation;
    }
  }

  static final class MethodFact extends Fact {
    final int declaringToken;
    final String name;
    final String descriptor;
    final int modifiers;
    final int returnNode;
    final List<Integer> parameters;
    final int flags;
    final Property property;
    final int operation;

    MethodFact(
        int tag,
        int declaringToken,
        String name,
        String descriptor,
        int modifiers,
        int returnNode,
        List<Integer> parameters,
        int flags,
        Property property,
        int operation) {
      super(tag);
      this.declaringToken = declaringToken;
      this.name = name;
      this.descriptor = descriptor;
      this.modifiers = modifiers;
      this.returnNode = returnNode;
      this.parameters = immutable(parameters);
      this.flags = flags;
      this.property = property;
      this.operation = operation;
    }
  }

  static final class CreatorFact extends Fact {
    final int executableKind;
    final int declaringToken;
    final String name;
    final String descriptor;
    final int modifiers;
    final List<String> propertyNames;
    final List<Integer> parameterNodes;
    final List<Property> parameterProperties;
    final int operation;

    CreatorFact(
        int tag,
        int executableKind,
        int declaringToken,
        String name,
        String descriptor,
        int modifiers,
        List<String> propertyNames,
        List<Integer> parameterNodes,
        List<Property> parameterProperties,
        int operation) {
      super(tag);
      this.executableKind = executableKind;
      this.declaringToken = declaringToken;
      this.name = name;
      this.descriptor = descriptor;
      this.modifiers = modifiers;
      this.propertyNames = Collections.unmodifiableList(new ArrayList<>(propertyNames));
      this.parameterNodes = immutable(parameterNodes);
      this.parameterProperties = Collections.unmodifiableList(new ArrayList<>(parameterProperties));
      this.operation = operation;
    }
  }

  static final class RecordFact extends Fact {
    final int declaringToken;
    final String name;
    final int typeNode;
    final String accessorDescriptor;
    final int flags;
    final Property property;

    RecordFact(
        int tag,
        int declaringToken,
        String name,
        int typeNode,
        String accessorDescriptor,
        int flags,
        Property property) {
      super(tag);
      this.declaringToken = declaringToken;
      this.name = name;
      this.typeNode = typeNode;
      this.accessorDescriptor = accessorDescriptor;
      this.flags = flags;
      this.property = property;
    }
  }

  static final class OrderFact extends Fact {
    final int declaringToken;
    final List<String> names;
    final boolean alphabetic;

    OrderFact(int tag, int declaringToken, List<String> names, boolean alphabetic) {
      super(tag);
      this.declaringToken = declaringToken;
      this.names = Collections.unmodifiableList(new ArrayList<>(names));
      this.alphabetic = alphabetic;
    }
  }

  static final class InstantiatorFact extends Fact {
    final int kind;
    final int operation;

    InstantiatorFact(int tag, int kind, int operation) {
      super(tag);
      this.kind = kind;
      this.operation = operation;
    }
  }

  static final class HierarchyFact extends Fact {
    final int declaringToken;
    final int superclassNode;
    final List<Integer> interfaceNodes;

    HierarchyFact(int tag, int declaringToken, int superclassNode, List<Integer> interfaceNodes) {
      super(tag);
      this.declaringToken = declaringToken;
      this.superclassNode = superclassNode;
      this.interfaceNodes = immutable(interfaceNodes);
    }
  }

  private static List<Integer> immutable(List<Integer> values) {
    return values == null
        ? Collections.emptyList()
        : Collections.unmodifiableList(new ArrayList<>(values));
  }
}
