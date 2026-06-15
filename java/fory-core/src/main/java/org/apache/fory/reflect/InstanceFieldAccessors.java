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

package org.apache.fory.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.exception.ForyException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.platform.internal._UnsafeUtils;
import org.apache.fory.util.Preconditions;
import sun.misc.Unsafe;

/**
 * Non-record instance field accessor owner.
 *
 * <p>This class is public only so generated serializers can name {@link InstanceAccessor} as a
 * concrete field type on JDK25+. Callers must still create accessors through {@link
 * FieldAccessor#createAccessor(Field)} so platform dispatch stays centralized.
 */
@Internal
public final class InstanceFieldAccessors {
  private static final int BOOLEAN_ACCESS = 1;
  private static final int BYTE_ACCESS = 2;
  private static final int CHAR_ACCESS = 3;
  private static final int SHORT_ACCESS = 4;
  private static final int INT_ACCESS = 5;
  private static final int LONG_ACCESS = 6;
  private static final int FLOAT_ACCESS = 7;
  private static final int DOUBLE_ACCESS = 8;
  private static final int OBJECT_ACCESS = 9;

  private InstanceFieldAccessors() {}

  static FieldAccessor createAccessor(Field field) {
    Preconditions.checkArgument(!Modifier.isStatic(field.getModifiers()), field);
    if (AndroidSupport.IS_ANDROID) {
      return new ReflectionAccessor(field);
    }
    if (GraalvmSupport.isGraalBuildTime()) {
      return new GeneratedAccessor(field);
    }
    return new InstanceAccessor(field);
  }

  private static int accessKind(Field field) {
    Class<?> fieldType = field.getType();
    if (fieldType == boolean.class) {
      return BOOLEAN_ACCESS;
    } else if (fieldType == byte.class) {
      return BYTE_ACCESS;
    } else if (fieldType == char.class) {
      return CHAR_ACCESS;
    } else if (fieldType == short.class) {
      return SHORT_ACCESS;
    } else if (fieldType == int.class) {
      return INT_ACCESS;
    } else if (fieldType == long.class) {
      return LONG_ACCESS;
    } else if (fieldType == float.class) {
      return FLOAT_ACCESS;
    } else if (fieldType == double.class) {
      return DOUBLE_ACCESS;
    }
    return OBJECT_ACCESS;
  }

  private static final class ReflectionAccessor extends FieldAccessor {
    private ReflectionAccessor(Field field) {
      super(field);
      try {
        field.setAccessible(true);
      } catch (RuntimeException e) {
        throw new ForyException("Failed to make field accessible: " + field, e);
      }
    }

    @Override
    public Object get(Object obj) {
      try {
        return field.get(obj);
      } catch (IllegalAccessException | IllegalArgumentException e) {
        throw new ForyException("Failed to read field reflectively: " + field, e);
      }
    }

    @Override
    public void set(Object obj, Object value) {
      try {
        field.set(obj, value);
      } catch (IllegalAccessException | IllegalArgumentException e) {
        throw new ForyException("Failed to write field reflectively: " + field, e);
      }
    }
  }

  /** Public only for generated serializers; use {@link FieldAccessor#createAccessor(Field)}. */
  public static final class InstanceAccessor extends FieldAccessor {
    private static final Unsafe UNSAFE = _UnsafeUtils.UNSAFE;

    private final long fieldOffset;
    private final int accessKind;

    InstanceAccessor(Field field) {
      super(field);
      fieldOffset = fieldOffset(field);
      accessKind = accessKind(field);
    }

    private static long fieldOffset(Field field) {
      return UNSAFE.objectFieldOffset(field);
    }

