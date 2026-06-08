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

package org.apache.fory.resolver;

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.exception.ForyException;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.StaticGeneratedStructSerializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.ExceptionUtils;

/** Shared registry of build-time generated static serializer mappings. */
@Internal
public final class StaticGeneratedSerializerRegistry {
  private static final String XLANG_SUFFIX = "_ForySerializer";
  private static final String NATIVE_SUFFIX = "_ForyNativeSerializer";

  public enum Mode {
    XLANG,
    NATIVE
  }

  private static final class Entry {
    private final Class<? extends StaticGeneratedStructSerializer> serializerClass;
    private final MethodHandle descriptorConstructor;
    private final MethodHandle runtimeConstructor;
    private final MethodHandle compatibleConstructor;

    private Entry(
        Class<? extends StaticGeneratedStructSerializer> serializerClass,
        MethodHandle descriptorConstructor,
        MethodHandle runtimeConstructor,
        MethodHandle compatibleConstructor) {
      this.serializerClass = serializerClass;
      this.descriptorConstructor = descriptorConstructor;
      this.runtimeConstructor = runtimeConstructor;
      this.compatibleConstructor = compatibleConstructor;
    }

    Class<? extends StaticGeneratedStructSerializer> getSerializerClass() {
      return serializerClass;
    }

    StaticGeneratedStructSerializer<?> newSerializer(
        TypeResolver resolver, Class<?> type, TypeDef typeDef) {
      try {
        return typeDef == null
            ? (StaticGeneratedStructSerializer<?>) runtimeConstructor.invoke(resolver, type)
            : (StaticGeneratedStructSerializer<?>)
                compatibleConstructor.invoke(resolver, type, typeDef);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }

    List<Descriptor> getGeneratedDescriptors() {
      try {
        return ((StaticGeneratedStructSerializer<?>) descriptorConstructor.invoke())
            .getGeneratedDescriptors();
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }
  }

  private final ConcurrentHashMap<Class<?>, Entry> xlangSerializers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Class<?>, Entry> nativeSerializers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Class<?>, Boolean> missingXlangSerializers =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Class<?>, Boolean> missingNativeSerializers =
      new ConcurrentHashMap<>();

  Class<? extends StaticGeneratedStructSerializer> getSerializerClass(
      Class<?> targetType, boolean xlang) {
    Entry entry = getEntry(targetType, xlang);
    return entry == null ? null : entry.getSerializerClass();
  }

  StaticGeneratedStructSerializer<?> newSerializer(
      TypeResolver resolver,
      Class<?> targetType,
      Class<? extends StaticGeneratedStructSerializer> serializerClass,
      TypeDef typeDef) {
    Entry entry = getEntry(targetType, resolver.isCrossLanguage());
    if (entry == null || entry.getSerializerClass() != serializerClass) {
      return null;
    }
    return entry.newSerializer(resolver, targetType, typeDef);
  }

  List<Descriptor> getGeneratedDescriptors(Class<?> targetType, boolean xlang) {
    Entry entry = getEntry(targetType, xlang);
    return entry == null ? null : entry.getGeneratedDescriptors();
  }

  private Entry getEntry(Class<?> targetType, boolean xlang) {
    ConcurrentHashMap<Class<?>, Entry> entries = xlang ? xlangSerializers : nativeSerializers;
    Entry entry = entries.get(targetType);
    if (entry != null) {
      return entry;
    }
    ConcurrentHashMap<Class<?>, Boolean> misses =
        xlang ? missingXlangSerializers : missingNativeSerializers;
    if (misses.containsKey(targetType)) {
      return null;
    }
    entry = loadEntry(targetType, xlang ? Mode.XLANG : Mode.NATIVE);
    if (entry == null) {
      misses.put(targetType, Boolean.TRUE);
      return null;
    }
    Entry existing = entries.putIfAbsent(targetType, entry);
    return existing == null ? entry : existing;
  }

  private Entry loadEntry(Class<?> targetType, Mode mode) {
    String serializerName = generatedSerializerBinaryName(targetType, mode);
    Class<?> serializerClass;
    try {
      serializerClass = Class.forName(serializerName, false, targetType.getClassLoader());
    } catch (ClassNotFoundException e) {
      return null;
    }
    if (!StaticGeneratedStructSerializer.class.isAssignableFrom(serializerClass)) {
      throw new ForyException(
          "Generated serializer "
              + serializerName
              + " for "
              + targetType.getName()
              + " does not extend "
              + StaticGeneratedStructSerializer.class.getName());
    }
    return newEntry(serializerClass.asSubclass(StaticGeneratedStructSerializer.class));
  }

  private Entry newEntry(Class<? extends StaticGeneratedStructSerializer> serializerClass) {
    MethodHandle descriptorConstructor = constructor(serializerClass);
    MethodHandle runtimeConstructor = constructor(serializerClass, TypeResolver.class, Class.class);
    MethodHandle compatibleConstructor =
        constructor(serializerClass, TypeResolver.class, Class.class, TypeDef.class);
    return new Entry(
        serializerClass, descriptorConstructor, runtimeConstructor, compatibleConstructor);
  }

  private MethodHandle constructor(
      Class<? extends StaticGeneratedStructSerializer> serializerClass,
      Class<?>... parameterTypes) {
    try {
      // Static generated serializers are emitted into application packages, which may be in named
      // modules not opened to Fory. Trusted constructor handles keep JPMS access in _JDKAccess.
      return ReflectionUtils.getCtrHandle(serializerClass, parameterTypes);
    } catch (RuntimeException e) {
      throw new ForyException(
          "Generated serializer "
              + serializerClass.getName()
              + " is missing or cannot access required constructor "
              + constructorSignature(parameterTypes),
          e);
    }
  }

  private static String constructorSignature(Class<?>[] parameterTypes) {
    StringBuilder builder = new StringBuilder("(");
    for (int i = 0; i < parameterTypes.length; i++) {
      if (i != 0) {
        builder.append(", ");
      }
      builder.append(parameterTypes[i].getSimpleName());
    }
    return builder.append(')').toString();
  }

  static String generatedSerializerBinaryName(Class<?> targetType, Mode mode) {
    return generatedSerializerBinaryName(targetType.getName(), mode);
  }

  static String generatedSerializerBinaryName(String targetBinaryName, Mode mode) {
    int packageEnd = targetBinaryName.lastIndexOf('.');
    if (packageEnd < 0) {
      return generatedSerializerSimpleName(targetBinaryName, mode);
    }
    return targetBinaryName.substring(0, packageEnd)
        + "."
        + generatedSerializerSimpleName(targetBinaryName.substring(packageEnd + 1), mode);
  }

  static String generatedSerializerSimpleName(String targetBinarySimpleName, Mode mode) {
    return escapeBinarySimpleName(targetBinarySimpleName) + suffix(mode);
  }

  private static String suffix(Mode mode) {
    return mode == Mode.XLANG ? XLANG_SUFFIX : NATIVE_SUFFIX;
  }

  private static String escapeBinarySimpleName(String binarySimpleName) {
    StringBuilder builder = new StringBuilder(binarySimpleName.length() + 32);
    for (int i = 0; i < binarySimpleName.length(); ) {
      int codePoint = binarySimpleName.codePointAt(i);
      if (codePoint == '$') {
        builder.append('_');
      } else if (codePoint == '_') {
        builder.append("_u_");
      } else if (Character.isJavaIdentifierPart(codePoint)) {
        builder.appendCodePoint(codePoint);
      } else {
        builder.append("_x").append(Integer.toHexString(codePoint)).append('_');
      }
      i += Character.charCount(codePoint);
    }
    return builder.toString();
  }
}
