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
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.function.ObjIntConsumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.util.function.ObjBooleanConsumer;
import org.apache.fory.util.function.ObjByteConsumer;
import org.apache.fory.util.function.ObjCharConsumer;
import org.apache.fory.util.function.ObjFloatConsumer;
import org.apache.fory.util.function.ObjShortConsumer;
import org.apache.fory.util.function.ToByteFunction;
import org.apache.fory.util.function.ToCharFunction;
import org.apache.fory.util.function.ToFloatFunction;
import org.apache.fory.util.function.ToShortFunction;

/**
 * Uniform interpreted object-member access for fields, getters, and setters.
 *
 * <p>Field members use typed access so primitive paths do not box. Ordinary JVM fields delegate to
 * Fory core's {@link FieldAccessor}; JDK 25 Native Image fields use typed reflection when module
 * access permits it. Method members cache a trusted {@code MethodHandle} on the JVM, use reflection
 * on Android, and use lambdas prepared by the Fory JSON Native Image Feature. Generated codecs
 * consume the original field or method metadata and emit direct expressions.
 */
public abstract class JsonFieldAccessor {
  private static final boolean USE_JDK25_NATIVE_ACCESS =
      GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && JdkVersion.MAJOR_VERSION >= 25;
  // The Feature calls the ordinary factories during analysis, so Native Image retains the same
  // accessor instances later returned while runtime configurations build interpreted codecs.
  private static final ClassValueCache<ConcurrentMap<Member, JsonFieldAccessor>> NATIVE_ACCESSORS =
      ClassValueCache.newClassKeyCache(32);

  public Object getObject(Object target) {
    throw new UnsupportedOperationException();
  }

  public Field field() {
    return null;
  }

  public Method getter() {
    return null;
  }

  public Method setter() {
    return null;
  }

  public FieldAccessor coreAccessor() {
    return null;
  }

  public boolean getBoolean(Object target) {
    return (Boolean) getObject(target);
  }

  public byte getByte(Object target) {
    return (Byte) getObject(target);
  }

  public short getShort(Object target) {
    return (Short) getObject(target);
  }

  public int getInt(Object target) {
    return (Integer) getObject(target);
  }

  public long getLong(Object target) {
    return (Long) getObject(target);
  }

  public float getFloat(Object target) {
    return (Float) getObject(target);
  }

  public double getDouble(Object target) {
    return (Double) getObject(target);
  }

  public char getChar(Object target) {
    return (Character) getObject(target);
  }

  public void putObject(Object target, Object value) {
    throw new UnsupportedOperationException();
  }

  public void putBoolean(Object target, boolean value) {
    putObject(target, value);
  }

  public void putByte(Object target, byte value) {
    putObject(target, value);
  }

  public void putShort(Object target, short value) {
    putObject(target, value);
  }

  public void putInt(Object target, int value) {
    putObject(target, value);
  }

  public void putLong(Object target, long value) {
    putObject(target, value);
  }

  public void putFloat(Object target, float value) {
    putObject(target, value);
  }

  public void putDouble(Object target, double value) {
    putObject(target, value);
  }

  public void putChar(Object target, char value) {
    putObject(target, value);
  }

