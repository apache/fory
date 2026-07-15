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

package org.apache.fory.json.meta;

import org.apache.fory.annotation.Internal;

/** Dense immutable facts decoded from one independently lazy generated metadata section. */
@Internal
public final class JsonDecodedMetadata {
  private final int section;
  private final Token[] tokens;
  private final TypeParameter[] typeParameters;
  private final JsonTypeNode[] typeNodes;
  private final Operation[] operations;
  private final Declaration[] declarations;
  private final SubtypeTable[] subtypeTables;
  private final Field[] fields;
  private final Method[] methods;
  private final Creator[] creators;
  private final RecordComponent[] recordComponents;
  private final PropertyOrder[] propertyOrders;
  private final Instantiator[] instantiators;
  private final Hierarchy[] hierarchies;

  JsonDecodedMetadata(
      int section,
      Token[] tokens,
      TypeParameter[] typeParameters,
      JsonTypeNode[] typeNodes,
      Operation[] operations,
      Declaration[] declarations,
      SubtypeTable[] subtypeTables,
      Field[] fields,
      Method[] methods,
      Creator[] creators,
      RecordComponent[] recordComponents,
      PropertyOrder[] propertyOrders,
      Instantiator[] instantiators,
      Hierarchy[] hierarchies) {
    this.section = section;
    this.tokens = tokens;
    this.typeParameters = typeParameters;
    this.typeNodes = typeNodes;
    this.operations = operations;
    this.declarations = declarations;
    this.subtypeTables = subtypeTables;
    this.fields = fields;
    this.methods = methods;
    this.creators = creators;
    this.recordComponents = recordComponents;
    this.propertyOrders = propertyOrders;
    this.instantiators = instantiators;
    this.hierarchies = hierarchies;
  }

  public int section() {
    return section;
  }

  public int tokenCount() {
    return tokens.length;
  }

  public Token token(int index) {
    return tokens[index];
  }

  public int typeParameterCount() {
    return typeParameters.length;
  }

  public TypeParameter typeParameter(int index) {
    return typeParameters[index];
  }

  public int typeNodeCount() {
    return typeNodes.length;
  }

  public JsonTypeNode typeNode(int index) {
    return typeNodes[index];
  }

  public int operationCount() {
    return operations.length;
  }

  public Operation operation(int index) {
    return operations[index];
  }

  public int declarationCount() {
    return declarations.length;
  }

  public Declaration declaration(int index) {
    return declarations[index];
  }

  public int subtypeTableCount() {
    return subtypeTables.length;
  }

  public SubtypeTable subtypeTable(int index) {
    return subtypeTables[index];
  }

  public int fieldCount() {
    return fields.length;
  }

  public Field field(int index) {
    return fields[index];
  }

  public int methodCount() {
    return methods.length;
  }

  public Method method(int index) {
    return methods[index];
  }

  public int creatorCount() {
    return creators.length;
  }

  public Creator creator(int index) {
    return creators[index];
  }

  public int recordComponentCount() {
    return recordComponents.length;
  }

  public RecordComponent recordComponent(int index) {
    return recordComponents[index];
  }

  public int propertyOrderCount() {
    return propertyOrders.length;
  }

  public PropertyOrder propertyOrder(int index) {
    return propertyOrders[index];
  }

  public int instantiatorCount() {
    return instantiators.length;
  }

  public Instantiator instantiator(int index) {
    return instantiators[index];
  }

  public int hierarchyCount() {
    return hierarchies.length;
  }

  public Hierarchy hierarchy(int index) {
    return hierarchies[index];
  }

  /** One section-local type token. */
  public static final class Token {
    private final int mode;
    private final int primitiveKind;
    private final int directIndex;
    private final String inaccessibleName;
    private final int nameChunk;
    private final int nameSlot;

