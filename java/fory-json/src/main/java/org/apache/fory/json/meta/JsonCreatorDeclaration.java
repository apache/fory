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

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.codec.JsonObjectModel;
import org.apache.fory.json.resolver.JsonSharedRegistry;

/** Immutable result of selecting and validating one declared {@link JsonCreator}. */
@Internal
public final class JsonCreatorDeclaration {
  private final Executable executable;
  private final Executable annotationSource;
  private final JsonCreator annotation;

  private JsonCreatorDeclaration(Executable executable, JsonCreator annotation) {
    this(executable, executable, annotation);
  }

  private JsonCreatorDeclaration(
      Executable executable, Executable annotationSource, JsonCreator annotation) {
    this.executable = executable;
    this.annotationSource = annotationSource;
    this.annotation = annotation;
  }

  public Executable executable() {
    return executable;
  }

  public JsonCreator annotation() {
    return annotation;
  }

  /** Returns the exact executable which owns the effective creator and parameter annotations. */
  public Executable annotationSource() {
    return annotationSource;
  }

  public static JsonCreatorDeclaration find(Class<?> type, JsonSharedRegistry registry) {
    Executable creator = null;
    JsonCreator annotation = null;
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      JsonCreator candidate = registry.annotation(type, constructor, JsonCreator.class);
      if (candidate != null) {
        validate(type, constructor);
        if (creator != null) {
          throw multipleCreatorsException(type);
        }
        creator = constructor;
        annotation = candidate;
      }
    }
    for (Method method : type.getDeclaredMethods()) {
      JsonCreator candidate = registry.annotation(type, method, JsonCreator.class);
      if (candidate != null) {
        validate(type, method);
        if (creator != null) {
          throw multipleCreatorsException(type);
        }
        creator = method;
        annotation = candidate;
      }
    }
    return creator == null ? null : new JsonCreatorDeclaration(creator, annotation);
  }

  /** Selects the one logical language creator while collapsing only proven default overloads. */
  public static JsonCreatorDeclaration find(
      Class<?> type, JsonSharedRegistry registry, JsonObjectModel objectModel) {
    if (objectModel == null) {
      return find(type, registry);
    }
    Executable expected = objectModel.creator();
    Executable invocation = objectModel.invocationCreator();
    Constructor<?> defaultConstructor = objectModel.defaultConstructor();
    JsonCreatorDeclaration selected = null;
    boolean unexpected = false;
    List<JsonCreatorDeclaration> declarations = findAll(type, registry);
    for (JsonCreatorDeclaration declaration : declarations) {
      Executable candidate = declaration.executable;
      if (candidate.equals(expected) || candidate.equals(invocation)) {
        validate(type, expected, invocation);
        if (selected != null
            && !Arrays.equals(selected.annotation.value(), declaration.annotation.value())) {
          unexpected = true;
        } else if (selected == null || candidate.equals(expected)) {
          // The annotation can live on the exact compiler accessibility constructor while the
          // logical constructor remains the sole construction schema owner.
          selected = new JsonCreatorDeclaration(expected, candidate, declaration.annotation);
        }
      } else if (candidate.equals(defaultConstructor)) {
        // Compiler-default constructors are an exact model-owned copy of the logical declaration.
      } else if (!isDefaultOverload(candidate, expected, objectModel.defaultMaskBits())) {
        validate(type, candidate);
        unexpected = true;
      }
    }
    if (unexpected || selected == null && !declarations.isEmpty()) {
      throw multipleCreatorsException(type);
    }
    return selected;
  }

  /** Returns every effective creator declaration for exact language metadata mapping. */
  public static List<JsonCreatorDeclaration> findAll(Class<?> type, JsonSharedRegistry registry) {
    ArrayList<JsonCreatorDeclaration> declarations = new ArrayList<>();
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      JsonCreator annotation = registry.annotation(type, constructor, JsonCreator.class);
      if (annotation == null) {
        continue;
      }
      declarations.add(new JsonCreatorDeclaration(constructor, annotation));
    }
    for (Method method : type.getDeclaredMethods()) {
      JsonCreator annotation = registry.annotation(type, method, JsonCreator.class);
      if (annotation == null || method.isSynthetic() || method.isBridge()) {
        continue;
      }
      declarations.add(new JsonCreatorDeclaration(method, annotation));
    }
    return Collections.unmodifiableList(declarations);
  }

  private static boolean isDefaultOverload(
      Executable candidate, Executable expected, int[] defaultMaskBits) {
    if (!(candidate instanceof Constructor)
        || !(expected instanceof Constructor)
        || candidate.getDeclaringClass() != expected.getDeclaringClass()) {
      return false;
    }
    Class<?>[] candidateTypes = candidate.getParameterTypes();
    Class<?>[] expectedTypes = expected.getParameterTypes();
    if (candidateTypes.length >= expectedTypes.length) {
      return false;
    }
    for (int i = 0; i < candidateTypes.length; i++) {
      if (candidateTypes[i] != expectedTypes[i]) {
        return false;
      }
    }
    for (int i = candidateTypes.length; i < expectedTypes.length; i++) {
      if (i >= defaultMaskBits.length || defaultMaskBits[i] < 0) {
        return false;
      }
    }
    return true;
  }

  private static void validate(Class<?> type, Executable creator) {
    validate(type, creator, creator);
  }

  private static void validate(Class<?> type, Executable creator, Executable invocationCreator) {
    int modifiers = creator.getModifiers();
    boolean invocableConstructor =
        creator instanceof Constructor
            && invocationCreator instanceof Constructor
            && invocationCreator != creator
            && invocationCreator.getDeclaringClass() == type
            && Modifier.isPublic(invocationCreator.getModifiers());
    if ((!Modifier.isPublic(modifiers) && !invocableConstructor)
        || creator.isSynthetic()
        || creator.isVarArgs()
        || creator.getParameterCount() == 0
        || creator.getTypeParameters().length != 0) {
      throw new ForyJsonException("Invalid @JsonCreator executable " + creator);
    }
    if (creator instanceof Method) {
      Method factory = (Method) creator;
      if (!Modifier.isStatic(modifiers) || factory.isBridge() || factory.getReturnType() != type) {
        throw new ForyJsonException("Invalid @JsonCreator factory " + factory);
      }
    }
  }

  private static ForyJsonException multipleCreatorsException(Class<?> type) {
    return new ForyJsonException("Multiple @JsonCreator declarations on " + type.getName());
  }
}