  public static JsonFieldAccessor forField(Field field) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return nativeAccessors(field).computeIfAbsent(field, JsonFieldAccessor::newFieldAccessor);
    }
    return newFieldAccessor(field);
  }

  public static JsonFieldAccessor forGetter(Method getter) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return nativeAccessors(getter).computeIfAbsent(getter, JsonFieldAccessor::newGetterAccessor);
    }
    return newGetterAccessor(getter);
  }

  public static JsonFieldAccessor forSetter(Method setter) {
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
      return nativeAccessors(setter).computeIfAbsent(setter, JsonFieldAccessor::newSetterAccessor);
    }
    return newSetterAccessor(setter);
  }

  private static ConcurrentMap<Member, JsonFieldAccessor> nativeAccessors(Member member) {
    return NATIVE_ACCESSORS.get(member.getDeclaringClass(), ConcurrentHashMap::new);
  }

  private static JsonFieldAccessor newFieldAccessor(Member member) {
    Field field = (Field) member;
    if (USE_JDK25_NATIVE_ACCESS) {
      // LambdaMetafactory accepts invocation handles but rejects REF_getField and REF_putField.
      // Typed reflection is faster than JDK 25's VarHandle-backed FieldAccessor in Native Image.
      try {
        return new ReflectiveFieldJsonAccessor(field);
      } catch (RuntimeException e) {
        // Named applications do not need to open model packages to Fory JSON. When JPMS rejects
        // reflective override during image analysis, select the existing trusted field owner once.
        return new FieldJsonAccessor(FieldAccessor.createAccessor(field));
      }
    }
    return new FieldJsonAccessor(FieldAccessor.createAccessor(field));
  }

  private static JsonFieldAccessor newGetterAccessor(Member member) {
    Method getter = (Method) member;
    return USE_JDK25_NATIVE_ACCESS
        ? LambdaGetterJsonAccessor.create(getter)
        : new GetterJsonAccessor(getter);
  }

  private static JsonFieldAccessor newSetterAccessor(Member member) {
    Method setter = (Method) member;
    return USE_JDK25_NATIVE_ACCESS && setter.getParameterCount() == 1
        ? LambdaSetterJsonAccessor.create(setter)
        : new SetterJsonAccessor(setter);
  }

  private static final class FieldJsonAccessor extends JsonFieldAccessor {
    private final FieldAccessor accessor;

    private FieldJsonAccessor(FieldAccessor accessor) {
      this.accessor = accessor;
    }

    @Override
    public FieldAccessor coreAccessor() {
      return accessor;
    }

    @Override
    public Field field() {
      return accessor.getField();
    }

    @Override
    public Object getObject(Object target) {
      return accessor.getObject(target);
    }

    @Override
    public boolean getBoolean(Object target) {
      return accessor.getBoolean(target);
    }

    @Override
    public byte getByte(Object target) {
      return accessor.getByte(target);
    }

    @Override
    public short getShort(Object target) {
      return accessor.getShort(target);
    }

    @Override
    public int getInt(Object target) {
      return accessor.getInt(target);
    }

    @Override
    public long getLong(Object target) {
      return accessor.getLong(target);
    }

    @Override
    public float getFloat(Object target) {
      return accessor.getFloat(target);
    }

    @Override
    public double getDouble(Object target) {
      return accessor.getDouble(target);
    }

    @Override
    public char getChar(Object target) {
      return accessor.getChar(target);
    }

    @Override
    public void putObject(Object target, Object value) {
      accessor.putObject(target, value);
    }

    @Override
    public void putBoolean(Object target, boolean value) {
      accessor.putBoolean(target, value);
    }

    @Override
    public void putByte(Object target, byte value) {
      accessor.putByte(target, value);
    }

    @Override
    public void putShort(Object target, short value) {
      accessor.putShort(target, value);
    }

    @Override
    public void putInt(Object target, int value) {
      accessor.putInt(target, value);
    }

    @Override
    public void putLong(Object target, long value) {
      accessor.putLong(target, value);
    }

    @Override
    public void putFloat(Object target, float value) {
      accessor.putFloat(target, value);
    }

    @Override
    public void putDouble(Object target, double value) {
      accessor.putDouble(target, value);
    }

    @Override
    public void putChar(Object target, char value) {
      accessor.putChar(target, value);
    }
  }

  private static final class ReflectiveFieldJsonAccessor extends JsonFieldAccessor {
    private final Field field;

    private ReflectiveFieldJsonAccessor(Field field) {
      this.field = field;
      field.setAccessible(true);
    }

    @Override
    public Field field() {
      return field;
    }

    @Override
    public Object getObject(Object target) {
      try {
        return field.get(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public boolean getBoolean(Object target) {
      try {
        return field.getBoolean(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public byte getByte(Object target) {
      try {
        return field.getByte(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public short getShort(Object target) {
      try {
        return field.getShort(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public int getInt(Object target) {
      try {
        return field.getInt(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public long getLong(Object target) {
      try {
        return field.getLong(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public float getFloat(Object target) {
      try {
        return field.getFloat(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public double getDouble(Object target) {
      try {
        return field.getDouble(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public char getChar(Object target) {
      try {
        return field.getChar(target);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot read JSON field " + field, e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      try {
        field.set(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putBoolean(Object target, boolean value) {
      try {
        field.setBoolean(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putByte(Object target, byte value) {
      try {
        field.setByte(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putShort(Object target, short value) {
      try {
        field.setShort(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putInt(Object target, int value) {
      try {
        field.setInt(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putLong(Object target, long value) {
      try {
        field.setLong(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putFloat(Object target, float value) {
      try {
        field.setFloat(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putDouble(Object target, double value) {
      try {
        field.setDouble(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }

    @Override
    public void putChar(Object target, char value) {
      try {
        field.setChar(target, value);
      } catch (IllegalAccessException e) {
        throw new ForyJsonException("Cannot write JSON field " + field, e);
      }
    }
  }

  private static final class GetterJsonAccessor extends JsonFieldAccessor {
    private final Method getter;
    private final MethodHandle getterHandle;

    private GetterJsonAccessor(Method getter) {
      this.getter = getter;
      if (AndroidSupport.IS_ANDROID) {
        getter.setAccessible(true);
        getterHandle = null;
      } else {
        getterHandle = methodHandle(getter);
      }
    }

    @Override
    public Method getter() {
      return getter;
    }

    @Override
    public Object getObject(Object target) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          return getter.invoke(target);
        }
        return getterHandle.invoke(target);
      } catch (Throwable e) {
        throw accessException(getter, e);
      }
    }
  }

  private abstract static class LambdaGetterJsonAccessor extends JsonFieldAccessor {
    private final Method getter;

    private LambdaGetterJsonAccessor(Method getter) {
      this.getter = getter;
    }

    @SuppressWarnings("unchecked")
    private static JsonFieldAccessor create(Method getter) {
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(getter.getDeclaringClass());
      try {
        Object function =
            _JDKAccess.makeGetterFunction(lookup, lookup.unreflect(getter), getter.getReturnType());
        Class<?> type = getter.getReturnType();
        if (!type.isPrimitive()) {
          return new ObjectLambdaGetterJsonAccessor(getter, (Function<Object, Object>) function);
        }
        if (type == boolean.class) {
          return new BooleanLambdaGetterJsonAccessor(getter, (Predicate<Object>) function);
        }
        if (type == byte.class) {
          return new ByteLambdaGetterJsonAccessor(getter, (ToByteFunction<Object>) function);
        }
        if (type == short.class) {
          return new ShortLambdaGetterJsonAccessor(getter, (ToShortFunction<Object>) function);
        }
        if (type == int.class) {
          return new IntLambdaGetterJsonAccessor(getter, (ToIntFunction<Object>) function);
        }
        if (type == long.class) {
          return new LongLambdaGetterJsonAccessor(getter, (ToLongFunction<Object>) function);
        }
        if (type == float.class) {
          return new FloatLambdaGetterJsonAccessor(getter, (ToFloatFunction<Object>) function);
        }
        if (type == double.class) {
          return new DoubleLambdaGetterJsonAccessor(getter, (ToDoubleFunction<Object>) function);
        }
        return new CharLambdaGetterJsonAccessor(getter, (ToCharFunction<Object>) function);
      } catch (IllegalAccessException e) {
        throw accessException(getter, e);
      }
    }

    @Override
    public Method getter() {
      return getter;
    }
  }

  private static final class ObjectLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final Function<Object, Object> function;

    private ObjectLambdaGetterJsonAccessor(Method getter, Function<Object, Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public Object getObject(Object target) {
      try {
        return function.apply(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }
  }

  private static final class BooleanLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final Predicate<Object> function;

    private BooleanLambdaGetterJsonAccessor(Method getter, Predicate<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public boolean getBoolean(Object target) {
      try {
        return function.test(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getBoolean(target);
    }
  }

  private static final class ByteLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final ToByteFunction<Object> function;

    private ByteLambdaGetterJsonAccessor(Method getter, ToByteFunction<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public byte getByte(Object target) {
      try {
        return function.applyAsByte(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getByte(target);
    }
  }

  private static final class ShortLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final ToShortFunction<Object> function;

    private ShortLambdaGetterJsonAccessor(Method getter, ToShortFunction<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public short getShort(Object target) {
      try {
        return function.applyAsShort(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getShort(target);
    }
  }

  private static final class IntLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final ToIntFunction<Object> function;

    private IntLambdaGetterJsonAccessor(Method getter, ToIntFunction<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public int getInt(Object target) {
      try {
        return function.applyAsInt(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getInt(target);
    }
  }

  private static final class LongLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final ToLongFunction<Object> function;

    private LongLambdaGetterJsonAccessor(Method getter, ToLongFunction<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public long getLong(Object target) {
      try {
        return function.applyAsLong(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getLong(target);
    }
  }

  private static final class FloatLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final ToFloatFunction<Object> function;

    private FloatLambdaGetterJsonAccessor(Method getter, ToFloatFunction<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public float getFloat(Object target) {
      try {
        return function.applyAsFloat(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getFloat(target);
    }
  }

  private static final class DoubleLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final ToDoubleFunction<Object> function;

    private DoubleLambdaGetterJsonAccessor(Method getter, ToDoubleFunction<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public double getDouble(Object target) {
      try {
        return function.applyAsDouble(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getDouble(target);
    }
  }

  private static final class CharLambdaGetterJsonAccessor extends LambdaGetterJsonAccessor {
    private final ToCharFunction<Object> function;

    private CharLambdaGetterJsonAccessor(Method getter, ToCharFunction<Object> function) {
      super(getter);
      this.function = function;
    }

    @Override
    public char getChar(Object target) {
      try {
        return function.applyAsChar(target);
      } catch (Throwable e) {
        throw accessException(getter(), e);
      }
    }

    @Override
    public Object getObject(Object target) {
      return getChar(target);
    }
  }

  private static final class SetterJsonAccessor extends JsonFieldAccessor {
    private final Method setter;
    private final MethodHandle setterHandle;

    private SetterJsonAccessor(Method setter) {
      this.setter = setter;
      if (AndroidSupport.IS_ANDROID) {
        setter.setAccessible(true);
        setterHandle = null;
      } else {
        setterHandle = methodHandle(setter);
      }
    }

    @Override
    public Method setter() {
      return setter;
    }

    @Override
    public void putObject(Object target, Object value) {
      try {
        if (AndroidSupport.IS_ANDROID) {
          setter.invoke(target, value);
        } else {
          setterHandle.invoke(target, value);
        }
      } catch (Throwable e) {
        throw accessException(setter, e);
      }
    }
  }

  private abstract static class LambdaSetterJsonAccessor extends JsonFieldAccessor {
    private final Method setter;

    private LambdaSetterJsonAccessor(Method setter) {
      this.setter = setter;
    }

    private static JsonFieldAccessor create(Method setter) {
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(setter.getDeclaringClass());
      try {
        MethodHandle handle = lookup.unreflect(setter);
        Class<?> type = setter.getParameterTypes()[0];
        if (!type.isPrimitive()) {
          return new ObjectLambdaSetterJsonAccessor(
              setter, _JDKAccess.makeJDKBiConsumer(lookup, handle));
        }
        if (type == boolean.class) {
          return new BooleanLambdaSetterJsonAccessor(
              setter,
              _JDKAccess.makeObjPrimitiveConsumer(
                  lookup, handle, ObjBooleanConsumer.class, boolean.class));
        }
        if (type == byte.class) {
          return new ByteLambdaSetterJsonAccessor(
              setter,
              _JDKAccess.makeObjPrimitiveConsumer(
                  lookup, handle, ObjByteConsumer.class, byte.class));
        }
        if (type == short.class) {
          return new ShortLambdaSetterJsonAccessor(
              setter,
              _JDKAccess.makeObjPrimitiveConsumer(
                  lookup, handle, ObjShortConsumer.class, short.class));
        }
        if (type == int.class) {
          return new IntLambdaSetterJsonAccessor(
              setter,
              _JDKAccess.makeObjPrimitiveConsumer(lookup, handle, ObjIntConsumer.class, int.class));
        }
        if (type == long.class) {
          return new LongLambdaSetterJsonAccessor(
              setter,
              _JDKAccess.makeObjPrimitiveConsumer(
                  lookup, handle, ObjLongConsumer.class, long.class));
        }
        if (type == float.class) {
          return new FloatLambdaSetterJsonAccessor(
              setter,
              _JDKAccess.makeObjPrimitiveConsumer(
                  lookup, handle, ObjFloatConsumer.class, float.class));
        }
        if (type == double.class) {
          return new DoubleLambdaSetterJsonAccessor(
              setter,
              _JDKAccess.makeObjPrimitiveConsumer(
                  lookup, handle, ObjDoubleConsumer.class, double.class));
        }
        return new CharLambdaSetterJsonAccessor(
            setter,
            _JDKAccess.makeObjPrimitiveConsumer(lookup, handle, ObjCharConsumer.class, char.class));
      } catch (IllegalAccessException e) {
        throw accessException(setter, e);
      }
    }

    @Override
    public Method setter() {
      return setter;
    }
  }

  private static final class ObjectLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final BiConsumer<Object, Object> function;

    private ObjectLambdaSetterJsonAccessor(Method setter, BiConsumer<Object, Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putObject(Object target, Object value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }
  }

  private static final class BooleanLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjBooleanConsumer<Object> function;

    private BooleanLambdaSetterJsonAccessor(Method setter, ObjBooleanConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putBoolean(Object target, boolean value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putBoolean(target, (Boolean) value);
    }
  }

  private static final class ByteLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjByteConsumer<Object> function;

    private ByteLambdaSetterJsonAccessor(Method setter, ObjByteConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putByte(Object target, byte value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putByte(target, (Byte) value);
    }
  }

  private static final class ShortLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjShortConsumer<Object> function;

    private ShortLambdaSetterJsonAccessor(Method setter, ObjShortConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putShort(Object target, short value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putShort(target, (Short) value);
    }
  }

  private static final class IntLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjIntConsumer<Object> function;

    private IntLambdaSetterJsonAccessor(Method setter, ObjIntConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putInt(Object target, int value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putInt(target, (Integer) value);
    }
  }

  private static final class LongLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjLongConsumer<Object> function;

    private LongLambdaSetterJsonAccessor(Method setter, ObjLongConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putLong(Object target, long value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putLong(target, (Long) value);
    }
  }

  private static final class FloatLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjFloatConsumer<Object> function;

    private FloatLambdaSetterJsonAccessor(Method setter, ObjFloatConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putFloat(Object target, float value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putFloat(target, (Float) value);
    }
  }

  private static final class DoubleLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjDoubleConsumer<Object> function;

    private DoubleLambdaSetterJsonAccessor(Method setter, ObjDoubleConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putDouble(Object target, double value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putDouble(target, (Double) value);
    }
  }

  private static final class CharLambdaSetterJsonAccessor extends LambdaSetterJsonAccessor {
    private final ObjCharConsumer<Object> function;

    private CharLambdaSetterJsonAccessor(Method setter, ObjCharConsumer<Object> function) {
      super(setter);
      this.function = function;
    }

    @Override
    public void putChar(Object target, char value) {
      try {
        function.accept(target, value);
      } catch (Throwable e) {
        throw accessException(setter(), e);
      }
    }

    @Override
    public void putObject(Object target, Object value) {
      putChar(target, (Character) value);
    }
  }

  private static MethodHandle methodHandle(Method method) {
    try {
      return _JDKAccess._trustedLookup(method.getDeclaringClass()).unreflect(method);
    } catch (IllegalAccessException e) {
      throw accessException(method, e);
    }
  }

  /** Preserves ordinary property-method failure semantics for generated direct calls. */
  protected static ForyJsonException accessException(Method method, Throwable e) {
    Throwable cause =
        e instanceof InvocationTargetException ? ((InvocationTargetException) e).getCause() : e;
    return new ForyJsonException("Cannot access JSON property method " + method, cause);
  }
}
