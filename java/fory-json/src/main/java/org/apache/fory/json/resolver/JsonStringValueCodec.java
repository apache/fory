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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Complete String representation selected by one effective {@code JsonValue} member. */
final class JsonStringValueCodec implements JsonValueCodec<Object> {
  private final Class<?> ownerType;
  private final JsonFieldAccessor accessor;
  private final ValueCreator creator;
  private final boolean raw;

  JsonStringValueCodec(
      Class<?> ownerType, JsonFieldAccessor accessor, Executable creator, boolean raw) {
    this.ownerType = ownerType;
    this.accessor = accessor;
    this.creator = creator == null ? null : new ValueCreator(ownerType, creator);
    this.raw = raw;
  }

  @Override
  public void writeString(StringJsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    String string = (String) accessor.getObject(value);
    if (string == null) {
      writer.writeNull();
    } else if (raw) {
      writer.writeRawValue(string);
    } else {
      writer.writeString(string);
    }
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    String string = (String) accessor.getObject(value);
    if (string == null) {
      writer.writeNull();
    } else if (raw) {
      writer.writeRawValue(string);
    } else {
      writer.writeString(string);
    }
  }

  @Override
  public Object readLatin1(Latin1JsonReader reader) {
    if (reader.peekNull()) {
      reader.readNull();
      return null;
    }
    return read(reader.readString());
  }

  @Override
  public Object readUtf16(Utf16JsonReader reader) {
    if (reader.peekNull()) {
      reader.readNull();
      return null;
    }
    return read(reader.readString());
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader) {
    if (reader.peekNull()) {
      reader.readNull();
      return null;
    }
    return read(reader.readString());
  }

  private Object read(String value) {
    if (raw) {
      throw new ForyJsonException(
          "Combined @JsonValue and @JsonRawValue representation is write-only for "
              + ownerType.getName());
    }
    if (creator == null) {
      throw new ForyJsonException(
          "Reading @JsonValue type "
              + ownerType.getName()
              + " requires a one-String-argument @JsonCreator");
    }
    return creator.create(value);
  }

  private static final class ValueCreator {
    private final Class<?> ownerType;
    private final Executable executable;
    private final MethodHandle invoker;

    private ValueCreator(Class<?> ownerType, Executable executable) {
      this.ownerType = ownerType;
      this.executable = executable;
      if (AndroidSupport.IS_ANDROID) {
        executable.setAccessible(true);
        invoker = null;
      } else {
        invoker = buildInvoker(ownerType, executable);
      }
    }

    private Object create(String value) {
      if (invoker != null) {
        return invoke(value);
      }
      try {
        Object result;
        if (executable instanceof Constructor) {
          result = ((Constructor<?>) executable).newInstance(value);
        } else {
          result = ((Method) executable).invoke(null, value);
        }
        return requireResult(result);
      } catch (InstantiationException | IllegalAccessException e) {
        throw invocationFailure(e);
      } catch (InvocationTargetException e) {
        throw creatorFailure(e.getCause());
      }
    }

    private Object invoke(String value) {
      try {
        Object result = (Object) invoker.invokeExact(value);
        return requireResult(result);
      } catch (Throwable cause) {
        throw creatorFailure(cause);
      }
    }

    private Object requireResult(Object result) {
      if (result == null || result.getClass() != ownerType) {
        throw new ForyJsonException(
            "JSON creator must return an exact non-null " + ownerType.getName());
      }
      return result;
    }

    private ForyJsonException invocationFailure(Throwable cause) {
      return new ForyJsonException(
          "Failed to invoke JSON creator for " + ownerType.getName(), cause);
    }

    private ForyJsonException creatorFailure(Throwable cause) {
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      return new ForyJsonException("JSON creator failed for " + ownerType.getName(), cause);
    }

    private static MethodHandle buildInvoker(Class<?> ownerType, Executable executable) {
      try {
        MethodHandle target =
            executable instanceof Constructor
                ? _JDKAccess._trustedLookup(ownerType)
                    .unreflectConstructor((Constructor<?>) executable)
                : _JDKAccess._trustedLookup(ownerType).unreflect((Method) executable);
        return target.asType(MethodType.methodType(Object.class, String.class));
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot access JSON creator for " + ownerType.getName(), e);
      }
    }
  }
}
