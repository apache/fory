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
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.resolver.JsonSharedRegistry;

/** Immutable result of selecting and validating one declared {@link JsonCreator}. */
@Internal
public final class JsonCreatorDeclaration {
  private final Executable executable;
  private final JsonCreator annotation;

  private JsonCreatorDeclaration(Executable executable, JsonCreator annotation) {
    this.executable = executable;
    this.annotation = annotation;
  }

  public Executable executable() {
    return executable;
  }

  public JsonCreator annotation() {
    return annotation;
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

  private static void validate(Class<?> type, Executable creator) {
    int modifiers = creator.getModifiers();
    if (!Modifier.isPublic(modifiers)
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