    Token(
        int mode,
        int primitiveKind,
        int directIndex,
        String inaccessibleName,
        int nameChunk,
        int nameSlot) {
      this.mode = mode;
      this.primitiveKind = primitiveKind;
      this.directIndex = directIndex;
      this.inaccessibleName = inaccessibleName;
      this.nameChunk = nameChunk;
      this.nameSlot = nameSlot;
    }

    public int mode() {
      return mode;
    }

    public int primitiveKind() {
      return primitiveKind;
    }

    public int directIndex() {
      return directIndex;
    }

    public String inaccessibleName() {
      return inaccessibleName;
    }

    int nameChunk() {
      return nameChunk;
    }

    int nameSlot() {
      return nameSlot;
    }
  }

  /** One stable generated type-parameter declaration key. */
  public static final class TypeParameter {
    private final int ownerKind;
    private final int ownerToken;
    private final String memberName;
    private final String descriptor;
    private final int parameterIndex;
    private final int[] bounds;

    TypeParameter(
        int ownerKind,
        int ownerToken,
        String memberName,
        String descriptor,
        int parameterIndex,
        int[] bounds) {
      this.ownerKind = ownerKind;
      this.ownerToken = ownerToken;
      this.memberName = memberName;
      this.descriptor = descriptor;
      this.parameterIndex = parameterIndex;
      this.bounds = bounds;
    }

    public int ownerKind() {
      return ownerKind;
    }

    public int ownerToken() {
      return ownerToken;
    }

    public String memberName() {
      return memberName;
    }

    public String descriptor() {
      return descriptor;
    }

    public int parameterIndex() {
      return parameterIndex;
    }

    public int boundCount() {
      return bounds.length;
    }

    public int bound(int index) {
      return bounds[index];
    }
  }

  /** One generated direct-operation index or exact cold member recipe. */
  public static final class Operation {
    private final int shape;
    private final int mode;
    private final int directionMask;
    private final int directIndex;
    private final int ownerToken;
    private final String memberName;
    private final String descriptor;

    Operation(
        int shape,
        int mode,
        int directionMask,
        int directIndex,
        int ownerToken,
        String memberName,
        String descriptor) {
      this.shape = shape;
      this.mode = mode;
      this.directionMask = directionMask;
      this.directIndex = directIndex;
      this.ownerToken = ownerToken;
      this.memberName = memberName;
      this.descriptor = descriptor;
    }

    public int shape() {
      return shape;
    }

    public int mode() {
      return mode;
    }

    public int directionMask() {
      return directionMask;
    }

    public int directIndex() {
      return directIndex;
    }

    public int ownerToken() {
      return ownerToken;
    }

    public String memberName() {
      return memberName;
    }

    public String descriptor() {
      return descriptor;
    }
  }

  /** Flattened {@code JsonProperty} values for one member or creator parameter. */
  public static final class Property {
    static final Property ABSENT = new Property(false, "", -1, 0);

    private final boolean present;
    private final String name;
    private final int index;
    private final int include;

    Property(boolean present, String name, int index, int include) {
      this.present = present;
      this.name = name;
      this.index = index;
      this.include = include;
    }

    public boolean present() {
      return present;
    }

    public String name() {
      return name;
    }

    public int index() {
      return index;
    }

    public int include() {
      return include;
    }
  }

  /** One declaration frontier entry. */
  public static final class Declaration {
    private final int typeToken;
    private final int kind;
    private final int modifiers;
    private final int superclassToken;
    private final int[] interfaceTokens;
    private final int codecToken;
    private final int codecOperation;

    Declaration(
        int typeToken,
        int kind,
        int modifiers,
        int superclassToken,
        int[] interfaceTokens,
        int codecToken,
        int codecOperation) {
      this.typeToken = typeToken;
      this.kind = kind;
      this.modifiers = modifiers;
      this.superclassToken = superclassToken;
      this.interfaceTokens = interfaceTokens;
      this.codecToken = codecToken;
      this.codecOperation = codecOperation;
    }

