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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import org.apache.fory.exception.ForyException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.reflect.FieldAccessor.FieldGetter;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.Functions;
import org.apache.fory.util.function.ToByteFunction;
import org.apache.fory.util.function.ToCharFunction;
import org.apache.fory.util.function.ToFloatFunction;
import org.apache.fory.util.function.ToShortFunction;

final class RecordFieldAccessors {
  private RecordFieldAccessors() {}

  static FieldAccessor createAccessor(Field field) {
    if (AndroidSupport.IS_ANDROID) {
      return new ReflectiveRecordFieldAccessor(field);
    }
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return new ReflectiveRecordFieldAccessor(field);
    }
    Object getter;
    try {
      Method getterMethod = field.getDeclaringClass().getDeclaredMethod(field.getName());
      getter = Functions.makeGetterFunction(getterMethod);
    } catch (NoSuchMethodException ex) {
      throw new RuntimeException(ex);
    }
    if (getter instanceof Predicate) {
      return new BooleanGetter(field, (Predicate) getter);
    } else if (getter instanceof ToByteFunction) {
      return new ByteGetter(field, (ToByteFunction) getter);
    } else if (getter instanceof ToCharFunction) {
      return new CharGetter(field, (ToCharFunction) getter);
    } else if (getter instanceof ToShortFunction) {
      return new ShortGetter(field, (ToShortFunction) getter);
    } else if (getter instanceof ToIntFunction) {
      return new IntGetter(field, (ToIntFunction) getter);
    } else if (getter instanceof ToLongFunction) {
      return new LongGetter(field, (ToLongFunction) getter);
    } else if (getter instanceof ToFloatFunction) {
      return new FloatGetter(field, (ToFloatFunction) getter);
    } else if (getter instanceof ToDoubleFunction) {
      return new DoubleGetter(field, (ToDoubleFunction) getter);
    } else {
      return new ObjectGetter(field, (Function) getter);
    }
  }

  static final class ReflectiveRecordFieldAccessor extends FieldGetter {
    private final Method accessor;

    ReflectiveRecordFieldAccessor(Field field) {
      super(field, null);
      try {
        accessor = field.getDeclaringClass().getDeclaredMethod(field.getName());
        accessor.setAccessible(true);
      } catch (NoSuchMethodException | RuntimeException e) {
        throw new ForyException("Failed to create record field accessor for " + field, e);
      }
    }

    @Override
    public Object get(Object obj) {
      checkObj(obj);
      try {
        return accessor.invoke(obj);
      } catch (IllegalAccessException | IllegalArgumentException e) {
        throw new ForyException("Failed to read record field reflectively: " + field, e);
      } catch (InvocationTargetException e) {
        throw new ForyException(
            "Record accessor threw while reading field: " + field, e.getCause());
      }
    }

    @Override
    public void set(Object obj, Object value) {
      throw new UnsupportedOperationException("Record field is read-only: " + field);
    }
  }

  private static class BooleanGetter extends FieldGetter {
    private final Predicate getter;

    public BooleanGetter(Field field, Predicate getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == boolean.class);
    }

    @Override
    public Boolean get(Object obj) {
      return getBoolean(obj);
    }

    @Override
    public boolean getBoolean(Object obj) {
      checkObj(obj);
      return getter.test(obj);
    }
  }

  private static class ByteGetter extends FieldGetter {
    private final ToByteFunction getter;

    public ByteGetter(Field field, ToByteFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == byte.class);
    }

    @Override
    public Byte get(Object obj) {
      return getByte(obj);
    }

    @Override
    public byte getByte(Object obj) {
      return getter.applyAsByte(obj);
    }
  }

  private static class CharGetter extends FieldGetter {
    private final ToCharFunction getter;

    public CharGetter(Field field, ToCharFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == char.class);
    }

    @Override
    public Character get(Object obj) {
      return getChar(obj);
    }

    @Override
    public char getChar(Object obj) {
      return getter.applyAsChar(obj);
    }
  }

  private static class ShortGetter extends FieldGetter {
    private final ToShortFunction getter;

    public ShortGetter(Field field, ToShortFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == short.class);
    }

    @Override
    public Short get(Object obj) {
      return getShort(obj);
    }

    @Override
    public short getShort(Object obj) {
      return getter.applyAsShort(obj);
    }
  }

  private static class IntGetter extends FieldGetter {
    private final ToIntFunction getter;

    public IntGetter(Field field, ToIntFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == int.class);
    }

    @Override
    public Integer get(Object obj) {
      return getInt(obj);
    }

    @Override
    public int getInt(Object obj) {
      return getter.applyAsInt(obj);
    }
  }

  private static class LongGetter extends FieldGetter {
    private final ToLongFunction getter;

    public LongGetter(Field field, ToLongFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == long.class);
    }

    @Override
    public Long get(Object obj) {
      return getLong(obj);
    }

    @Override
    public long getLong(Object obj) {
      return getter.applyAsLong(obj);
    }
  }

  private static class FloatGetter extends FieldGetter {
    private final ToFloatFunction getter;

    public FloatGetter(Field field, ToFloatFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == float.class);
    }

    @Override
    public Float get(Object obj) {
      return getFloat(obj);
    }

    @Override
    public float getFloat(Object obj) {
      return getter.applyAsFloat(obj);
    }
  }

  private static class DoubleGetter extends FieldGetter {
    private final ToDoubleFunction getter;

    public DoubleGetter(Field field, ToDoubleFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == double.class);
    }

    @Override
    public Double get(Object obj) {
      return getDouble(obj);
    }

    @Override
    public double getDouble(Object obj) {
      return getter.applyAsDouble(obj);
    }
  }

  private static class ObjectGetter extends FieldGetter {
    private final Function getter;

    public ObjectGetter(Field field, Function getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(!field.getType().isPrimitive(), field);
    }

    @Override
    public Object get(Object obj) {
      return getter.apply(obj);
    }
  }
}
