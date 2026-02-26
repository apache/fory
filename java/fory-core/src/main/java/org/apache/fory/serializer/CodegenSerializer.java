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

package org.apache.fory.serializer;

import static org.apache.fory.util.Preconditions.checkArgument;

import java.lang.reflect.Modifier;
import org.apache.fory.Fory;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.Generated;
import org.apache.fory.memory.MemoryBuffer;

/** Util for JIT Serialization. */
@SuppressWarnings("UnstableApiUsage")
public final class CodegenSerializer {

  public static boolean supportCodegenForJavaSerialization(Class<?> cls) {
    // bean class can be static nested class, but can't be a non-static inner class.
    // Check modifiers first to avoid loading the enclosing class unnecessarily —
    // in classloader-isolated environments (e.g. OSGi, module systems) the enclosing
    // class may not be visible, causing NoClassDefFoundError.
    if (Modifier.isStatic(cls.getModifiers())) {
      return true;
    }
    try {
      return cls.getEnclosingClass() == null;
    } catch (Throwable t) {
      // Enclosing class is not loadable — the class cannot be a valid non-static
      // inner class in this context, so codegen is not applicable.
      return false;
    }
  }

  @SuppressWarnings("unchecked")
  public static <T> Class<Serializer<T>> loadCodegenSerializer(Fory fory, Class<T> cls) {
    try {
      return (Class<Serializer<T>>) CodecUtils.loadOrGenObjectCodecClass(cls, fory);
    } catch (Exception e) {
      String msg = String.format("Create sequential serializer failed, \nclass: %s", cls);
      throw new RuntimeException(msg, e);
    }
  }

  /**
   * A bean serializer which initializes lazily on first call read/write method.
   *
   * <p>This class is used by {@link org.apache.fory.builder.BaseObjectCodecBuilder} to avoid
   * potential recursive bean serializer creation when there is a circular reference in class
   * children fields.
   */
  public static final class LazyInitBeanSerializer<T> extends AbstractObjectSerializer<T> {
    private Serializer<T> serializer;
    private Serializer<T> interpreterSerializer;

    public LazyInitBeanSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      getOrCreateGeneratedSerializer().write(buffer, value);
    }

    @Override
    public T read(MemoryBuffer buffer) {
      return getOrCreateGeneratedSerializer().read(buffer);
    }

    @SuppressWarnings({"rawtypes"})
    private Serializer<T> getOrCreateGeneratedSerializer() {
      if (serializer == null) {
        Serializer<T> jitSerializer = fory.getClassResolver().getSerializer(type);
        // Just be defensive for `getSerializer`/other call in Codec Builder to make
        // LazyInitBeanSerializer as serializer for `type`.
        if (jitSerializer instanceof LazyInitBeanSerializer) {
          // jit not finished, avoid recursive call this serializer.
          if (interpreterSerializer != null) {
            return interpreterSerializer;
          }
          fory.getClassResolver().getTypeInfo(type).setSerializer(null);
          if (fory.getConfig().isAsyncCompilationEnabled()) {
            // jit not finished, avoid recursive call current serializer.
            Class<? extends Serializer> sc =
                fory.getClassResolver().getSerializerClass(type, false);
            fory.getClassResolver().getTypeInfo(type).setSerializer(this);
            return interpreterSerializer = Serializers.newSerializer(fory, type, sc);
          } else {
            Class<? extends Serializer> sc = fory.getClassResolver().getSerializerClass(type);
            fory.getClassResolver().getTypeInfo(type).setSerializer(this);
            checkArgument(
                Generated.GeneratedSerializer.class.isAssignableFrom(sc),
                "Expect jit serializer but got %s for class %s",
                sc,
                type);
            serializer = Serializers.newSerializer(fory, type, sc);
            fory.getClassResolver().setSerializer(type, serializer);
            return serializer;
          }
        } else {
          serializer = jitSerializer;
        }
      }
      return serializer;
    }
  }
}