    public int typeToken() {
      return typeToken;
    }

    public int kind() {
      return kind;
    }

    public int modifiers() {
      return modifiers;
    }

    public int superclassToken() {
      return superclassToken;
    }

    public int interfaceCount() {
      return interfaceTokens.length;
    }

    public int interfaceToken(int index) {
      return interfaceTokens[index];
    }

    public boolean hasCodec() {
      return codecToken >= 0;
    }

    public int codecToken() {
      return codecToken;
    }

    public int codecOperation() {
      return codecOperation;
    }
  }

  /** One complete closed subtype annotation. */
  public static final class SubtypeTable {
    private final int inclusion;
    private final String property;
    private final SubtypeEntry[] entries;

    SubtypeTable(int inclusion, String property, SubtypeEntry[] entries) {
      this.inclusion = inclusion;
      this.property = property;
      this.entries = entries;
    }

    public int inclusion() {
      return inclusion;
    }

    public String property() {
      return property;
    }

    public int entryCount() {
      return entries.length;
    }

    public SubtypeEntry entry(int index) {
      return entries[index];
    }
  }

  /** One subtype annotation entry using either a token or a binary-name string. */
  public static final class SubtypeEntry {
    private final int typeToken;
    private final String className;
    private final String name;

    SubtypeEntry(int typeToken, String className, String name) {
      this.typeToken = typeToken;
      this.className = className;
      this.name = name;
    }

    public int typeToken() {
      return typeToken;
    }

    public String className() {
      return className;
    }

    public String name() {
      return name;
    }
  }

  /** One field candidate. */
  public static final class Field {
    private final int declaringToken;
    private final String name;
    private final String descriptor;
    private final int modifiers;
    private final int typeNode;
    private final int flags;
    private final Property property;
    private final int operation;

    Field(
        int declaringToken,
        String name,
        String descriptor,
        int modifiers,
        int typeNode,
        int flags,
        Property property,
        int operation) {
      this.declaringToken = declaringToken;
      this.name = name;
      this.descriptor = descriptor;
      this.modifiers = modifiers;
      this.typeNode = typeNode;
      this.flags = flags;
      this.property = property;
      this.operation = operation;
    }

    public int declaringToken() {
      return declaringToken;
    }

    public String name() {
      return name;
    }

    public String descriptor() {
      return descriptor;
    }

    public int modifiers() {
      return modifiers;
    }

    public int typeNode() {
      return typeNode;
    }

    public int flags() {
      return flags;
    }

    public Property property() {
      return property;
    }

    public int operation() {
      return operation;
    }
  }

  /** One public method candidate. */
  public static final class Method {
    private final int declaringToken;
    private final String name;
    private final String descriptor;
    private final int modifiers;
    private final int returnNode;
    private final int[] parameterNodes;
    private final int flags;
    private final Property property;
    private final int operation;

    Method(
        int declaringToken,
        String name,
        String descriptor,
        int modifiers,
        int returnNode,
        int[] parameterNodes,
        int flags,
        Property property,
        int operation) {
      this.declaringToken = declaringToken;
      this.name = name;
      this.descriptor = descriptor;
      this.modifiers = modifiers;
      this.returnNode = returnNode;
      this.parameterNodes = parameterNodes;
      this.flags = flags;
      this.property = property;
      this.operation = operation;
    }

    public int declaringToken() {
      return declaringToken;
    }

    public String name() {
      return name;
    }

    public String descriptor() {
      return descriptor;
    }

    public int modifiers() {
      return modifiers;
    }

    public int returnNode() {
      return returnNode;
    }

    public int parameterCount() {
      return parameterNodes.length;
    }

    public int parameterNode(int index) {
      return parameterNodes[index];
    }

    public int flags() {
      return flags;
    }

    public Property property() {
      return property;
    }

    public int operation() {
      return operation;
    }
  }

