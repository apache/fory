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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import org.apache.fory.json.ForyJsonException;

/** Android-only exact-shape method accessors, isolated from the ordinary JVM class hierarchy. */
final class AndroidJsonFieldAccessors {
  private static final int BOOLEAN_ACCESS = 1;
  private static final int BYTE_ACCESS = 2;
  private static final int CHAR_ACCESS = 3;
  private static final int SHORT_ACCESS = 4;
  private static final int INT_ACCESS = 5;
  private static final int LONG_ACCESS = 6;
  private static final int FLOAT_ACCESS = 7;
  private static final int DOUBLE_ACCESS = 8;
  private static final int OBJECT_ACCESS = 9;

  static JsonFieldAccessor getter(Method getter) {
    return new Getter(getter);
  }

  static JsonFieldAccessor setter(Method setter) {
    return new Setter(setter);
  }

  private static final class Getter extends JsonFieldAccessor {
    private final Method getter;
    private final MethodHandle handle;
    private final int accessKind;

    private Getter(Method getter) {
      this.getter = getter;
      Class<?> returnType = getter.getReturnType();
      Class<?> exactType = returnType.isPrimitive() ? returnType : Object.class;
      handle = handle(getter).asType(MethodType.methodType(exactType, Object.class));
      accessKind = accessKind(returnType);
    }

    @Override
    public Method getter() {
      return getter;
    }

    @Override
    public Object getObject(Object target) {
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          return getBoolean(target);
        case BYTE_ACCESS:
          return getByte(target);
        case CHAR_ACCESS:
          return getChar(target);
        case SHORT_ACCESS:
          return getShort(target);
        case INT_ACCESS:
          return getInt(target);
        case LONG_ACCESS:
          return getLong(target);
        case FLOAT_ACCESS:
          return getFloat(target);
        case DOUBLE_ACCESS:
          return getDouble(target);
        default:
          return getReference(target);
      }
    }

    private Object getReference(Object target) {
      try {
        return (Object) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public boolean getBoolean(Object target) {
      try {
        return (boolean) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public byte getByte(Object target) {
      try {
        return (byte) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public char getChar(Object target) {
      try {
        return (char) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public short getShort(Object target) {
      try {
        return (short) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public int getInt(Object target) {
      try {
        return (int) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public long getLong(Object target) {
      try {
        return (long) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public float getFloat(Object target) {
      try {
        return (float) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public double getDouble(Object target) {
      try {
        return (double) handle.invokeExact(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }
  }

  private static final class Setter extends JsonFieldAccessor {
    private final Method setter;
    private final MethodHandle handle;
    private final int accessKind;

    private Setter(Method setter) {
      this.setter = setter;
      Class<?> parameterType = setter.getParameterTypes()[0];
      Class<?> exactType = parameterType.isPrimitive() ? parameterType : Object.class;
      handle = handle(setter).asType(MethodType.methodType(void.class, Object.class, exactType));
      accessKind = accessKind(parameterType);
    }

    @Override
    public Method setter() {
      return setter;
    }

    @Override
    public void putObject(Object target, Object value) {
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          putBoolean(target, (Boolean) value);
          return;
        case BYTE_ACCESS:
          putByte(target, (Byte) value);
          return;
        case CHAR_ACCESS:
          putChar(target, (Character) value);
          return;
        case SHORT_ACCESS:
          putShort(target, (Short) value);
          return;
        case INT_ACCESS:
          putInt(target, (Integer) value);
          return;
        case LONG_ACCESS:
          putLong(target, (Long) value);
          return;
        case FLOAT_ACCESS:
          putFloat(target, (Float) value);
          return;
        case DOUBLE_ACCESS:
          putDouble(target, (Double) value);
          return;
        default:
          putReference(target, value);
      }
    }

    private void putReference(Object target, Object value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putBoolean(Object target, boolean value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putByte(Object target, byte value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putChar(Object target, char value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putShort(Object target, short value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putInt(Object target, int value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putLong(Object target, long value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putFloat(Object target, float value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public void putDouble(Object target, double value) {
      try {
        handle.invokeExact(target, value);
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }
  }

  private static MethodHandle handle(Method method) {
    try {
      method.setAccessible(true);
      return MethodHandles.lookup().unreflect(method);
    } catch (IllegalAccessException | RuntimeException e) {
      throw accessException(method, e);
    }
  }

  private static ForyJsonException accessException(Method method, Throwable e) {
    return new ForyJsonException("Cannot access JSON property method " + method, e);
  }

  private static int accessKind(Class<?> type) {
    if (type == boolean.class) {
      return BOOLEAN_ACCESS;
    } else if (type == byte.class) {
      return BYTE_ACCESS;
    } else if (type == char.class) {
      return CHAR_ACCESS;
    } else if (type == short.class) {
      return SHORT_ACCESS;
    } else if (type == int.class) {
      return INT_ACCESS;
    } else if (type == long.class) {
      return LONG_ACCESS;
    } else if (type == float.class) {
      return FLOAT_ACCESS;
    } else if (type == double.class) {
      return DOUBLE_ACCESS;
    }
    return OBJECT_ACCESS;
  }

  private AndroidJsonFieldAccessors() {}
}
