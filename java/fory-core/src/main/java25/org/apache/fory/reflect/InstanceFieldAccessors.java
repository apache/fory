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

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.apache.fory.annotation.Internal;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.util.Preconditions;

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

  private static VarHandle fieldHandle(Field field) {
    try {
      return _JDKAccess
          ._trustedLookup(field.getDeclaringClass())
          .findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
    } catch (IllegalAccessException e) {
      throw accessFailure(field, e);
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException("Failed to create VarHandle for field " + field, e);
    }
  }

  private static IllegalStateException accessFailure(Field field, Throwable cause) {
    return new IllegalStateException(
        "Cannot access field " + field + ". " + _JDKAccess.jdk25AccessMessage(),
        cause);
  }

  /** Public only for generated serializers; use {@link FieldAccessor#createAccessor(Field)}. */
  public static final class InstanceAccessor extends FieldAccessor {
    private final VarHandle handle;
    private final int accessKind;

    InstanceAccessor(Field field) {
      super(field);
      handle = fieldHandle(field);
      accessKind = accessKind(field);
    }

    @Override
    public Object get(Object obj) {
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          return (boolean) handle.get(obj);
        case BYTE_ACCESS:
          return (byte) handle.get(obj);
        case CHAR_ACCESS:
          return (char) handle.get(obj);
        case SHORT_ACCESS:
          return (short) handle.get(obj);
        case INT_ACCESS:
          return (int) handle.get(obj);
        case LONG_ACCESS:
          return (long) handle.get(obj);
        case FLOAT_ACCESS:
          return (float) handle.get(obj);
        case DOUBLE_ACCESS:
          return (double) handle.get(obj);
        case OBJECT_ACCESS:
          return handle.get(obj);
        default:
          throw new IllegalStateException("Unsupported access kind " + accessKind);
      }
    }

    @Override
    public void set(Object obj, Object value) {
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          handle.set(obj, (Boolean) value);
          return;
        case BYTE_ACCESS:
          handle.set(obj, (Byte) value);
          return;
        case CHAR_ACCESS:
          handle.set(obj, (Character) value);
          return;
        case SHORT_ACCESS:
          handle.set(obj, (Short) value);
          return;
        case INT_ACCESS:
          handle.set(obj, (Integer) value);
          return;
        case LONG_ACCESS:
          handle.set(obj, (Long) value);
          return;
        case FLOAT_ACCESS:
          handle.set(obj, (Float) value);
          return;
        case DOUBLE_ACCESS:
          handle.set(obj, (Double) value);
          return;
        case OBJECT_ACCESS:
          handle.set(obj, value);
          return;
        default:
          throw new IllegalStateException("Unsupported access kind " + accessKind);
      }
    }

    @Override
    public void copy(Object sourceObject, Object targetObject) {
      switch (accessKind) {
        case BOOLEAN_ACCESS:
          handle.set(targetObject, (boolean) handle.get(sourceObject));
          return;
        case BYTE_ACCESS:
          handle.set(targetObject, (byte) handle.get(sourceObject));
          return;
        case CHAR_ACCESS:
          handle.set(targetObject, (char) handle.get(sourceObject));
          return;
        case SHORT_ACCESS:
          handle.set(targetObject, (short) handle.get(sourceObject));
          return;
        case INT_ACCESS:
          handle.set(targetObject, (int) handle.get(sourceObject));
          return;
        case LONG_ACCESS:
          handle.set(targetObject, (long) handle.get(sourceObject));
          return;
        case FLOAT_ACCESS:
          handle.set(targetObject, (float) handle.get(sourceObject));
          return;
        case DOUBLE_ACCESS:
          handle.set(targetObject, (double) handle.get(sourceObject));
          return;
        case OBJECT_ACCESS:
          handle.set(targetObject, handle.get(sourceObject));
          return;
        default:
          throw new IllegalStateException("Unsupported access kind " + accessKind);
      }
    }

    @Override
    public void copyObject(Object sourceObject, Object targetObject) {
      if (accessKind == OBJECT_ACCESS) {
        handle.set(targetObject, handle.get(sourceObject));
      } else {
        super.copyObject(sourceObject, targetObject);
      }
    }

    @Override
    public boolean getBoolean(Object obj) {
      return (boolean) handle.get(obj);
    }

    @Override
    public void putBoolean(Object obj, boolean value) {
      handle.set(obj, value);
    }

    @Override
    public byte getByte(Object obj) {
      return (byte) handle.get(obj);
    }

    @Override
    public void putByte(Object obj, byte value) {
      handle.set(obj, value);
    }

    @Override
    public char getChar(Object obj) {
      return (char) handle.get(obj);
    }

    @Override
    public void putChar(Object obj, char value) {
      handle.set(obj, value);
    }

    @Override
    public short getShort(Object obj) {
      return (short) handle.get(obj);
    }

    @Override
    public void putShort(Object obj, short value) {
      handle.set(obj, value);
    }

    @Override
    public int getInt(Object obj) {
      return (int) handle.get(obj);
    }

    @Override
    public void putInt(Object obj, int value) {
      handle.set(obj, value);
    }

    @Override
    public long getLong(Object obj) {
      return (long) handle.get(obj);
    }

    @Override
    public void putLong(Object obj, long value) {
      handle.set(obj, value);
    }

    @Override
    public float getFloat(Object obj) {
      return (float) handle.get(obj);
    }

    @Override
    public void putFloat(Object obj, float value) {
      handle.set(obj, value);
    }

    @Override
    public double getDouble(Object obj) {
      return (double) handle.get(obj);
    }

    @Override
    public void putDouble(Object obj, double value) {
      handle.set(obj, value);
    }
  }
}