  /** One constructor or static factory candidate. */
  public static final class Creator {
    private final int executableKind;
    private final int declaringToken;
    private final String name;
    private final String descriptor;
    private final int modifiers;
    private final String[] propertyNames;
    private final int[] parameterNodes;
    private final Property[] parameterProperties;
    private final int operation;

    Creator(
        int executableKind,
        int declaringToken,
        String name,
        String descriptor,
        int modifiers,
        String[] propertyNames,
        int[] parameterNodes,
        Property[] parameterProperties,
        int operation) {
      this.executableKind = executableKind;
      this.declaringToken = declaringToken;
      this.name = name;
      this.descriptor = descriptor;
      this.modifiers = modifiers;
      this.propertyNames = propertyNames;
      this.parameterNodes = parameterNodes;
      this.parameterProperties = parameterProperties;
      this.operation = operation;
    }

    public int executableKind() {
      return executableKind;
    }

    public int declaringToken() {
      return declaringToken;
    }

    public String name() {
      return name;
    }

    public String descriptor() {
      return descriptor;
    }

    public int modifiers() {
      return modifiers;
    }

    public int propertyNameCount() {
      return propertyNames.length;
    }

    public String propertyName(int index) {
      return propertyNames[index];
    }

    public int parameterCount() {
      return parameterNodes.length;
    }

    public int parameterNode(int index) {
      return parameterNodes[index];
    }

    public Property parameterProperty(int index) {
      return parameterProperties[index];
    }

    public int operation() {
      return operation;
    }
  }

  /** One record component candidate. */
  public static final class RecordComponent {
    private final int declaringToken;
    private final String name;
    private final int typeNode;
    private final String accessorDescriptor;
    private final int flags;
    private final Property property;

    RecordComponent(
        int declaringToken,
        String name,
        int typeNode,
        String accessorDescriptor,
        int flags,
        Property property) {
      this.declaringToken = declaringToken;
      this.name = name;
      this.typeNode = typeNode;
      this.accessorDescriptor = accessorDescriptor;
      this.flags = flags;
      this.property = property;
    }

    public int declaringToken() {
      return declaringToken;
    }

    public String name() {
      return name;
    }

    public int typeNode() {
      return typeNode;
    }

    public String accessorDescriptor() {
      return accessorDescriptor;
    }

    public int flags() {
      return flags;
    }

    public Property property() {
      return property;
    }
  }

  /** One nearest property-order annotation. */
  public static final class PropertyOrder {
    private final int declaringToken;
    private final String[] names;
    private final boolean alphabetic;

    PropertyOrder(int declaringToken, String[] names, boolean alphabetic) {
      this.declaringToken = declaringToken;
      this.names = names;
      this.alphabetic = alphabetic;
    }

    public int declaringToken() {
      return declaringToken;
    }

    public int nameCount() {
      return names.length;
    }

    public String name(int index) {
      return names[index];
    }

    public boolean alphabetic() {
      return alphabetic;
    }
  }

  /** One no-argument or canonical-record construction fact. */
  public static final class Instantiator {
    private final int kind;
    private final int operation;

    Instantiator(int kind, int operation) {
      this.kind = kind;
      this.operation = operation;
    }

    public int kind() {
      return kind;
    }

    public int operation() {
      return operation;
    }
  }

  /** Generated generic superclass and interface edges for one declaration. */
  public static final class Hierarchy {
    private final int declaringToken;
    private final int superclassNode;
    private final int[] interfaceNodes;

    Hierarchy(int declaringToken, int superclassNode, int[] interfaceNodes) {
      this.declaringToken = declaringToken;
      this.superclassNode = superclassNode;
      this.interfaceNodes = interfaceNodes;
    }

    public int declaringToken() {
      return declaringToken;
    }

    public int superclassNode() {
      return superclassNode;
    }

    public int interfaceCount() {
      return interfaceNodes.length;
    }

    public int interfaceNode(int index) {
      return interfaceNodes[index];
    }
  }
}
