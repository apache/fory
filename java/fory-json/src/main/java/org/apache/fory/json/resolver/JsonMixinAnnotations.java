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

package org.apache.fory.json.resolver;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonMixinRemove;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;

/** Resolves immutable annotation overlays for the exact Mixins enabled by one runtime. */
final class JsonMixinAnnotations {
  @SuppressWarnings("unchecked")
  private static final Class<? extends Annotation>[] SUPPORTED =
      new Class[] {
        JsonAnyGetter.class,
        JsonAnyProperty.class,
        JsonAnySetter.class,
        JsonBase64.class,
        JsonCodec.class,
        JsonCreator.class,
        JsonIgnore.class,
        JsonProperty.class,
        JsonPropertyOrder.class,
        JsonRawValue.class,
        JsonSubTypes.class,
        JsonUnwrapped.class,
        JsonValue.class
      };

  private static final Set<Class<? extends Annotation>> SUPPORTED_SET =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList(SUPPORTED)));

  private final IdentityHashMap<Class<?>, TargetOverlay> overlays;

  JsonMixinAnnotations(JsonConfig config) {
    IdentityHashMap<Class<?>, TargetOverlay> resolved = new IdentityHashMap<>();
    for (Map.Entry<Class<?>, Class<?>> entry : config.mixins().entrySet()) {
      Class<?> targetType = entry.getKey();
      Class<?> mixinType = entry.getValue();
      TargetOverlay overlay = resolve(targetType, mixinType);
      if (!overlay.isEmpty()) {
        resolved.put(targetType, overlay);
      }
    }
    overlays = resolved;
  }

  TargetOverlay overlay(Class<?> targetType) {
    return overlays.get(targetType);
  }

  static TargetOverlay resolve(Class<?> targetType, Class<?> mixinType) {
    validateAssociation(targetType, mixinType);
    Map<AnnotatedElement, ElementOverlay> declarations = new HashMap<>();
    addOverlay(declarations, targetType, elementOverlay(mixinType, ElementType.TYPE));

    for (Field sourceField : mixinType.getDeclaredFields()) {
      if (sourceField.isSynthetic()) {
        continue;
      }
      ElementOverlay sourceOverlay = elementOverlay(sourceField, ElementType.FIELD);
      if (sourceOverlay == null) {
        continue;
      }
      if (Modifier.isStatic(sourceField.getModifiers())) {
        throw invalidSelector(targetType, mixinType, sourceField, "must be an instance field");
      }
      Field targetField = matchField(targetType, mixinType, sourceField);
      addOverlay(declarations, targetField, sourceOverlay);
    }

    for (Method sourceMethod : mixinType.getDeclaredMethods()) {
      if (sourceMethod.isSynthetic() || sourceMethod.isBridge()) {
        continue;
      }
      ElementOverlay sourceOverlay = elementOverlay(sourceMethod, ElementType.METHOD);
      ElementOverlay[] parameterOverlays = parameterOverlays(sourceMethod);
      if (sourceOverlay == null && parameterOverlays == null) {
        continue;
      }
      if (!Modifier.isAbstract(sourceMethod.getModifiers())) {
        throw invalidSelector(targetType, mixinType, sourceMethod, "must be abstract");
      }
      Method targetMethod = matchMethod(targetType, mixinType, sourceMethod);
      if (sourceOverlay != null
          && sourceOverlay.mentions(JsonCreator.class)
          && targetMethod.getDeclaringClass() != targetType) {
        throw invalidSelector(
            targetType, mixinType, sourceMethod, "cannot select an inherited @JsonCreator factory");
      }
      addOverlay(declarations, targetMethod, sourceOverlay);
      addParameterOverlays(declarations, parameterOverlays, targetMethod);
    }

    for (Constructor<?> sourceConstructor : mixinType.getDeclaredConstructors()) {
      ElementOverlay sourceOverlay = elementOverlay(sourceConstructor, ElementType.CONSTRUCTOR);
      ElementOverlay[] parameterOverlays = parameterOverlays(sourceConstructor);
      if (sourceOverlay == null && parameterOverlays == null) {
        continue;
      }
      Constructor<?> targetConstructor = matchConstructor(targetType, mixinType, sourceConstructor);
      addOverlay(declarations, targetConstructor, sourceOverlay);
      addParameterOverlays(declarations, parameterOverlays, targetConstructor);
    }
    return new TargetOverlay(targetType, mixinType, declarations);
  }

  private static void validateAssociation(Class<?> targetType, Class<?> mixinType) {
    JsonMixin declaration;
    try {
      declaration = mixinType.getDeclaredAnnotation(JsonMixin.class);
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException("Cannot read @JsonMixin on " + mixinType.getName(), e);
    }
    if (declaration == null || declaration.target() != targetType) {
      throw new ForyJsonException(
          "Invalid JSON Mixin association " + mixinType.getName() + " -> " + targetType.getName());
    }
    int sourceModifiers = mixinType.getModifiers();
    if (mixinType.isAnnotation()
        || mixinType.isEnum()
        || mixinType.isAnonymousClass()
        || mixinType.isLocalClass()
        || mixinType.isMemberClass() && !Modifier.isStatic(sourceModifiers)
        || (!mixinType.isInterface() && !Modifier.isAbstract(sourceModifiers))) {
      throw new ForyJsonException(
          "JSON Mixin source must be a named interface or static abstract class: "
              + mixinType.getName());
    }
    if (mixinType.getInterfaces().length != 0
        || (!mixinType.isInterface() && mixinType.getSuperclass() != Object.class)) {
      throw new ForyJsonException(
          "JSON Mixin source must not extend or implement another type: " + mixinType.getName());
    }
    if (targetType == mixinType
        || targetType.isPrimitive()
        || targetType.isArray()
        || targetType.isAnnotation()) {
      throw new ForyJsonException(
          "Invalid JSON Mixin target " + targetType.getTypeName() + " for " + mixinType.getName());
    }
  }

  private static void addParameterOverlays(
      Map<AnnotatedElement, ElementOverlay> declarations,
      ElementOverlay[] overlays,
      Executable target) {
    if (overlays == null) {
      return;
    }
    Parameter[] targetParameters = target.getParameters();
    for (int i = 0; i < overlays.length; i++) {
      if (overlays[i] != null) {
        addOverlay(declarations, targetParameters[i], overlays[i]);
      }
    }
  }

  private static ElementOverlay[] parameterOverlays(Executable executable) {
    Parameter[] parameters = executable.getParameters();
    ElementOverlay[] overlays = null;
    for (int i = 0; i < parameters.length; i++) {
      ElementOverlay overlay = elementOverlay(parameters[i], ElementType.PARAMETER);
      if (overlay != null) {
        if (overlays == null) {
          overlays = new ElementOverlay[parameters.length];
        }
        overlays[i] = overlay;
      }
    }
    return overlays;
  }

  private static void addOverlay(
      Map<AnnotatedElement, ElementOverlay> declarations,
      AnnotatedElement target,
      ElementOverlay overlay) {
    if (overlay == null) {
      return;
    }
    ElementOverlay previous = declarations.put(target, overlay);
    if (previous != null) {
      throw new ForyJsonException("Duplicate JSON Mixin selector for " + target);
    }
  }

  private static ElementOverlay elementOverlay(AnnotatedElement source, ElementType elementType) {
    Map<Class<? extends Annotation>, Annotation> replacements = new HashMap<>();
    for (Class<? extends Annotation> annotationType : SUPPORTED) {
      Annotation annotation = declaredAnnotation(source, annotationType);
      if (annotation != null) {
        replacements.put(annotationType, annotation);
      }
    }
    if (declaredAnnotation(source, JsonType.class) != null) {
      throw new ForyJsonException("@JsonType cannot be declared by a JSON Mixin: " + source);
    }
    Set<Class<? extends Annotation>> removals = new HashSet<>();
    JsonMixinRemove remove = declaredAnnotation(source, JsonMixinRemove.class);
    if (remove != null) {
      Class<? extends Annotation>[] removedTypes;
      try {
        removedTypes = remove.value();
      } catch (RuntimeException | LinkageError e) {
        throw new ForyJsonException("Cannot resolve @JsonMixinRemove on " + source, e);
      }
      if (removedTypes.length == 0) {
        throw new ForyJsonException(
            "@JsonMixinRemove must name at least one annotation on " + source);
      }
      for (Class<? extends Annotation> removedType : removedTypes) {
        if (!SUPPORTED_SET.contains(removedType)) {
          throw new ForyJsonException(
              "Unsupported JSON Mixin removal " + removedType.getName() + " on " + source);
        }
        if (!supportsElement(removedType, elementType)) {
          throw new ForyJsonException(
              "JSON Mixin cannot remove @"
                  + removedType.getSimpleName()
                  + " from "
                  + elementType
                  + ' '
                  + source);
        }
        if (!removals.add(removedType)) {
          throw new ForyJsonException(
              "Duplicate JSON Mixin removal " + removedType.getName() + " on " + source);
        }
        if (replacements.containsKey(removedType)) {
          throw new ForyJsonException(
              "JSON Mixin both declares and removes " + removedType.getName() + " on " + source);
        }
      }
    }
    if (replacements.isEmpty() && removals.isEmpty()) {
      return null;
    }
    return new ElementOverlay(source, replacements, removals);
  }

  private static boolean supportsElement(
      Class<? extends Annotation> annotationType, ElementType elementType) {
    Target target = annotationType.getDeclaredAnnotation(Target.class);
    if (target == null) {
      return true;
    }
    for (ElementType supported : target.value()) {
      if (supported == elementType) {
        return true;
      }
    }
    return false;
  }

  private static Field matchField(Class<?> targetType, Class<?> mixinType, Field source) {
    List<Field> matches = new ArrayList<>();
    for (Class<?> current = targetType;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Field field : current.getDeclaredFields()) {
        if (!field.isSynthetic()
            && !Modifier.isStatic(field.getModifiers())
            && field.getName().equals(source.getName())
            && field.getType() == source.getType()) {
          matches.add(field);
        }
      }
    }
    return oneMatch(targetType, mixinType, source, matches);
  }

  private static Method matchMethod(Class<?> targetType, Class<?> mixinType, Method source) {
    Set<Method> candidates = new LinkedHashSet<>(Arrays.asList(targetType.getMethods()));
    for (Class<?> current = targetType;
        current != null && current != Object.class;
        current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())) {
          candidates.add(method);
        }
      }
    }
    List<Method> matches = new ArrayList<>();
    for (Method method : candidates) {
      if (!method.isSynthetic()
          && !method.isBridge()
          && method.getName().equals(source.getName())
          && method.getReturnType() == source.getReturnType()
          && Arrays.equals(method.getParameterTypes(), source.getParameterTypes())) {
        matches.add(method);
      }
    }
    return oneMatch(targetType, mixinType, source, matches);
  }

  private static Constructor<?> matchConstructor(
      Class<?> targetType, Class<?> mixinType, Constructor<?> source) {
    List<Constructor<?>> matches = new ArrayList<>();
    Class<?>[] sourceParameters = source.getParameterTypes();
    for (Constructor<?> constructor : targetType.getDeclaredConstructors()) {
      if (!constructor.isSynthetic()
          && Arrays.equals(sourceParameters, constructor.getParameterTypes())) {
        matches.add(constructor);
      }
    }
    return oneMatch(targetType, mixinType, source, matches);
  }

  private static <T extends AnnotatedElement> T oneMatch(
      Class<?> targetType, Class<?> mixinType, AnnotatedElement source, List<T> matches) {
    if (matches.size() != 1) {
      throw invalidSelector(
          targetType,
          mixinType,
          source,
          matches.isEmpty() ? "does not match a target declaration" : "is ambiguous");
    }
    return matches.get(0);
  }

  private static ForyJsonException invalidSelector(
      Class<?> targetType, Class<?> mixinType, AnnotatedElement source, String reason) {
    return new ForyJsonException(
        "Invalid JSON Mixin selector "
            + source
            + " from "
            + mixinType.getName()
            + " for "
            + targetType.getName()
            + ": "
            + reason);
  }

  private static <A extends Annotation> A declaredAnnotation(
      AnnotatedElement element, Class<A> annotationType) {
    try {
      return element.getDeclaredAnnotation(annotationType);
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot read @" + annotationType.getSimpleName() + " on " + element, e);
    }
  }

  static <A extends Annotation> A targetAnnotation(
      AnnotatedElement element, Class<A> annotationType) {
    validateTargetControl(element);
    return declaredAnnotation(element, annotationType);
  }

  static void validateTargetControl(AnnotatedElement element) {
    if (declaredAnnotation(element, JsonMixinRemove.class) != null
        && declaredAnnotation(declarationOwner(element), JsonMixin.class) == null) {
      throw new ForyJsonException(
          "@JsonMixinRemove is valid only inside a direct @JsonMixin source: " + element);
    }
  }

  private static Class<?> declarationOwner(AnnotatedElement element) {
    if (element instanceof Class<?>) {
      return (Class<?>) element;
    }
    if (element instanceof Member) {
      return ((Member) element).getDeclaringClass();
    }
    if (element instanceof Parameter) {
      return ((Parameter) element).getDeclaringExecutable().getDeclaringClass();
    }
    throw new ForyJsonException("Unsupported JSON annotation declaration " + element);
  }

  static final class TargetOverlay {
    private final Class<?> targetType;
    private final Class<?> mixinType;
    private final Map<AnnotatedElement, ElementOverlay> declarations;

    private TargetOverlay(
        Class<?> targetType,
        Class<?> mixinType,
        Map<AnnotatedElement, ElementOverlay> declarations) {
      this.targetType = targetType;
      this.mixinType = mixinType;
      this.declarations = Collections.unmodifiableMap(new HashMap<>(declarations));
    }

    Class<?> mixinType() {
      return mixinType;
    }

    boolean isEmpty() {
      return declarations.isEmpty();
    }

    Set<AnnotatedElement> sourceDeclarations() {
      Set<AnnotatedElement> sources = new HashSet<>();
      for (ElementOverlay declaration : declarations.values()) {
        sources.add(declaration.source);
      }
      return Collections.unmodifiableSet(sources);
    }

    Set<AnnotatedElement> targetDeclarations() {
      return declarations.keySet();
    }

    <A extends Annotation> A annotation(AnnotatedElement target, Class<A> annotationType) {
      validateTargetControl(target);
      ElementOverlay overlay = declarations.get(target);
      if (overlay != null) {
        Annotation replacement = overlay.replacements.get(annotationType);
        if (replacement != null) {
          return annotationType.cast(replacement);
        }
        if (overlay.removals.contains(annotationType)) {
          return null;
        }
      }
      if (target instanceof Class<?>
          && target != targetType
          && (annotationType == JsonCodec.class || annotationType == JsonPropertyOrder.class)
          && removed(targetType, annotationType)) {
        return null;
      }
      return declaredAnnotation(target, annotationType);
    }

    boolean removed(AnnotatedElement target, Class<? extends Annotation> annotationType) {
      ElementOverlay overlay = declarations.get(target);
      return overlay != null && overlay.removals.contains(annotationType);
    }
  }

  private static final class ElementOverlay {
    private final AnnotatedElement source;
    private final Map<Class<? extends Annotation>, Annotation> replacements;
    private final Set<Class<? extends Annotation>> removals;

    private ElementOverlay(
        AnnotatedElement source,
        Map<Class<? extends Annotation>, Annotation> replacements,
        Set<Class<? extends Annotation>> removals) {
      this.source = source;
      this.replacements = Collections.unmodifiableMap(new HashMap<>(replacements));
      this.removals = Collections.unmodifiableSet(new HashSet<>(removals));
    }

    private boolean mentions(Class<? extends Annotation> annotationType) {
      return replacements.containsKey(annotationType) || removals.contains(annotationType);
    }
  }
}
