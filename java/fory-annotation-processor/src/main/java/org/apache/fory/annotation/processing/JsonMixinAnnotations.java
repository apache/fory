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

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Resolves one Mixin source into an immutable effective-annotation table for its exact target. */
final class JsonMixinAnnotations {
  private static final String JSON_PACKAGE = "org.apache.fory.json";
  private static final String JSON_MIXIN_REMOVE = JSON_PACKAGE + ".annotation.JsonMixinRemove";
  private static final String JSON_TYPE = JSON_PACKAGE + ".annotation.JsonType";
  private static final String JSON_ANY_GETTER = JSON_PACKAGE + ".annotation.JsonAnyGetter";
  private static final String JSON_ANY_PROPERTY = JSON_PACKAGE + ".annotation.JsonAnyProperty";
  private static final String JSON_ANY_SETTER = JSON_PACKAGE + ".annotation.JsonAnySetter";
  private static final String JSON_BASE64 = JSON_PACKAGE + ".annotation.JsonBase64";
  private static final String JSON_CODEC = JSON_PACKAGE + ".annotation.JsonCodec";
  private static final String JSON_CREATOR = JSON_PACKAGE + ".annotation.JsonCreator";
  private static final String JSON_IGNORE = JSON_PACKAGE + ".annotation.JsonIgnore";
  private static final String JSON_PROPERTY = JSON_PACKAGE + ".annotation.JsonProperty";
  private static final String JSON_PROPERTY_ORDER = JSON_PACKAGE + ".annotation.JsonPropertyOrder";
  private static final String JSON_RAW_VALUE = JSON_PACKAGE + ".annotation.JsonRawValue";
  private static final String JSON_SUB_TYPES = JSON_PACKAGE + ".annotation.JsonSubTypes";
  private static final String JSON_UNWRAPPED = JSON_PACKAGE + ".annotation.JsonUnwrapped";
  private static final String JSON_VALUE = JSON_PACKAGE + ".annotation.JsonValue";

