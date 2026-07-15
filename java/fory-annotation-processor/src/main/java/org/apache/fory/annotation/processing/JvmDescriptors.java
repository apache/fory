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

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Exact JVM descriptors derived from compiler types. */
final class JvmDescriptors {
  private final Elements elements;
  private final Types types;

  JvmDescriptors(Elements elements, Types types) {
    this.elements = elements;
    this.types = types;
  }

  String field(VariableElement field) {
    return type(field.asType());
  }

  String executable(ExecutableElement executable) {
    StringBuilder builder = new StringBuilder("(");
    for (VariableElement parameter : executable.getParameters()) {
      builder.append(type(parameter.asType()));
    }
    builder.append(')');
    if (executable.getSimpleName().contentEquals("<init>")) {
      return builder.append('V').toString();
    }
    return builder.append(type(executable.getReturnType())).toString();
  }

  String binaryName(TypeElement type) {
    return elements.getBinaryName(type).toString();
  }

  private String type(TypeMirror mirror) {
    TypeKind kind = mirror.getKind();
    switch (kind) {
      case BOOLEAN:
        return "Z";
      case BYTE:
        return "B";
      case SHORT:
        return "S";
      case INT:
        return "I";
      case LONG:
        return "J";
      case CHAR:
        return "C";
      case FLOAT:
        return "F";
      case DOUBLE:
        return "D";
      case VOID:
        return "V";
      case ARRAY:
        return "[" + type(((ArrayType) mirror).getComponentType());
      default:
        TypeMirror erased = types.erasure(mirror);
        TypeElement element = (TypeElement) types.asElement(erased);
        if (element == null) {
          throw new ProcessingException("Cannot derive JVM descriptor for " + mirror, null);
        }
        return "L" + binaryName(element).replace('.', '/') + ";";
    }
  }
}
