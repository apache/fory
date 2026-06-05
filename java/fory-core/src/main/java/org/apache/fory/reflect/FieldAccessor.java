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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.record.RecordUtils;

/** Field accessor for primitive types and object types. */
public abstract class FieldAccessor {
  private static final int BOOLEAN_ACCESS = 1;
  private static final int BYTE_ACCESS = 2;
  private static final int CHAR_ACCESS = 3;
  private static final int SHORT_ACCESS = 4;
  private static final int INT_ACCESS = 5;
  private static final int LONG_ACCESS = 6;
  private static final int FLOAT_ACCESS = 7;
  private static final int DOUBLE_ACCESS = 8;
  private static final int OBJECT_ACCESS = 9;

  protected final Field field;
  private final int accessKind;

  public FieldAccessor(Field field) {
    this.field = field;
    Preconditions.checkNotNull(field);
    accessKind = accessKind(field);
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

  public abstract Object get(Object obj);

  public void set(Object obj, Object value) {
    throw new UnsupportedOperationException("Unsupported for field " + field);
  }

  public void copy(Object sourceObject, Object targetObject) {
    switch (accessKind) {
      case BOOLEAN_ACCESS:
        putBoolean(targetObject, getBoolean(sourceObject));
        return;
      case BYTE_ACCESS:
        putByte(targetObject, getByte(sourceObject));
        return;
      case CHAR_ACCESS:
        putChar(targetObject, getChar(sourceObject));
        return;
      case SHORT_ACCESS:
        putShort(targetObject, getShort(sourceObject));
        return;
      case INT_ACCESS:
        putInt(targetObject, getInt(sourceObject));
        return;
      case LONG_ACCESS:
        putLong(targetObject, getLong(sourceObject));
        return;
      case FLOAT_ACCESS:
        putFloat(targetObject, getFloat(sourceObject));
        return;
      case DOUBLE_ACCESS:
        putDouble(targetObject, getDouble(sourceObject));
        return;
      default:
        putObject(targetObject, getObject(sourceObject));
    }
  }

  public void copyObject(Object sourceObject, Object targetObject) {
    putObject(targetObject, getObject(sourceObject));
  }

  public Field getField() {
    return field;
  }

  public boolean getBoolean(Object targetObject) {
    return (Boolean) get(targetObject);
  }

  public void putBoolean(Object targetObject, boolean value) {
    set(targetObject, value);
  }

  public byte getByte(Object targetObject) {
    return (Byte) get(targetObject);
  }

  public void putByte(Object targetObject, byte value) {
    set(targetObject, value);
  }

  public char getChar(Object targetObject) {
    return (Character) get(targetObject);
  }

  public void putChar(Object targetObject, char value) {
    set(targetObject, value);
  }

  public short getShort(Object targetObject) {
    return (Short) get(targetObject);
  }

  public void putShort(Object targetObject, short value) {
    set(targetObject, value);
  }

  public int getInt(Object targetObject) {
    return (Integer) get(targetObject);
  }

  public void putInt(Object targetObject, int value) {
    set(targetObject, value);
  }

  public long getLong(Object targetObject) {
    return (Long) get(targetObject);
  }

  public void putLong(Object targetObject, long value) {
    set(targetObject, value);
  }

  public float getFloat(Object targetObject) {
    return (Float) get(targetObject);
  }

  public void putFloat(Object targetObject, float value) {
    set(targetObject, value);
  }

  public double getDouble(Object targetObject) {
    return (Double) get(targetObject);
  }

  public void putDouble(Object targetObject, double value) {
    set(targetObject, value);
  }

  public void putObject(Object targetObject, Object object) {
    set(targetObject, object);
  }

  public Object getObject(Object targetObject) {
    return get(targetObject);
  }

  final void checkObj(Object obj) {
    // Unsafe offset access does not validate the receiver. A wrong receiver is a Fory
    // programming error, so keep this debug-only instead of adding production hot-path checks.
    assert field.getDeclaringClass().isInstance(obj) : illegalObject(obj);
  }

  private String illegalObject(Object obj) {
    return "Illegal class " + (obj == null ? null : obj.getClass());
  }

  @Override
  public String toString() {
    return field.toString();
  }

  public abstract static class FieldGetter extends FieldAccessor {
    private final Object getter;

    protected FieldGetter(Field field, Object getter) {
      super(field);
      this.getter = getter;
    }

    public Object getGetter() {
      return getter;
    }
  }

  public static FieldAccessor createAccessor(Field field) {
    Preconditions.checkArgument(!Modifier.isStatic(field.getModifiers()), field);
    if (RecordUtils.isRecord(field.getDeclaringClass())) {
      return RecordFieldAccessors.createAccessor(field);
    }
    return InstanceFieldAccessors.createAccessor(field);
  }
}
