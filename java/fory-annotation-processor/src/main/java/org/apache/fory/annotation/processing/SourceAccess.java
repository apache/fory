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

import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Java source-nameability and member-expression access from a generated package peer. */
final class SourceAccess {
  private final Elements elements;
  private final Types types;
  private final String generatedPackage;

  SourceAccess(Elements elements, Types types, String generatedPackage) {
    this.elements = elements;
    this.types = types;
    this.generatedPackage = generatedPackage;
  }

  boolean type(TypeMirror mirror) {
    TypeKind kind = mirror.getKind();
    if (kind.isPrimitive() || kind == TypeKind.VOID) {
      return true;
    }
    if (kind == TypeKind.ARRAY) {
      return type(((ArrayType) mirror).getComponentType());
    }
    if (kind == TypeKind.TYPEVAR) {
      return type(((TypeVariable) mirror).getUpperBound());
    }
    if (kind == TypeKind.WILDCARD) {
      WildcardType wildcard = (WildcardType) mirror;
      return (wildcard.getExtendsBound() == null || type(wildcard.getExtendsBound()))
          && (wildcard.getSuperBound() == null || type(wildcard.getSuperBound()));
    }
    if (!(mirror instanceof DeclaredType)) {
      return false;
    }
    DeclaredType declared = (DeclaredType) mirror;
    Element element = declared.asElement();
    if (!(element instanceof TypeElement) || !typeElement((TypeElement) element)) {
      return false;
    }
    TypeMirror owner = declared.getEnclosingType();
    if (owner != null && owner.getKind() != TypeKind.NONE && !type(owner)) {
      return false;
    }
    for (TypeMirror argument : declared.getTypeArguments()) {
      if (!type(argument)) {
        return false;
      }
    }
    return true;
  }

  boolean field(VariableElement field) {
    return member(field) && type(field.asType());
  }

  boolean method(ExecutableElement method) {
    if (!member(method) || !type(method.getReturnType())) {
      return false;
    }
    for (VariableElement parameter : method.getParameters()) {
      if (!type(parameter.asType())) {
        return false;
      }
    }
    return true;
  }

  boolean constructor(ExecutableElement constructor) {
    if (!member(constructor)) {
      return false;
    }
    for (VariableElement parameter : constructor.getParameters()) {
      if (!type(parameter.asType())) {
        return false;
      }
    }
    return true;
  }

  boolean creator(ExecutableElement creator) {
    return creator.getKind() == ElementKind.CONSTRUCTOR ? constructor(creator) : method(creator);
  }

  String sourceName(TypeMirror mirror) {
    TypeKind kind = mirror.getKind();
    if (kind.isPrimitive() || kind == TypeKind.VOID) {
      return mirror.toString();
    }
    if (kind == TypeKind.ARRAY) {
      return sourceName(((ArrayType) mirror).getComponentType()) + "[]";
    }
    TypeMirror erased = types.erasure(mirror);
    Element element = types.asElement(erased);
    if (!(element instanceof TypeElement)) {
      throw new ProcessingException("Cannot name source type " + mirror, null);
    }
    return ((TypeElement) element).getQualifiedName().toString();
  }

  private boolean member(Element member) {
    Element declaring = member.getEnclosingElement();
    if (!(declaring instanceof TypeElement) || !typeElement((TypeElement) declaring)) {
      return false;
    }
    Set<Modifier> modifiers = member.getModifiers();
    if (modifiers.contains(Modifier.PRIVATE)) {
      return false;
    }
    if (modifiers.contains(Modifier.PUBLIC)) {
      return true;
    }
    PackageElement ownerPackage = elements.getPackageOf(declaring);
    return ownerPackage.getQualifiedName().contentEquals(generatedPackage);
  }

  private boolean typeElement(TypeElement type) {
    for (Element current = type;
        current instanceof TypeElement;
        current = current.getEnclosingElement()) {
      Set<Modifier> modifiers = current.getModifiers();
      if (modifiers.contains(Modifier.PRIVATE)) {
        return false;
      }
      if (!modifiers.contains(Modifier.PUBLIC)
          && !elements.getPackageOf(current).getQualifiedName().contentEquals(generatedPackage)) {
        return false;
      }
    }
    return true;
  }
}
