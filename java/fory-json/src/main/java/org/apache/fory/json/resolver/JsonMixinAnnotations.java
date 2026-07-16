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
import java.lang.invoke.MethodHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
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
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.ReflectionUtils;

/** Resolves immutable annotation overlays for the exact mix-ins enabled by one runtime. */
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
    for (Class<?> targetType : config.mixinTargets()) {
      Class<?> mixinType = config.mixinType(targetType);
      resolved.put(targetType, resolve(targetType, mixinType));
    }
    overlays = resolved;
  }

  TargetOverlay overlay(Class<?> targetType) {
    return overlays.get(targetType);
  }

  static TargetOverlay resolve(Class<?> targetType, Class<?> mixinType) {
    validateAssociation(targetType, mixinType);
    GeneratedRequirements generated = validateGeneratedMetadata(targetType, mixinType);
    Map<AnnotatedElement, ElementOverlay> declarations = new HashMap<>();
    addOverlay(declarations, targetType, elementOverlay(mixinType, ElementType.TYPE));

    for (Field sourceField : mixinType.getDeclaredFields()) {
      if (sourceField.isSynthetic() || !hasConfiguration(sourceField)) {
        continue;
      }
      if (Modifier.isStatic(sourceField.getModifiers())) {
        throw invalidSelector(targetType, mixinType, sourceField, "must be an instance field");
      }
      Field targetField = matchField(targetType, mixinType, sourceField);
      addOverlay(declarations, targetField, elementOverlay(sourceField, ElementType.FIELD));
    }

    for (Method sourceMethod : mixinType.getDeclaredMethods()) {
      if (sourceMethod.isSynthetic() || sourceMethod.isBridge()) {
        continue;
      }
      if (!hasConfiguration(sourceMethod) && !hasParameterConfiguration(sourceMethod)) {
        continue;
      }
      if (!Modifier.isAbstract(sourceMethod.getModifiers())) {
        throw invalidSelector(targetType, mixinType, sourceMethod, "must be abstract");
      }
      Method targetMethod = matchMethod(targetType, mixinType, sourceMethod);
      if (mentions(sourceMethod, JsonCreator.class)
          && targetMethod.getDeclaringClass() != targetType) {
        throw invalidSelector(
            targetType, mixinType, sourceMethod, "cannot select an inherited @JsonCreator factory");
      }
      addOverlay(declarations, targetMethod, elementOverlay(sourceMethod, ElementType.METHOD));
      addParameterOverlays(declarations, sourceMethod, targetMethod);
    }

    for (Constructor<?> sourceConstructor : mixinType.getDeclaredConstructors()) {
      if (!hasConfiguration(sourceConstructor) && !hasParameterConfiguration(sourceConstructor)) {
        continue;
      }
      Constructor<?> targetConstructor = matchConstructor(targetType, mixinType, sourceConstructor);
      addOverlay(
          declarations,
          targetConstructor,
          elementOverlay(sourceConstructor, ElementType.CONSTRUCTOR));
      addParameterOverlays(
          declarations,
          sourceConstructor,
          targetConstructor,
          constructorParameterOffset(mixinType, sourceConstructor));
    }
    return new TargetOverlay(
        targetType, mixinType, generated.codecRequired, generated.valueMetadata, declarations);
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
          "Invalid JSON mix-in association " + mixinType.getName() + " -> " + targetType.getName());
    }
    int sourceModifiers = mixinType.getModifiers();
    if (mixinType.isAnnotation()
        || mixinType.isEnum()
        || mixinType.isAnonymousClass()
        || mixinType.isLocalClass()
        || (!mixinType.isInterface() && !Modifier.isAbstract(sourceModifiers))) {
      throw new ForyJsonException(
          "JSON mix-in source must be a named interface or abstract class: " + mixinType.getName());
    }
    if (mixinType.getInterfaces().length != 0
        || (!mixinType.isInterface() && mixinType.getSuperclass() != Object.class)) {
      throw new ForyJsonException(
          "JSON mix-in source must not extend or implement another type: " + mixinType.getName());
    }
    if (targetType == mixinType
        || targetType.isPrimitive()
        || targetType.isArray()
        || targetType.isAnnotation()) {
      throw new ForyJsonException(
          "Invalid JSON mix-in target " + targetType.getTypeName() + " for " + mixinType.getName());
    }
  }

  private static GeneratedRequirements validateGeneratedMetadata(
      Class<?> targetType, Class<?> mixinType) {
    String name = JsonSharedRegistry.generatedMixinMetadataBinaryName(mixinType, targetType);
    Class<?> metadataClass;
    try {
      metadataClass = Class.forName(name, false, mixinType.getClassLoader());
    } catch (ClassNotFoundException e) {
      if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
        throw new ForyJsonException(
            "Missing generated JSON mix-in metadata "
                + name
                + " for "
                + targetType.getName()
                + " and "
                + mixinType.getName());
      }
      return GeneratedRequirements.NONE;
    } catch (LinkageError e) {
      throw new ForyJsonException("Cannot load generated JSON mix-in metadata " + name, e);
    }
    if (!GeneratedJsonMixinMetadata.class.isAssignableFrom(metadataClass)) {
      throw new ForyJsonException(
          "Generated JSON mix-in metadata does not implement "
              + GeneratedJsonMixinMetadata.class.getName()
              + ": "
              + name);
    }
    GeneratedJsonMixinMetadata metadata = newGeneratedMetadata(metadataClass);
    String targetName;
    String mixinName;
    boolean codecRequired;
    boolean valueMetadata;
    try {
      targetName = metadata.targetName();
      mixinName = metadata.mixinName();
      codecRequired = metadata.codecRequired();
      valueMetadata = metadata.valueMetadata();
    } catch (RuntimeException | LinkageError e) {
      throw new ForyJsonException(
          "Cannot read generated JSON mix-in metadata " + metadataClass.getName(), e);
    }
    if (!targetType.getName().equals(targetName) || !mixinType.getName().equals(mixinName)) {
      throw new ForyJsonException(
          "Generated JSON mix-in metadata identity does not match "
              + targetType.getName()
              + " and "
              + mixinType.getName());
    }
    ClassLoader metadataLoader = metadataClass.getClassLoader();
    requireMetadataClass(metadataLoader, targetType, metadataClass);
    requireMetadataClass(metadataLoader, mixinType, metadataClass);
    return new GeneratedRequirements(codecRequired, valueMetadata);
  }

  private static GeneratedJsonMixinMetadata newGeneratedMetadata(Class<?> metadataClass) {
    Constructor<?> publicConstructor;
    try {
      publicConstructor = metadataClass.getConstructor();
    } catch (NoSuchMethodException e) {
      throw new ForyJsonException(
          "Generated JSON mix-in metadata must declare a public no-argument constructor: "
              + metadataClass.getName(),
          e);
    }
    if (!Modifier.isPublic(metadataClass.getModifiers())) {
      throw new ForyJsonException(
          "Generated JSON mix-in metadata must be public: " + metadataClass.getName());
    }
    if (AndroidSupport.IS_ANDROID) {
      try {
        return (GeneratedJsonMixinMetadata) publicConstructor.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new ForyJsonException(
            "Cannot construct generated JSON mix-in metadata " + metadataClass.getName(),
            unwrap(e));
      }
    }
    try {
      // Generated metadata belongs to the source module and may be in a package that is not
      // exported or opened to Fory. The trusted handle is used only during pair validation.
      MethodHandle constructor = ReflectionUtils.getCtrHandle(metadataClass);
      return (GeneratedJsonMixinMetadata) constructor.invoke();
    } catch (Throwable e) {
      throw new ForyJsonException(
          "Cannot construct generated JSON mix-in metadata " + metadataClass.getName(), unwrap(e));
    }
  }

  private static Throwable unwrap(Throwable failure) {
    return failure instanceof InvocationTargetException
        ? ((InvocationTargetException) failure).getTargetException()
        : failure;
  }

  private static void requireMetadataClass(
      ClassLoader loader, Class<?> expected, Class<?> metadataClass) {
    Class<?> resolved;
    try {
      resolved = Class.forName(expected.getName(), false, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      throw new ForyJsonException(
          "Generated JSON mix-in metadata "
              + metadataClass.getName()
              + " cannot resolve "
              + expected.getName(),
          e);
    }
    if (resolved != expected) {
      throw new ForyJsonException(
          "Generated JSON mix-in metadata "
              + metadataClass.getName()
              + " resolves a different "
              + expected.getName());
    }
  }

  private static void addParameterOverlays(
      Map<AnnotatedElement, ElementOverlay> declarations, Executable source, Executable target) {
    addParameterOverlays(declarations, source, target, 0);
  }

  private static void addParameterOverlays(
      Map<AnnotatedElement, ElementOverlay> declarations,
      Executable source,
      Executable target,
      int sourceOffset) {
    Parameter[] sourceParameters = source.getParameters();
    Parameter[] targetParameters = target.getParameters();
    for (int i = sourceOffset; i < sourceParameters.length; i++) {
      if (hasConfiguration(sourceParameters[i])) {
        addOverlay(
            declarations,
            targetParameters[i - sourceOffset],
            elementOverlay(sourceParameters[i], ElementType.PARAMETER));
      }
    }
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
      throw new ForyJsonException("Duplicate JSON mix-in selector for " + target);
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
      throw new ForyJsonException("@JsonType cannot be declared by a JSON mix-in: " + source);
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
              "Unsupported JSON mix-in removal " + removedType.getName() + " on " + source);
        }
        if (!supportsElement(removedType, elementType)) {
          throw new ForyJsonException(
              "JSON mix-in cannot remove @"
                  + removedType.getSimpleName()
                  + " from "
                  + elementType
                  + ' '
                  + source);
        }
        if (!removals.add(removedType)) {
          throw new ForyJsonException(
              "Duplicate JSON mix-in removal " + removedType.getName() + " on " + source);
        }
        if (replacements.containsKey(removedType)) {
          throw new ForyJsonException(
              "JSON mix-in both declares and removes " + removedType.getName() + " on " + source);
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
    int sourceOffset = constructorParameterOffset(mixinType, source);
    for (Constructor<?> constructor : targetType.getDeclaredConstructors()) {
      if (!constructor.isSynthetic()
          && sourceParameters.length - sourceOffset == constructor.getParameterCount()
          && parametersEqual(sourceParameters, sourceOffset, constructor.getParameterTypes())) {
        matches.add(constructor);
      }
    }
    return oneMatch(targetType, mixinType, source, matches);
  }

  private static int constructorParameterOffset(
      Class<?> mixinType, Constructor<?> sourceConstructor) {
    Class<?> enclosingType = mixinType.getEnclosingClass();
    Class<?>[] parameters = sourceConstructor.getParameterTypes();
    return enclosingType != null
            && mixinType.isMemberClass()
            && !Modifier.isStatic(mixinType.getModifiers())
            && parameters.length != 0
            && parameters[0] == enclosingType
        ? 1
        : 0;
  }

  private static boolean parametersEqual(Class<?>[] source, int sourceOffset, Class<?>[] target) {
    for (int i = 0; i < target.length; i++) {
      if (source[i + sourceOffset] != target[i]) {
        return false;
      }
    }
    return true;
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
        "Invalid JSON mix-in selector "
            + source
            + " from "
            + mixinType.getName()
            + " for "
            + targetType.getName()
            + ": "
            + reason);
  }

  private static boolean hasParameterConfiguration(Executable executable) {
    for (Parameter parameter : executable.getParameters()) {
      if (hasConfiguration(parameter)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasConfiguration(AnnotatedElement element) {
    if (declaredAnnotation(element, JsonMixinRemove.class) != null
        || declaredAnnotation(element, JsonType.class) != null) {
      return true;
    }
    for (Class<? extends Annotation> annotationType : SUPPORTED) {
      if (declaredAnnotation(element, annotationType) != null) {
        return true;
      }
    }
    return false;
  }

  private static boolean mentions(
      AnnotatedElement element, Class<? extends Annotation> annotationType) {
    if (declaredAnnotation(element, annotationType) != null) {
      return true;
    }
    JsonMixinRemove remove = declaredAnnotation(element, JsonMixinRemove.class);
    if (remove == null) {
      return false;
    }
    for (Class<? extends Annotation> removed : remove.value()) {
      if (removed == annotationType) {
        return true;
      }
    }
    return false;
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
    private final boolean codecRequired;
    private final boolean valueMetadata;
    private final Map<AnnotatedElement, ElementOverlay> declarations;
    private final Set<AnnotatedElement> sourceDeclarations;

    private TargetOverlay(
        Class<?> targetType,
        Class<?> mixinType,
        boolean codecRequired,
        boolean valueMetadata,
        Map<AnnotatedElement, ElementOverlay> declarations) {
      this.targetType = targetType;
      this.mixinType = mixinType;
      this.codecRequired = codecRequired;
      this.valueMetadata = valueMetadata;
      this.declarations = Collections.unmodifiableMap(new HashMap<>(declarations));
      Set<AnnotatedElement> sources = new HashSet<>();
      for (ElementOverlay declaration : declarations.values()) {
        sources.add(declaration.source);
      }
      sourceDeclarations = Collections.unmodifiableSet(sources);
    }

    Class<?> targetType() {
      return targetType;
    }

    Class<?> mixinType() {
      return mixinType;
    }

    boolean codecRequired() {
      return codecRequired;
    }

    boolean valueMetadata() {
      return valueMetadata;
    }

    Set<AnnotatedElement> sourceDeclarations() {
      return sourceDeclarations;
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

    boolean replaces(AnnotatedElement target, Class<? extends Annotation> annotationType) {
      ElementOverlay overlay = declarations.get(target);
      return overlay != null && overlay.replacements.containsKey(annotationType);
    }

    void validateTypeReplacements(
        Class<? extends Annotation> annotationType, String representation) {
      List<String> unused = new ArrayList<>();
      for (Map.Entry<AnnotatedElement, ElementOverlay> entry : declarations.entrySet()) {
        for (Class<? extends Annotation> replacement : entry.getValue().replacements.keySet()) {
          if (entry.getKey() != targetType || replacement != annotationType) {
            unused.add(unusedReplacement(entry, replacement));
          }
        }
      }
      rejectUnused(unused, representation);
    }

    void validateValueReplacements(List<? extends Member> valueMembers, Executable creator) {
      List<String> unused = new ArrayList<>();
      for (Map.Entry<AnnotatedElement, ElementOverlay> entry : declarations.entrySet()) {
        AnnotatedElement target = entry.getKey();
        for (Class<? extends Annotation> replacement : entry.getValue().replacements.keySet()) {
          boolean consumed =
              contains(valueMembers, target)
                      && (replacement == JsonValue.class || replacement == JsonRawValue.class)
                  || target.equals(creator) && replacement == JsonCreator.class
                  || target instanceof Parameter
                      && ((Parameter) target).getDeclaringExecutable().equals(creator)
                      && replacement == JsonProperty.class;
          if (!consumed) {
            unused.add(unusedReplacement(entry, replacement));
          }
        }
      }
      rejectUnused(unused, "@JsonValue representation");
    }

    void validateNoReplacements(String representation) {
      List<String> unused = new ArrayList<>();
      for (Map.Entry<AnnotatedElement, ElementOverlay> entry : declarations.entrySet()) {
        for (Class<? extends Annotation> replacement : entry.getValue().replacements.keySet()) {
          unused.add(unusedReplacement(entry, replacement));
        }
      }
      rejectUnused(unused, representation);
    }

    private static boolean contains(List<? extends Member> declarations, AnnotatedElement target) {
      for (Member declaration : declarations) {
        if (declaration.equals(target)) {
          return true;
        }
      }
      return false;
    }

    private static String unusedReplacement(
        Map.Entry<AnnotatedElement, ElementOverlay> entry,
        Class<? extends Annotation> annotationType) {
      return "@"
          + annotationType.getSimpleName()
          + " on mix-in selector "
          + entry.getValue().source
          + " matched to "
          + entry.getKey();
    }

    private static void rejectUnused(List<String> unused, String representation) {
      if (unused.isEmpty()) {
        return;
      }
      Collections.sort(unused);
      throw new ForyJsonException(
          unused.get(0) + " is not consumed by the selected " + representation);
    }
  }

  private static final class GeneratedRequirements {
    private static final GeneratedRequirements NONE = new GeneratedRequirements(false, false);

    private final boolean codecRequired;
    private final boolean valueMetadata;

    private GeneratedRequirements(boolean codecRequired, boolean valueMetadata) {
      this.codecRequired = codecRequired;
      this.valueMetadata = valueMetadata;
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
  }
}
