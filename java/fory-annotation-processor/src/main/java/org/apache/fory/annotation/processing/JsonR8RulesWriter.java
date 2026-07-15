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

import java.util.LinkedHashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.apache.fory.json.meta.JsonClassNames;

/** Writes precise Android-targeted R8 rules from the same access decisions as generated code. */
final class JsonR8RulesWriter {
  private final JsonSourceModel model;
  private final Elements elements;
  private final Types types;

  JsonR8RulesWriter(JsonSourceModel model, Elements elements, Types types) {
    this.model = model;
    this.elements = elements;
    this.types = types;
  }

  String write() {
    Set<String> classKeeps = new LinkedHashSet<>();
    Set<String> memberKeeps = new LinkedHashSet<>();
    classKeeps.add(model.targetBinaryName);
    for (int section = 0; section < 3; section++) {
      JsonSourceModel.Section value = model.section(section);
      for (JsonSourceModel.Token token : value.tokens) {
        if (!token.primitive() && !token.direct) {
          classKeeps.add(JsonClassNames.requireBinaryName(token.binaryName, "generated metadata"));
        }
      }
      for (JsonSourceModel.Fact fact : value.facts) {
        if (fact instanceof JsonSourceModel.SubtypesFact) {
          for (JsonSourceModel.SubtypeEntry entry : ((JsonSourceModel.SubtypesFact) fact).entries) {
            if (!entry.className.isEmpty()) {
              classKeeps.add(
                  JsonClassNames.requireBinaryName(entry.className, "@JsonSubTypes className"));
            }
          }
        }
      }
      for (JsonSourceModel.Operation operation : value.operations) {
        if (!operation.direct) {
          memberKeeps.add(memberRule(operation));
        }
      }
    }
    StringBuilder builder = new StringBuilder(2048);
    for (String className : classKeeps) {
      builder.append("-keep,allowoptimization class ").append(className).append(" {\n}\n\n");
    }
    builder
        .append("-keep,allowoptimization class ")
        .append(model.metadataBinaryName)
        .append(" extends org.apache.fory.json.meta.JsonTypeMetadata {\n")
        .append("  public <init>(java.lang.Class);\n")
        .append("  public java.lang.Object metadata(int);\n")
        .append("  public java.lang.Class metadataType(int,int);\n")
        .append("  public java.lang.Object metadataOperation(int,int);\n")
        .append("}\n\n");
    for (String rule : memberKeeps) {
      builder.append(rule);
    }
    return builder.toString();
  }

  private String memberRule(JsonSourceModel.Operation operation) {
    Element member = operation.member;
    TypeElement owner = (TypeElement) member.getEnclosingElement();
    StringBuilder builder =
        new StringBuilder("-keepclassmembers,allowoptimization class ")
            .append(elements.getBinaryName(owner))
            .append(" {\n  ");
    if (member instanceof VariableElement) {
      VariableElement field = (VariableElement) member;
      builder.append(r8Type(field.asType())).append(' ').append(field.getSimpleName()).append(';');
    } else {
      ExecutableElement executable = (ExecutableElement) member;
      if (executable.getKind() == ElementKind.CONSTRUCTOR) {
        builder.append("<init>");
      } else {
        builder
            .append(r8Type(executable.getReturnType()))
            .append(' ')
            .append(executable.getSimpleName());
      }
      builder.append('(');
      for (int i = 0; i < executable.getParameters().size(); i++) {
        if (i != 0) {
          builder.append(',');
        }
        builder.append(r8Type(executable.getParameters().get(i).asType()));
      }
      builder.append(");");
    }
    return builder.append("\n}\n\n").toString();
  }

  private String r8Type(TypeMirror type) {
    TypeKind kind = type.getKind();
    if (kind.isPrimitive() || kind == TypeKind.VOID) {
      return type.toString();
    }
    if (kind == TypeKind.ARRAY) {
      return r8Type(((ArrayType) type).getComponentType()) + "[]";
    }
    TypeElement element = (TypeElement) types.asElement(types.erasure(type));
    return elements.getBinaryName(element).toString();
  }
}