    @Override
    public Object get(Object obj) {
      checkObj(obj);
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          return UNSAFE.getBoolean(obj, fieldOffset);
        case BYTE_ACCESS:
          return UNSAFE.getByte(obj, fieldOffset);
        case CHAR_ACCESS:
          return UNSAFE.getChar(obj, fieldOffset);
        case SHORT_ACCESS:
          return UNSAFE.getShort(obj, fieldOffset);
        case INT_ACCESS:
          return UNSAFE.getInt(obj, fieldOffset);
        case LONG_ACCESS:
          return UNSAFE.getLong(obj, fieldOffset);
        case FLOAT_ACCESS:
          return UNSAFE.getFloat(obj, fieldOffset);
        case DOUBLE_ACCESS:
          return UNSAFE.getDouble(obj, fieldOffset);
        case OBJECT_ACCESS:
          return UNSAFE.getObject(obj, fieldOffset);
        default:
          throw new IllegalStateException("Unsupported access kind " + accessKind);
      }
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          UNSAFE.putBoolean(obj, fieldOffset, (Boolean) value);
          return;
        case BYTE_ACCESS:
          UNSAFE.putByte(obj, fieldOffset, (Byte) value);
          return;
        case CHAR_ACCESS:
          UNSAFE.putChar(obj, fieldOffset, (Character) value);
          return;
        case SHORT_ACCESS:
          UNSAFE.putShort(obj, fieldOffset, (Short) value);
          return;
        case INT_ACCESS:
          UNSAFE.putInt(obj, fieldOffset, (Integer) value);
          return;
        case LONG_ACCESS:
          UNSAFE.putLong(obj, fieldOffset, (Long) value);
          return;
        case FLOAT_ACCESS:
          UNSAFE.putFloat(obj, fieldOffset, (Float) value);
          return;
        case DOUBLE_ACCESS:
          UNSAFE.putDouble(obj, fieldOffset, (Double) value);
          return;
        case OBJECT_ACCESS:
          UNSAFE.putObject(obj, fieldOffset, value);
          return;
        default:
          throw new IllegalStateException("Unsupported access kind " + accessKind);
      }
    }

    @Override
    public void copy(Object sourceObject, Object targetObject) {
      checkObj(sourceObject);
      checkObj(targetObject);
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          UNSAFE.putBoolean(
              targetObject, fieldOffset, UNSAFE.getBoolean(sourceObject, fieldOffset));
          return;
        case BYTE_ACCESS:
          UNSAFE.putByte(targetObject, fieldOffset, UNSAFE.getByte(sourceObject, fieldOffset));
          return;
        case CHAR_ACCESS:
          UNSAFE.putChar(targetObject, fieldOffset, UNSAFE.getChar(sourceObject, fieldOffset));
          return;
        case SHORT_ACCESS:
          UNSAFE.putShort(targetObject, fieldOffset, UNSAFE.getShort(sourceObject, fieldOffset));
          return;
        case INT_ACCESS:
          UNSAFE.putInt(targetObject, fieldOffset, UNSAFE.getInt(sourceObject, fieldOffset));
          return;
        case LONG_ACCESS:
          UNSAFE.putLong(targetObject, fieldOffset, UNSAFE.getLong(sourceObject, fieldOffset));
          return;
        case FLOAT_ACCESS:
          UNSAFE.putFloat(targetObject, fieldOffset, UNSAFE.getFloat(sourceObject, fieldOffset));
          return;
        case DOUBLE_ACCESS:
          UNSAFE.putDouble(targetObject, fieldOffset, UNSAFE.getDouble(sourceObject, fieldOffset));
          return;
        case OBJECT_ACCESS:
          UNSAFE.putObject(targetObject, fieldOffset, UNSAFE.getObject(sourceObject, fieldOffset));
          return;
        default:
          super.copy(sourceObject, targetObject);
      }
    }

    @Override
    public void copyObject(Object sourceObject, Object targetObject) {
      checkObj(sourceObject);
      checkObj(targetObject);
      if (accessKind == OBJECT_ACCESS) {
        UNSAFE.putObject(targetObject, fieldOffset, UNSAFE.getObject(sourceObject, fieldOffset));
      } else {
        super.copyObject(sourceObject, targetObject);
      }
    }

    @Override
    public boolean getBoolean(Object obj) {
      checkObj(obj);
      return UNSAFE.getBoolean(obj, fieldOffset);
    }

    @Override
    public void putBoolean(Object obj, boolean value) {
      checkObj(obj);
      UNSAFE.putBoolean(obj, fieldOffset, value);
    }

    @Override
    public byte getByte(Object obj) {
      checkObj(obj);
      return UNSAFE.getByte(obj, fieldOffset);
    }

    @Override
    public void putByte(Object obj, byte value) {
      checkObj(obj);
      UNSAFE.putByte(obj, fieldOffset, value);
    }

    @Override
    public char getChar(Object obj) {
      checkObj(obj);
      return UNSAFE.getChar(obj, fieldOffset);
    }

    @Override
    public void putChar(Object obj, char value) {
      checkObj(obj);
      UNSAFE.putChar(obj, fieldOffset, value);
    }

    @Override
    public short getShort(Object obj) {
      checkObj(obj);
      return UNSAFE.getShort(obj, fieldOffset);
    }

    @Override
    public void putShort(Object obj, short value) {
      checkObj(obj);
      UNSAFE.putShort(obj, fieldOffset, value);
    }

    @Override
    public int getInt(Object obj) {
      checkObj(obj);
      return UNSAFE.getInt(obj, fieldOffset);
    }

    @Override
    public void putInt(Object obj, int value) {
      checkObj(obj);
      UNSAFE.putInt(obj, fieldOffset, value);
    }

    @Override
    public long getLong(Object obj) {
      checkObj(obj);
      return UNSAFE.getLong(obj, fieldOffset);
    }

    @Override
    public void putLong(Object obj, long value) {
      checkObj(obj);
      UNSAFE.putLong(obj, fieldOffset, value);
    }

    @Override
    public float getFloat(Object obj) {
      checkObj(obj);
      return UNSAFE.getFloat(obj, fieldOffset);
    }

    @Override
    public void putFloat(Object obj, float value) {
      checkObj(obj);
      UNSAFE.putFloat(obj, fieldOffset, value);
    }

    @Override
    public double getDouble(Object obj) {
      checkObj(obj);
      return UNSAFE.getDouble(obj, fieldOffset);
    }

    @Override
    public void putDouble(Object obj, double value) {
      checkObj(obj);
      UNSAFE.putDouble(obj, fieldOffset, value);
    }
  }

  static final class GeneratedAccessor extends FieldAccessor {
    private static final ClassValueCache<ConcurrentMap<String, Tuple2<MethodHandle, MethodHandle>>>
        cache = ClassValueCache.newClassKeyCache(8);

    private final MethodHandle getter;
    private final MethodHandle setter;

    GeneratedAccessor(Field field) {
      super(field);
      ConcurrentMap<String, Tuple2<MethodHandle, MethodHandle>> map =
          cache.get(field.getDeclaringClass(), ConcurrentHashMap::new);
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(field.getDeclaringClass());
      Tuple2<MethodHandle, MethodHandle> tuple2 =
          map.computeIfAbsent(
              field.getName(),
              k -> {
                try {
                  MethodHandle getter =
                      lookup.findGetter(
                          field.getDeclaringClass(), field.getName(), field.getType());
                  MethodHandle setter =
                      lookup.findSetter(
                          field.getDeclaringClass(), field.getName(), field.getType());
                  return Tuple2.of(getter, setter);
                } catch (IllegalAccessException | NoSuchFieldException ex) {
                  throw new RuntimeException(ex);
                }
              });
      getter = tuple2.f0;
      setter = tuple2.f1;
    }

    @Override
    public Object get(Object obj) {
      try {
        return getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void set(Object obj, Object value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public boolean getBoolean(Object obj) {
      try {
        return (boolean) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putBoolean(Object obj, boolean value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public byte getByte(Object obj) {
      try {
        return (byte) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putByte(Object obj, byte value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public char getChar(Object obj) {
      try {
        return (char) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putChar(Object obj, char value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public short getShort(Object obj) {
      try {
        return (short) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putShort(Object obj, short value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public int getInt(Object obj) {
      try {
        return (int) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putInt(Object obj, int value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public long getLong(Object obj) {
      try {
        return (long) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putLong(Object obj, long value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public float getFloat(Object obj) {
      try {
        return (float) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putFloat(Object obj, float value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public double getDouble(Object obj) {
      try {
        return (double) getter.invoke(obj);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void putDouble(Object obj, double value) {
      try {
        setter.invoke(obj, value);
      } catch (Throwable e) {
        throw new RuntimeException(e);
      }
    }
  }
}
