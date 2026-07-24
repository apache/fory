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
import java.util.List;

final class SourceTypeNode {
  final String rawType;
  final String typeName;
  final String typeExtMeta;
  final List<SourceTypeNode> typeArguments;
  final SourceTypeNode componentType;
  final SourceTypeNode ownerType;
  final boolean primitive;
  final boolean nestedCompatibleStruct;

  SourceTypeNode(
      String rawType,
      String typeName,
      String typeExtMeta,
      List<SourceTypeNode> typeArguments,
      SourceTypeNode componentType,
      SourceTypeNode ownerType,
      boolean primitive,
      boolean nestedCompatibleStruct) {
    this.rawType = rawType;
    this.typeName = typeName;
    this.typeExtMeta = typeExtMeta;
    this.typeArguments =
        typeArguments == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(typeArguments));
    this.componentType = componentType;
    this.ownerType = ownerType;
    this.primitive = primitive;
    this.nestedCompatibleStruct = nestedCompatibleStruct;
  }

  String generatedTypeExpression() {
    // Generated serializers must not reference TypeRef directly: Android javac resolves TypeRef's
    // AnnotatedType overloads while compiling generated source.
    if (typeExtMeta == null
        && typeArguments.isEmpty()
        && componentType == null
        && ownerType == null) {
      return "Descriptor.generatedType(" + rawType + ".class)";
    }
    return "Descriptor.generatedType("
        + rawType
        + ".class, "
        + (typeExtMeta == null ? "null" : typeExtMeta)
        + ", "
        + typeArgumentsExpression()
        + ", "
        + (componentType == null ? "null" : componentType.generatedTypeExpression())
        + ", "
        + (ownerType == null ? "null" : ownerType.generatedTypeExpression())
        + ")";
  }

  private String typeArgumentsExpression() {
    if (typeArguments.isEmpty()) {
      return "null";
    }
    StringBuilder builder = new StringBuilder("Arrays.<Descriptor.GeneratedType>asList(");
    for (int i = 0; i < typeArguments.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      builder.append(typeArguments.get(i).generatedTypeExpression());
    }
    builder.append(')');
    return builder.toString();
  }

  boolean hasNestedCompatibleStruct() {
    if (nestedCompatibleStruct) {
      return true;
    }
    if (componentType != null && componentType.hasNestedCompatibleStruct()) {
      return true;
    }
    if (ownerType != null && ownerType.hasNestedCompatibleStruct()) {
      return true;
    }
    for (SourceTypeNode typeArgument : typeArguments) {
      if (typeArgument.hasNestedCompatibleStruct()) {
        return true;
      }
    }
    return false;
  }
}
