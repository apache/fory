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

final class SourceField {
  enum AccessKind {
    ACCESSOR,
    FIELD,
    METHOD
  }

  final int id;
  final String name;
  final String erasedType;
  final SourceTypeNode typeNode;
  final int modifiers;
  final String declaringClass;
  final boolean serialized;
  final boolean arrayType;
  final AccessKind readAccessKind;
  final String readAccess;
  final AccessKind writeAccessKind;
  final String writeAccess;
  final boolean hasForyField;
  final int foryFieldId;
  final boolean nullable;
  final boolean trackingRef;
  final boolean hasTrackingRefMetadata;
  final String dynamic;

  SourceField(
      int id,
      String name,
      String erasedType,
      SourceTypeNode typeNode,
      int modifiers,
      String declaringClass,
      boolean serialized,
      boolean arrayType,
      AccessKind readAccessKind,
      String readAccess,
      AccessKind writeAccessKind,
      String writeAccess,
      boolean hasForyField,
      int foryFieldId,
      boolean nullable,
      boolean trackingRef,
      boolean hasTrackingRefMetadata,
      String dynamic) {
    this.id = id;
    this.name = name;
    this.erasedType = erasedType;
    this.typeNode = typeNode;
    this.modifiers = modifiers;
    this.declaringClass = declaringClass;
    this.serialized = serialized;
    this.arrayType = arrayType;
    this.readAccessKind = readAccessKind;
    this.readAccess = readAccess;
    this.writeAccessKind = writeAccessKind;
    this.writeAccess = writeAccess;
    this.hasForyField = hasForyField;
    this.foryFieldId = foryFieldId;
    this.nullable = nullable;
    this.trackingRef = trackingRef;
    this.hasTrackingRefMetadata = hasTrackingRefMetadata;
    this.dynamic = dynamic;
  }

  String readExpression(String target) {
    if (readAccessKind == AccessKind.ACCESSOR) {
      return fieldAccessorName() + ".get(" + target + ")";
    }
    if (readAccessKind == AccessKind.METHOD) {
      return target + "." + readAccess + "()";
    }
    return target + "." + readAccess;
  }

  String writeStatement(String target, String valueExpression) {
    if (writeAccessKind == AccessKind.ACCESSOR) {
      return fieldAccessorName() + ".set(" + target + ", " + valueExpression + ");";
    }
    if (writeAccessKind == AccessKind.METHOD) {
      return target + "." + writeAccess + "(" + valueExpression + ");";
    }
    return target + "." + writeAccess + " = " + valueExpression + ";";
  }

  boolean usesFieldAccessor() {
    return readAccessKind == AccessKind.ACCESSOR || writeAccessKind == AccessKind.ACCESSOR;
  }

  String fieldAccessorName() {
    return "fieldAccessor" + id;
  }

  String defaultValue() {
    switch (erasedType) {
      case "boolean":
        return "false";
      case "byte":
        return "(byte) 0";
      case "char":
        return "(char) 0";
      case "short":
        return "(short) 0";
      case "int":
        return "0";
      case "long":
        return "0L";
      case "float":
        return "0.0f";
      case "double":
        return "0.0d";
      default:
        return "null";
    }
  }

  String castExpression(String valueExpression) {
    switch (erasedType) {
      case "boolean":
        return "((Boolean) " + valueExpression + ").booleanValue()";
      case "byte":
        return "((Byte) " + valueExpression + ").byteValue()";
      case "char":
        return "((Character) " + valueExpression + ").charValue()";
      case "short":
        return "((Short) " + valueExpression + ").shortValue()";
      case "int":
        return "((Integer) " + valueExpression + ").intValue()";
      case "long":
        return "((Long) " + valueExpression + ").longValue()";
      case "float":
        return "((Float) " + valueExpression + ").floatValue()";
      case "double":
        return "((Double) " + valueExpression + ").doubleValue()";
      default:
        return "(" + erasedType + ") " + valueExpression;
    }
  }
}
