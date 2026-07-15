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
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/** Strongly typed reads of source annotation mirrors without runtime annotation proxies. */
final class SourceAnnotations {
  private final Elements elements;

  SourceAnnotations(Elements elements) {
    this.elements = elements;
  }

  AnnotationMirror find(Element element, String name) {
    return find(element.getAnnotationMirrors(), name);
  }

  AnnotationMirror find(TypeMirror type, String name) {
    return find(type.getAnnotationMirrors(), name);
  }

  AnnotationMirror find(List<? extends AnnotationMirror> mirrors, String name) {
    for (AnnotationMirror mirror : mirrors) {
      Element annotation = mirror.getAnnotationType().asElement();
      if (annotation instanceof TypeElement
          && ((TypeElement) annotation).getQualifiedName().contentEquals(name)) {
        return mirror;
      }
    }
    return null;
  }

  boolean has(Element element, String name) {
    return find(element, name) != null;
  }

  String string(AnnotationMirror mirror, String name, String defaultValue) {
    Object value = value(mirror, name);
    return value == null ? defaultValue : (String) value;
  }

  boolean bool(AnnotationMirror mirror, String name, boolean defaultValue) {
    Object value = value(mirror, name);
    return value == null ? defaultValue : (Boolean) value;
  }

  int integer(AnnotationMirror mirror, String name, int defaultValue) {
    Object value = value(mirror, name);
    return value == null ? defaultValue : ((Number) value).intValue();
  }

  int enumOrdinal(AnnotationMirror mirror, String name, int defaultValue) {
    Object value = value(mirror, name);
    if (!(value instanceof javax.lang.model.element.VariableElement)) {
      return defaultValue;
    }
    javax.lang.model.element.VariableElement constant =
        (javax.lang.model.element.VariableElement) value;
    List<javax.lang.model.element.VariableElement> constants =
        javax.lang.model.util.ElementFilter.fieldsIn(
            constant.getEnclosingElement().getEnclosedElements());
    for (int i = 0; i < constants.size(); i++) {
      if (constants.get(i).equals(constant)) {
        return i;
      }
    }
    return defaultValue;
  }

  TypeMirror type(AnnotationMirror mirror, String name) {
    Object value = value(mirror, name);
    return value instanceof TypeMirror ? (TypeMirror) value : null;
  }

  List<String> strings(AnnotationMirror mirror, String name) {
    List<? extends AnnotationValue> values = array(mirror, name);
    List<String> result = new ArrayList<>(values.size());
    for (AnnotationValue value : values) {
      result.add((String) value.getValue());
    }
    return result;
  }

  List<AnnotationMirror> annotations(AnnotationMirror mirror, String name) {
    List<? extends AnnotationValue> values = array(mirror, name);
    List<AnnotationMirror> result = new ArrayList<>(values.size());
    for (AnnotationValue value : values) {
      Object annotation = value.getValue();
      if (annotation instanceof AnnotationMirror) {
        result.add((AnnotationMirror) annotation);
      }
    }
    return result;
  }

  private List<? extends AnnotationValue> array(AnnotationMirror mirror, String name) {
    Object value = value(mirror, name);
    if (value instanceof List<?>) {
      @SuppressWarnings("unchecked")
      List<? extends AnnotationValue> result = (List<? extends AnnotationValue>) value;
      return result;
    }
    return Collections.emptyList();
  }

  private Object value(AnnotationMirror mirror, String name) {
    if (mirror == null) {
      return null;
    }
    Map<? extends ExecutableElement, ? extends AnnotationValue> values =
        elements.getElementValuesWithDefaults(mirror);
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        values.entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(name)) {
        return entry.getValue().getValue();
      }
    }
    return null;
  }
}
