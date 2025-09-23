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
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.Platform;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.function.Functions;
import org.apache.fory.util.function.ToByteFunction;
import org.apache.fory.util.function.ToCharFunction;
import org.apache.fory.util.function.ToFloatFunction;
import org.apache.fory.util.function.ToShortFunction;
import org.apache.fory.util.record.RecordUtils;
import org.apache.fory.util.unsafe._JDKAccess;

/**
 * Field accessor for primitive types and object types.
 *
 * <p>Note for primitive types, there will be box/unbox overhead. Use {@link UnsafeFieldAccessor} if
 * possible to avoid this overhead.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class FieldAccessor {
  protected final Field field;
  protected final long fieldOffset;

  public FieldAccessor(Field field) {
    this.field = field;
    Preconditions.checkNotNull(field);
    long fieldOffset;
    try {
      fieldOffset = ReflectionUtils.getFieldOffset(field);
    } catch (UnsupportedOperationException e) {
      fieldOffset = -1;
    }
    this.fieldOffset = fieldOffset;
  }

  protected FieldAccessor(Field field, long fieldOffset) {
    this.field = field;
    this.fieldOffset = fieldOffset;
  }

  public abstract Object get(Object obj);

  public void set(Object obj, Object value) {
    throw new UnsupportedOperationException("Unsupported for field " + field);
  }

  public Field getField() {
    return field;
  }

  public final void putObject(Object targetObject, Object object) {
    if (fieldOffset != -1) {
      Platform.putObject(targetObject, fieldOffset, object);
    } else {
      set(targetObject, object);
    }
  }

  public final Object getObject(Object targetObject) {
    if (fieldOffset != -1) {
      return Platform.getObject(targetObject, fieldOffset);
    } else {
      return get(targetObject);
    }
  }

  public long getFieldOffset() {
    return fieldOffset;
  }

  void checkObj(Object obj) {
    if (!this.field.getDeclaringClass().isAssignableFrom(obj.getClass())) {
      throw new IllegalArgumentException("Illegal class " + obj.getClass());
    }
  }

  @Override
  public String toString() {
    return field.toString();
  }

  public abstract static class FieldGetter extends FieldAccessor {

    private final Object getter;

    protected FieldGetter(Field field, Object getter) {
      super(field, -1);
      this.getter = getter;
    }

    public Object getGetter() {
      return getter;
    }
  }

  public static FieldAccessor createAccessor(Field field) {
    if (RecordUtils.isRecord(field.getDeclaringClass())) {
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
    if (GraalvmSupport.isGraalBuildtime()) {
      return new GeneratedAccessor(field);
    }
    if (field.getType() == boolean.class) {
      return new BooleanAccessor(field);
    } else if (field.getType() == byte.class) {
      return new ByteAccessor(field);
    } else if (field.getType() == char.class) {
      return new CharAccessor(field);
    } else if (field.getType() == short.class) {
      return new ShortAccessor(field);
    } else if (field.getType() == int.class) {
      return new IntAccessor(field);
    } else if (field.getType() == long.class) {
      return new LongAccessor(field);
    } else if (field.getType() == float.class) {
      return new FloatAccessor(field);
    } else if (field.getType() == double.class) {
      return new DoubleAccessor(field);
    } else {
      return new ObjectAccessor(field);
    }
  }

  /** Primitive boolean accessor. */
  public static class BooleanAccessor extends FieldAccessor {
    public BooleanAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == boolean.class);
    }

    @Override
    public Object get(Object obj) {
      checkObj(obj);
      return Platform.getBoolean(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putBoolean(obj, fieldOffset, (Boolean) value);
    }
  }

  public static class BooleanGetter extends FieldGetter {
    private final Predicate getter;

    public BooleanGetter(Field field, Predicate getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == boolean.class);
    }

    @Override
    public Boolean get(Object obj) {
      checkObj(obj);
      return getter.test(obj);
    }
  }

  /** Primitive byte accessor. */
  public static class ByteAccessor extends FieldAccessor {
    public ByteAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == byte.class);
    }

    @Override
    public Byte get(Object obj) {
      checkObj(obj);
      return Platform.getByte(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putByte(obj, fieldOffset, (Byte) value);
    }
  }

  public static class ByteGetter extends FieldGetter {

    private final ToByteFunction getter;

    public ByteGetter(Field field, ToByteFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == byte.class);
    }

    @Override
    public Byte get(Object obj) {
      return getter.applyAsByte(obj);
    }
  }

  /** Primitive char accessor. */
  public static class CharAccessor extends FieldAccessor {
    public CharAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == char.class);
    }

    @Override
    public Character get(Object obj) {
      checkObj(obj);
      return Platform.getChar(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putChar(obj, fieldOffset, (Character) value);
    }
  }

  public static class CharGetter extends FieldGetter {
    private final ToCharFunction getter;

    public CharGetter(Field field, ToCharFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == char.class);
    }

    @Override
    public Character get(Object obj) {
      return getter.applyAsChar(obj);
    }
  }

  /** Primitive short accessor. */
  public static class ShortAccessor extends FieldAccessor {
    public ShortAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == short.class);
    }

    @Override
    public Short get(Object obj) {
      checkObj(obj);
      return Platform.getShort(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putShort(obj, fieldOffset, (Short) value);
    }
  }

  public static class ShortGetter extends FieldGetter {
    private final ToShortFunction getter;

    public ShortGetter(Field field, ToShortFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == short.class);
    }

    @Override
    public Short get(Object obj) {
      return getter.applyAsShort(obj);
    }
  }

  /** Primitive int accessor. */
  public static class IntAccessor extends FieldAccessor {
    public IntAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == int.class);
    }

    @Override
    public Integer get(Object obj) {
      checkObj(obj);
      return Platform.getInt(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putInt(obj, fieldOffset, (Integer) value);
    }
  }

  public static class IntGetter extends FieldGetter {
    private final ToIntFunction getter;

    public IntGetter(Field field, ToIntFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == int.class);
    }

    @Override
    public Integer get(Object obj) {
      return getter.applyAsInt(obj);
    }
  }

  /** Primitive long accessor. */
  public static class LongAccessor extends FieldAccessor {
    public LongAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == long.class);
    }

    @Override
    public Long get(Object obj) {
      checkObj(obj);
      return Platform.getLong(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putLong(obj, fieldOffset, (Long) value);
    }
  }

  public static class LongGetter extends FieldGetter {
    private final ToLongFunction getter;

    public LongGetter(Field field, ToLongFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == long.class);
    }

    @Override
    public Long get(Object obj) {
      return getter.applyAsLong(obj);
    }
  }

  /** Primitive float accessor. */
  public static class FloatAccessor extends FieldAccessor {
    public FloatAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == float.class);
    }

    @Override
    public Object get(Object obj) {
      checkObj(obj);
      return Platform.getFloat(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putFloat(obj, fieldOffset, (Float) value);
    }
  }

  public static class FloatGetter extends FieldGetter {
    private final ToFloatFunction getter;

    public FloatGetter(Field field, ToFloatFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == float.class);
    }

    @Override
    public Float get(Object obj) {
      return getter.applyAsFloat(obj);
    }
  }

  /** Primitive double accessor. */
  public static class DoubleAccessor extends FieldAccessor {
    public DoubleAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(field.getType() == double.class);
    }

    @Override
    public Object get(Object obj) {
      checkObj(obj);
      return Platform.getDouble(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putDouble(obj, fieldOffset, (Double) value);
    }
  }

  public static class DoubleGetter extends FieldGetter {
    private final ToDoubleFunction getter;

    public DoubleGetter(Field field, ToDoubleFunction getter) {
      super(field, getter);
      this.getter = getter;
      Preconditions.checkArgument(field.getType() == double.class);
    }

    @Override
    public Double get(Object obj) {
      return getter.applyAsDouble(obj);
    }
  }

  /** Object accessor. */
  public static class ObjectAccessor extends FieldAccessor {
    public ObjectAccessor(Field field) {
      super(field);
      Preconditions.checkArgument(!TypeUtils.isPrimitive(field.getType()));
    }

    @Override
    public Object get(Object obj) {
      checkObj(obj);
      return Platform.getObject(obj, fieldOffset);
    }

    @Override
    public void set(Object obj, Object value) {
      checkObj(obj);
      Platform.putObject(obj, fieldOffset, value);
    }
  }

  public static class ObjectGetter extends FieldGetter {
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

  static class GeneratedAccessor extends FieldAccessor {
    private static final ClassValueCache<ConcurrentMap<String, Tuple2<MethodHandle, MethodHandle>>>
        cache = ClassValueCache.newClassKeyCache(8);

    private final MethodHandle getter;
    private final MethodHandle setter;

    protected GeneratedAccessor(Field field) {
      super(field, -1);
      ConcurrentMap<String, Tuple2<MethodHandle, MethodHandle>> map;
      try {
        map = cache.get(field.getDeclaringClass(), ConcurrentHashMap::new);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
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
  }
}
