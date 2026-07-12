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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.resolver.JsonTypeResolver;

/**
 * Immutable construction metadata for one property-based {@code JsonCreator}.
 *
 * <p>The interpreted path allocates exactly one fixed-size argument array per object. Generated
 * readers consume the same field metadata but invoke the executable directly with typed locals.
 */
@Internal
public final class JsonCreatorInfo {
  private final Class<?> ownerType;
  private final Executable executable;
  private final JsonCreatorFieldInfo[] fields;
  private final Object[] defaults;
  private final long[] hashes;

  public JsonCreatorInfo(
      Class<?> ownerType, Executable executable, JsonCreatorFieldInfo[] fields, Object[] defaults) {
    this.ownerType = ownerType;
    this.executable = executable;
    this.fields = fields;
    this.defaults = defaults;
    hashes = new long[fields.length];
    for (int i = 0; i < fields.length; i++) {
      hashes[i] = fields[i].nameHash();
    }
  }

  public Executable executable() {
    return executable;
  }

  public JsonCreatorFieldInfo[] fields() {
    return fields;
  }

  public Object[] newArguments() {
    return Arrays.copyOf(defaults, defaults.length);
  }

  public int index(long hash) {
    // Creator arity is deliberately finite and normally small. A linear exact-hash table avoids a
    // second object graph and is allocation-free; construction rejects every hash collision.
    for (int i = 0; i < hashes.length; i++) {
      if (hashes[i] == hash) {
        return i;
      }
    }
    return -1;
  }

  public void resolveTypes(JsonTypeResolver resolver) {
    for (JsonCreatorFieldInfo field : fields) {
      field.resolveType(resolver);
    }
  }

  public Object create(Object[] arguments) {
    try {
      Object value;
      if (executable instanceof Constructor) {
        value = ((Constructor<?>) executable).newInstance(arguments);
      } else {
        value = ((Method) executable).invoke(null, arguments);
      }
      if (value == null || value.getClass() != ownerType) {
        throw new ForyJsonException(
            "JSON creator must return an exact non-null " + ownerType.getName());
      }
      return value;
    } catch (InstantiationException | IllegalAccessException e) {
      throw new ForyJsonException("Failed to invoke JSON creator for " + ownerType.getName(), e);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
    }
  }
}