  private static final Set<String> MAPPING_ANNOTATIONS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              Arrays.asList(
                  JSON_ANY_GETTER,
                  JSON_ANY_PROPERTY,
                  JSON_ANY_SETTER,
                  JSON_BASE64,
                  JSON_CODEC,
                  JSON_CREATOR,
                  JSON_IGNORE,
                  JSON_PROPERTY,
                  JSON_PROPERTY_ORDER,
                  JSON_RAW_VALUE,
                  JSON_SUB_TYPES,
                  JSON_UNWRAPPED,
                  JSON_VALUE)));

  private final TypeElement target;
  private final TypeElement source;
  private final Map<Element, Overlay> overlays;

  private JsonMixinAnnotations(
      TypeElement target, TypeElement source, Map<Element, Overlay> overlays) {
    this.target = target;
    this.source = source;
    this.overlays = overlays;
  }

  static JsonMixinAnnotations resolve(
      Elements elements, Types types, TypeElement target, TypeElement source) {
    validateTypes(target, source);
    Map<Element, Overlay> overlays = new IdentityHashMap<>();
    Overlay typeOverlay = overlay(source);
    if (typeOverlay != null) {
      overlays.put(target, typeOverlay);
    }
    for (VariableElement sourceField : ElementFilter.fieldsIn(source.getEnclosedElements())) {
      Overlay overlay = overlay(sourceField);
      if (overlay != null) {
        if (sourceField.getModifiers().contains(Modifier.STATIC)) {
          throw invalid("@JsonMixin field selector must be an instance field", sourceField);
        }
        overlays.put(matchField(elements, types, target, source, sourceField), overlay);
      }
    }
    for (ExecutableElement sourceMethod : ElementFilter.methodsIn(source.getEnclosedElements())) {
      Overlay methodOverlay = overlay(sourceMethod);
      ExecutableElement targetMethod = null;
      if (methodOverlay != null || hasParameterOverlay(sourceMethod)) {
        if (!sourceMethod.getModifiers().contains(Modifier.ABSTRACT)) {
          throw invalid("@JsonMixin method selector must be abstract", sourceMethod);
        }
        targetMethod =
            matchMethod(
                elements,
                types,
                target,
                source,
                sourceMethod,
                methodOverlay != null && methodOverlay.mentions(JSON_CREATOR));
      }
      if (methodOverlay != null) {
        overlays.put(targetMethod, methodOverlay);
      }
      if (targetMethod != null) {
        matchParameters(overlays, sourceMethod, targetMethod);
      }
    }
    for (ExecutableElement sourceConstructor :
        ElementFilter.constructorsIn(source.getEnclosedElements())) {
      Overlay constructorOverlay = overlay(sourceConstructor);
      ExecutableElement targetConstructor = null;
      if (constructorOverlay != null || hasParameterOverlay(sourceConstructor)) {
        targetConstructor = matchConstructor(types, target, source, sourceConstructor);
      }
      if (constructorOverlay != null) {
        overlays.put(targetConstructor, constructorOverlay);
      }
      if (targetConstructor != null) {
        matchParameters(overlays, sourceConstructor, targetConstructor);
      }
    }
    return new JsonMixinAnnotations(
        target, source, Collections.unmodifiableMap(new IdentityHashMap<>(overlays)));
  }

  TypeElement target() {
    return target;
  }

  TypeElement source() {
    return source;
  }

  boolean isEmpty() {
    return overlays.isEmpty();
  }

  AnnotationMirror annotation(Element element, String annotationName) {
    Overlay overlay = overlays.get(element);
    if (overlay != null) {
      AnnotationMirror replacement = overlay.replacements.get(annotationName);
      if (replacement != null) {
        return replacement;
      }
      if (overlay.removals.contains(annotationName)) {
        return null;
      }
    }
    if (element instanceof TypeElement
        && !element.equals(target)
        && (annotationName.equals(JSON_CODEC) || annotationName.equals(JSON_PROPERTY_ORDER))
        && isRemoved(target, annotationName)) {
      return null;
    }
    return declaredAnnotation(element, annotationName);
  }

  private boolean isRemoved(Element element, String annotationName) {
    Overlay overlay = overlays.get(element);
    return overlay != null && overlay.removals.contains(annotationName);
  }

  boolean hasJsonAnnotations(Element element) {
    for (String annotationName : MAPPING_ANNOTATIONS) {
      if (annotation(element, annotationName) != null) {
        return true;
      }
    }
    return false;
  }

  void collectAnnotations(Element element, Set<String> annotationTypes) {
    for (String annotationName : MAPPING_ANNOTATIONS) {
      if (annotation(element, annotationName) != null) {
        annotationTypes.add(annotationName);
      }
    }
  }

  List<Element> sourceSelectors() {
    List<Element> selectors = new ArrayList<>(overlays.size());
    for (Overlay overlay : overlays.values()) {
      selectors.add(overlay.source);
    }
    return selectors;
  }

  List<Element> targetSelectors() {
    return new ArrayList<>(overlays.keySet());
  }

  private static void validateTypes(TypeElement target, TypeElement source) {
    ElementKind sourceKind = source.getKind();
    NestingKind nestingKind = source.getNestingKind();
    if (!(sourceKind == ElementKind.INTERFACE
            || sourceKind == ElementKind.CLASS && source.getModifiers().contains(Modifier.ABSTRACT))
        || nestingKind == NestingKind.LOCAL
        || nestingKind == NestingKind.ANONYMOUS
        || nestingKind == NestingKind.MEMBER && !source.getModifiers().contains(Modifier.STATIC)) {
      throw invalid("@JsonMixin source must be a named interface or static abstract class", source);
    }
    ElementKind targetKind = target.getKind();
    if (!(targetKind == ElementKind.CLASS
        || targetKind == ElementKind.INTERFACE
        || targetKind == ElementKind.ENUM
        || targetKind.name().equals("RECORD"))) {
      throw invalid("@JsonMixin target must be a class, interface, enum, or Record", source);
    }
    if (target.equals(source)) {
      throw invalid("@JsonMixin source and target must be different types", source);
    }
    if (!source.getInterfaces().isEmpty()) {
      throw invalid("@JsonMixin source must not extend or implement another type", source);
    }
    TypeMirror superclass = source.getSuperclass();
    if (sourceKind == ElementKind.CLASS
        && superclass.getKind() != TypeKind.NONE
        && !superclass.toString().equals("java.lang.Object")) {
      throw invalid("@JsonMixin source must not extend or implement another type", source);
    }
  }

  private static VariableElement matchField(
      Elements elements,
      Types types,
      TypeElement target,
      TypeElement source,
      VariableElement selector) {
    List<VariableElement> matches = new ArrayList<>();
    TypeElement current = target;
    while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
      for (VariableElement field : ElementFilter.fieldsIn(current.getEnclosedElements())) {
        if (field.getKind() != ElementKind.ENUM_CONSTANT
            && !field.getModifiers().contains(Modifier.STATIC)
            && field.getSimpleName().contentEquals(selector.getSimpleName())
            && sameErasure(types, field.asType(), selector.asType())) {
          matches.add(field);
        }
      }
      TypeMirror superclass = current.getSuperclass();
      Element superElement =
          superclass.getKind() == TypeKind.NONE ? null : types.asElement(superclass);
      current = superElement instanceof TypeElement ? (TypeElement) superElement : null;
    }
    return oneMatch(elements, target, source, selector, "field", matches);
  }

  private static ExecutableElement matchMethod(
      Elements elements,
      Types types,
      TypeElement target,
      TypeElement source,
      ExecutableElement selector,
      boolean creatorSelector) {
    List<ExecutableElement> matches = new ArrayList<>();
    Set<ExecutableElement> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    if (creatorSelector) {
      for (ExecutableElement method : ElementFilter.methodsIn(target.getEnclosedElements())) {
        if (matchesMethod(types, selector, method) && seen.add(method)) {
          matches.add(method);
        }
      }
    } else {
      for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(target))) {
        if (method.getModifiers().contains(Modifier.PUBLIC)
            && matchesMethod(types, selector, method)
            && seen.add(method)) {
          matches.add(method);
        }
      }
      for (TypeElement owner : classHierarchy(types, target)) {
        for (ExecutableElement method : ElementFilter.methodsIn(owner.getEnclosedElements())) {
          if (!method.getModifiers().contains(Modifier.PUBLIC)
              && matchesMethod(types, selector, method)
              && seen.add(method)) {
            matches.add(method);
          }
        }
      }
    }
    return oneMatch(elements, target, source, selector, "method", matches);
  }

  private static List<TypeElement> classHierarchy(Types types, TypeElement target) {
    List<TypeElement> hierarchy = new ArrayList<>();
    TypeElement current = target;
    while (current != null && !current.getQualifiedName().contentEquals("java.lang.Object")) {
      hierarchy.add(current);
      TypeMirror superclass = current.getSuperclass();
      Element superElement =
          superclass.getKind() == TypeKind.NONE ? null : types.asElement(superclass);
      current = superElement instanceof TypeElement ? (TypeElement) superElement : null;
    }
    return hierarchy;
  }

  private static boolean matchesMethod(
      Types types, ExecutableElement selector, ExecutableElement candidate) {
    if (!candidate.getSimpleName().contentEquals(selector.getSimpleName())
        || !sameErasure(types, candidate.getReturnType(), selector.getReturnType())) {
      return false;
    }
    List<? extends VariableElement> sourceParameters = selector.getParameters();
    List<? extends VariableElement> targetParameters = candidate.getParameters();
    if (sourceParameters.size() != targetParameters.size()) {
      return false;
    }
    for (int i = 0; i < sourceParameters.size(); i++) {
      if (!sameErasure(types, sourceParameters.get(i).asType(), targetParameters.get(i).asType())) {
        return false;
      }
    }
    return true;
  }

  private static ExecutableElement matchConstructor(
      Types types, TypeElement target, TypeElement source, ExecutableElement selector) {
    List<ExecutableElement> matches = new ArrayList<>();
    for (ExecutableElement constructor :
        ElementFilter.constructorsIn(target.getEnclosedElements())) {
      if (sameParameters(types, selector, constructor)) {
        matches.add(constructor);
      }
    }
    return oneMatch(null, target, source, selector, "constructor", matches);
  }

  private static boolean sameParameters(
      Types types, ExecutableElement left, ExecutableElement right) {
    List<? extends VariableElement> leftParameters = left.getParameters();
    List<? extends VariableElement> rightParameters = right.getParameters();
    if (leftParameters.size() != rightParameters.size()) {
      return false;
    }
    for (int i = 0; i < leftParameters.size(); i++) {
      if (!sameErasure(types, leftParameters.get(i).asType(), rightParameters.get(i).asType())) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameErasure(Types types, TypeMirror left, TypeMirror right) {
    return types.isSameType(types.erasure(left), types.erasure(right));
  }

  private static void matchParameters(
      Map<Element, Overlay> overlays,
      ExecutableElement sourceExecutable,
      ExecutableElement targetExecutable) {
    List<? extends VariableElement> sourceParameters = sourceExecutable.getParameters();
    List<? extends VariableElement> targetParameters = targetExecutable.getParameters();
    for (int i = 0; i < sourceParameters.size(); i++) {
      Overlay overlay = overlay(sourceParameters.get(i));
      if (overlay != null) {
        overlays.put(targetParameters.get(i), overlay);
      }
    }
  }

  private static boolean hasParameterOverlay(ExecutableElement executable) {
    for (VariableElement parameter : executable.getParameters()) {
      if (overlay(parameter) != null) {
        return true;
      }
    }
    return false;
  }

  private static <T extends Element> T oneMatch(
      Elements elements,
      TypeElement target,
      TypeElement source,
      Element selector,
      String kind,
      List<T> matches) {
    if (matches.size() == 1) {
      return matches.get(0);
    }
    String targetName =
        elements == null
            ? target.getQualifiedName().toString()
            : elements.getBinaryName(target).toString();
    throw invalid(
        "@JsonMixin "
            + kind
            + " selector "
            + selector
            + " on "
            + source.getQualifiedName()
            + (matches.isEmpty() ? " does not match " : " ambiguously matches ")
            + targetName,
        selector);
  }

  private static Overlay overlay(Element sourceElement) {
    if (declaredAnnotation(sourceElement, JSON_TYPE) != null) {
      throw invalid("@JsonType cannot be declared by a JSON Mixin", sourceElement);
    }
    Map<String, AnnotationMirror> replacements = new LinkedHashMap<>();
    for (AnnotationMirror mirror : sourceElement.getAnnotationMirrors()) {
      String name = annotationName(mirror);
      if (MAPPING_ANNOTATIONS.contains(name)) {
        replacements.put(name, mirror);
      }
    }
    Set<String> removals = new LinkedHashSet<>();
    AnnotationMirror remove = declaredAnnotation(sourceElement, JSON_MIXIN_REMOVE);
    if (remove != null) {
      AnnotationValue value = annotationValue(remove, "value");
      if (value == null || !(value.getValue() instanceof List<?>)) {
        throw invalid("@JsonMixinRemove must declare at least one annotation", sourceElement);
      }
      for (Object rawEntry : (List<?>) value.getValue()) {
        Object entry =
            rawEntry instanceof AnnotationValue ? ((AnnotationValue) rawEntry).getValue() : null;
        if (!(entry instanceof TypeMirror)) {
          throw invalid("Invalid @JsonMixinRemove annotation type", sourceElement);
        }
        Element annotationElement =
            ((TypeMirror) entry).getKind() == TypeKind.DECLARED
                ? ((javax.lang.model.type.DeclaredType) entry).asElement()
                : null;
        if (!(annotationElement instanceof TypeElement)) {
          throw invalid("Invalid @JsonMixinRemove annotation type", sourceElement);
        }
        String name = ((TypeElement) annotationElement).getQualifiedName().toString();
        if (!MAPPING_ANNOTATIONS.contains(name)) {
          throw invalid("Unsupported @JsonMixinRemove annotation " + name, sourceElement);
        }
        if (!removals.add(name)) {
          throw invalid("Duplicate @JsonMixinRemove annotation " + name, sourceElement);
        }
        if (replacements.containsKey(name)) {
          throw invalid(
              "Cannot declare and remove " + name + " on the same selector", sourceElement);
        }
        validateRemovalKind(sourceElement, (TypeElement) annotationElement);
      }
      if (removals.isEmpty()) {
        throw invalid("@JsonMixinRemove must declare at least one annotation", sourceElement);
      }
    }
    return replacements.isEmpty() && removals.isEmpty()
        ? null
        : new Overlay(
            sourceElement,
            Collections.unmodifiableMap(replacements),
            Collections.unmodifiableSet(removals));
  }

  private static void validateRemovalKind(Element element, TypeElement annotationType) {
    ElementType elementType = elementType(element.getKind());
    Target target = annotationType.getAnnotation(Target.class);
    if (elementType != null
        && (target == null || Arrays.asList(target.value()).contains(elementType))) {
      return;
    }
    throw invalid(
        "Cannot remove " + annotationType.getQualifiedName() + " from " + element.getKind(),
        element);
  }

  private static ElementType elementType(ElementKind kind) {
    if (kind == ElementKind.CLASS
        || kind == ElementKind.INTERFACE
        || kind == ElementKind.ENUM
        || kind.name().equals("RECORD")) {
      return ElementType.TYPE;
    }
    if (kind == ElementKind.FIELD) {
      return ElementType.FIELD;
    }
    if (kind == ElementKind.METHOD) {
      return ElementType.METHOD;
    }
    if (kind == ElementKind.CONSTRUCTOR) {
      return ElementType.CONSTRUCTOR;
    }
    if (kind == ElementKind.PARAMETER) {
      return ElementType.PARAMETER;
    }
    return null;
  }

  private static AnnotationMirror declaredAnnotation(Element element, String annotationName) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      if (annotationName(mirror).equals(annotationName)) {
        return mirror;
      }
    }
    return null;
  }

  private static String annotationName(AnnotationMirror mirror) {
    Element annotationElement = mirror.getAnnotationType().asElement();
    return annotationElement instanceof TypeElement
        ? ((TypeElement) annotationElement).getQualifiedName().toString()
        : mirror.getAnnotationType().toString();
  }

  private static AnnotationValue annotationValue(AnnotationMirror annotation, String name) {
    Element annotationElement = annotation.getAnnotationType().asElement();
    if (!(annotationElement instanceof TypeElement)) {
      return null;
    }
    for (Element enclosed : annotationElement.getEnclosedElements()) {
      if (enclosed instanceof ExecutableElement && enclosed.getSimpleName().contentEquals(name)) {
        ExecutableElement member = (ExecutableElement) enclosed;
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
            annotation.getElementValues().entrySet()) {
          if (entry.getKey().equals(member)) {
            return entry.getValue();
          }
        }
        return member.getDefaultValue();
      }
    }
    return null;
  }

  private static InvalidJsonMixinException invalid(String message, Element element) {
    return new InvalidJsonMixinException(message, element);
  }

  static final class InvalidJsonMixinException extends RuntimeException {
    final Element element;

    InvalidJsonMixinException(String message, Element element) {
      super(message);
      this.element = element;
    }
  }

  private static final class Overlay {
    final Element source;
    final Map<String, AnnotationMirror> replacements;
    final Set<String> removals;

    Overlay(Element source, Map<String, AnnotationMirror> replacements, Set<String> removals) {
      this.source = source;
      this.replacements = replacements;
      this.removals = removals;
    }

    boolean mentions(String annotationName) {
      return replacements.containsKey(annotationName) || removals.contains(annotationName);
    }
  }
}
