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

import static org.apache.fory.util.function.Functions.makeGetterFunction;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.apache.fory.Fory;
import org.apache.fory.builder.Generated;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.GraalvmSupport.GraalvmSerializerHolder;
import org.apache.fory.util.StringUtils;
import org.apache.fory.util.unsafe._JDKAccess;

/** Serialization utils and common serializers. */
@SuppressWarnings({"rawtypes", "unchecked"})
public class Serializers {
  // avoid duplicate reflect inspection and cache for graalvm support too.
  private static final Cache<Class, Tuple2<MethodType, MethodHandle>> CTR_MAP;

  static {
    if (GraalvmSupport.isGraalBuildtime()) {
      CTR_MAP = CacheBuilder.newBuilder().concurrencyLevel(32).build();
    } else {
      CTR_MAP = CacheBuilder.newBuilder().weakKeys().softValues().build();
    }
  }

  private static final MethodType SIG1 = MethodType.methodType(void.class, Fory.class, Class.class);
  private static final MethodType SIG2 = MethodType.methodType(void.class, Fory.class);
  private static final MethodType SIG3 = MethodType.methodType(void.class, Class.class);
  private static final MethodType SIG4 = MethodType.methodType(void.class);

  /**
   * Serializer subclass must have a constructor which take parameters of type {@link Fory} and
   * {@link Class}, or {@link Fory} or {@link Class} or no-arg constructor.
   */
  public static <T> Serializer<T> newSerializer(
      Fory fory, Class type, Class<? extends Serializer> serializerClass) {
    Serializer serializer = fory.getClassResolver().getSerializer(type, false);
    try {
      if (serializerClass == ObjectSerializer.class) {
        return new ObjectSerializer(fory, type);
      }
      if (serializerClass == MetaSharedSerializer.class) {
        TypeDef typeDef = fory.getClassResolver().getTypeDef(type, true);
        return new MetaSharedSerializer(fory, type, typeDef);
      }
      Tuple2<MethodType, MethodHandle> ctrInfo = CTR_MAP.getIfPresent(serializerClass);
      if (ctrInfo != null) {
        if (GraalvmSupport.isGraalBuildtime()) {
          if (Generated.class.isAssignableFrom(serializerClass)) {
            return new GraalvmSerializerHolder(fory, type, serializerClass);
          }
        }
        MethodType sig = ctrInfo.f0;
        MethodHandle handle = ctrInfo.f1;
        if (sig.equals(SIG1)) {
          return (Serializer<T>) handle.invoke(fory, type);
        } else if (sig.equals(SIG2)) {
          return (Serializer<T>) handle.invoke(fory);
        } else if (sig.equals(SIG3)) {
          return (Serializer<T>) handle.invoke(type);
        } else {
          return (Serializer<T>) handle.invoke();
        }
      } else {
        return createSerializer(fory, type, serializerClass);
      }
    } catch (InvocationTargetException e) {
      fory.getClassResolver().resetSerializer(type, serializer);
      if (e.getCause() != null) {
        Platform.throwException(e.getCause());
      } else {
        Platform.throwException(e);
      }
    } catch (Throwable t) {
      // Some serializer may set itself in constructor as serializer, but the
      // constructor failed later. For example, some final type field doesn't
      // support serialization.
      fory.getClassResolver().resetSerializer(type, serializer);
      Platform.throwException(t);
    }
    throw new IllegalStateException("unreachable");
  }

  private static <T> Serializer<T> createSerializer(
      Fory fory, Class<?> type, Class<? extends Serializer> serializerClass) throws Throwable {
    MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(serializerClass);
    try {
      MethodHandle ctr = lookup.findConstructor(serializerClass, SIG1);
      CTR_MAP.put(serializerClass, Tuple2.of(SIG1, ctr));
      if (GraalvmSupport.isGraalBuildtime()) {
        if (Generated.class.isAssignableFrom(serializerClass)) {
          return new GraalvmSerializerHolder(fory, type, serializerClass);
        }
      }
      return (Serializer<T>) ctr.invoke(fory, type);
    } catch (NoSuchMethodException e) {
      ExceptionUtils.ignore(e);
    }
    try {
      MethodHandle ctr = lookup.findConstructor(serializerClass, SIG2);
      CTR_MAP.put(serializerClass, Tuple2.of(SIG2, ctr));
      if (GraalvmSupport.isGraalBuildtime()) {
        if (Generated.class.isAssignableFrom(serializerClass)) {
          return new GraalvmSerializerHolder(fory, type, serializerClass);
        }
      }
      return (Serializer<T>) ctr.invoke(fory);
    } catch (NoSuchMethodException e) {
      ExceptionUtils.ignore(e);
    }
    try {
      MethodHandle ctr = lookup.findConstructor(serializerClass, SIG3);
      CTR_MAP.put(serializerClass, Tuple2.of(SIG3, ctr));
      if (GraalvmSupport.isGraalBuildtime()) {
        if (Generated.class.isAssignableFrom(serializerClass)) {
          return new GraalvmSerializerHolder(fory, type, serializerClass);
        }
      }
      return (Serializer<T>) ctr.invoke(type);
    } catch (NoSuchMethodException e) {
      MethodHandle ctr = ReflectionUtils.getCtrHandle(serializerClass);
      CTR_MAP.put(serializerClass, Tuple2.of(SIG4, ctr));
      if (GraalvmSupport.isGraalBuildtime()) {
        if (Generated.class.isAssignableFrom(serializerClass)) {
          return new GraalvmSerializerHolder(fory, type, serializerClass);
        }
      }
      return (Serializer<T>) ctr.invoke();
    }
  }

  public static <T> void write(MemoryBuffer buffer, Serializer<T> serializer, T obj) {
    serializer.write(buffer, obj);
  }

  public static <T> T read(MemoryBuffer buffer, Serializer<T> serializer) {
    return serializer.read(buffer);
  }

  public abstract static class CrossLanguageCompatibleSerializer<T> extends Serializer<T> {

    public CrossLanguageCompatibleSerializer(Fory fory, Class<T> cls) {
      super(fory, cls);
    }

    public CrossLanguageCompatibleSerializer(
        Fory fory, Class<T> cls, boolean needToWriteRef, boolean immutable) {
      super(fory, cls, needToWriteRef, immutable);
    }
  }

  private static final ToIntFunction GET_CODER;
  private static final Function GET_VALUE;

  static {
    GET_VALUE = (Function) makeGetterFunction(StringBuilder.class.getSuperclass(), "getValue");
    ToIntFunction<CharSequence> getCoder;
    try {
      Method getCoderMethod = StringBuilder.class.getSuperclass().getDeclaredMethod("getCoder");
      getCoder = (ToIntFunction<CharSequence>) makeGetterFunction(getCoderMethod, int.class);
    } catch (NoSuchMethodException e) {
      getCoder = null;
    }
    GET_CODER = getCoder;
  }

  public abstract static class AbstractStringBuilderSerializer<T extends CharSequence>
      extends Serializer<T> {
    protected final StringSerializer stringSerializer;

    public AbstractStringBuilderSerializer(Fory fory, Class<T> type) {
      super(fory, type);
      stringSerializer = new StringSerializer(fory);
    }

    @Override
    public void write(MemoryBuffer buffer, T value) {
      if (isJava) {
        if (GET_CODER != null) {
          int coder = GET_CODER.applyAsInt(value);
          byte[] v = (byte[]) GET_VALUE.apply(value);
          int bytesLen = value.length();
          if (coder != 0) {
            if (coder != 1) {
              throw new UnsupportedOperationException("Unsupported coder " + coder);
            }
            bytesLen <<= 1;
          }
          long header = ((long) bytesLen << 2) | coder;
          buffer.writeVarUint64(header);
          buffer.writeBytes(v, 0, bytesLen);
        } else {
          char[] v = (char[]) GET_VALUE.apply(value);
          if (StringUtils.isLatin(v)) {
            stringSerializer.writeCharsLatin1(buffer, v, value.length());
          } else {
            stringSerializer.writeCharsUTF16(buffer, v, value.length());
          }
        }
      } else {
        stringSerializer.writeString(buffer, value.toString());
      }
    }
  }

  public static final class StringBuilderSerializer
      extends AbstractStringBuilderSerializer<StringBuilder> {

    public StringBuilderSerializer(Fory fory) {
      super(fory, StringBuilder.class);
    }

    @Override
    public StringBuilder copy(StringBuilder origin) {
      return new StringBuilder(origin);
    }

    @Override
    public StringBuilder read(MemoryBuffer buffer) {
      if (isJava) {
        return new StringBuilder(stringSerializer.readString(buffer));
      } else {
        return new StringBuilder(stringSerializer.readString(buffer));
      }
    }
  }

  public static final class StringBufferSerializer
      extends AbstractStringBuilderSerializer<StringBuffer> {

    public StringBufferSerializer(Fory fory) {
      super(fory, StringBuffer.class);
    }

    @Override
    public StringBuffer copy(StringBuffer origin) {
      return new StringBuffer(origin);
    }

    @Override
    public StringBuffer read(MemoryBuffer buffer) {
      if (isJava) {
        return new StringBuffer(stringSerializer.readString(buffer));
      } else {
        return new StringBuffer(stringSerializer.readString(buffer));
      }
    }
  }

  public static final class BigDecimalSerializer extends ImmutableSerializer<BigDecimal> {
    public BigDecimalSerializer(Fory fory) {
      super(fory, BigDecimal.class);
    }

    @Override
    public void write(MemoryBuffer buffer, BigDecimal value) {
      final byte[] bytes = value.unscaledValue().toByteArray();
      buffer.writeVarUint32Small7(value.scale());
      buffer.writeVarUint32Small7(value.precision());
      buffer.writeVarUint32Small7(bytes.length);
      buffer.writeBytes(bytes);
    }

    @Override
    public BigDecimal read(MemoryBuffer buffer) {
      int scale = buffer.readVarUint32Small7();
      int precision = buffer.readVarUint32Small7();
      int len = buffer.readVarUint32Small7();
      byte[] bytes = buffer.readBytes(len);
      final BigInteger bigInteger = new BigInteger(bytes);
      return new BigDecimal(bigInteger, scale, new MathContext(precision));
    }
  }

  public static final class BigIntegerSerializer extends ImmutableSerializer<BigInteger> {
    public BigIntegerSerializer(Fory fory) {
      super(fory, BigInteger.class);
    }

    @Override
    public void write(MemoryBuffer buffer, BigInteger value) {
      final byte[] bytes = value.toByteArray();
      buffer.writeVarUint32Small7(bytes.length);
      buffer.writeBytes(bytes);
    }

    @Override
    public BigInteger read(MemoryBuffer buffer) {
      int len = buffer.readVarUint32Small7();
      byte[] bytes = buffer.readBytes(len);
      return new BigInteger(bytes);
    }
  }

  public static final class AtomicBooleanSerializer extends Serializer<AtomicBoolean> {

    public AtomicBooleanSerializer(Fory fory) {
      super(fory, AtomicBoolean.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicBoolean value) {
      buffer.writeBoolean(value.get());
    }

    @Override
    public AtomicBoolean copy(AtomicBoolean origin) {
      return new AtomicBoolean(origin.get());
    }

    @Override
    public AtomicBoolean read(MemoryBuffer buffer) {
      return new AtomicBoolean(buffer.readBoolean());
    }
  }

  public static final class AtomicIntegerSerializer extends Serializer<AtomicInteger> {

    public AtomicIntegerSerializer(Fory fory) {
      super(fory, AtomicInteger.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicInteger value) {
      buffer.writeInt32(value.get());
    }

    @Override
    public AtomicInteger copy(AtomicInteger origin) {
      return new AtomicInteger(origin.get());
    }

    @Override
    public AtomicInteger read(MemoryBuffer buffer) {
      return new AtomicInteger(buffer.readInt32());
    }
  }

  public static final class AtomicLongSerializer extends Serializer<AtomicLong> {

    public AtomicLongSerializer(Fory fory) {
      super(fory, AtomicLong.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicLong value) {
      buffer.writeInt64(value.get());
    }

    @Override
    public AtomicLong copy(AtomicLong origin) {
      return new AtomicLong(origin.get());
    }

    @Override
    public AtomicLong read(MemoryBuffer buffer) {
      return new AtomicLong(buffer.readInt64());
    }
  }

  public static final class AtomicReferenceSerializer extends Serializer<AtomicReference> {

    public AtomicReferenceSerializer(Fory fory) {
      super(fory, AtomicReference.class);
    }

    @Override
    public void write(MemoryBuffer buffer, AtomicReference value) {
      fory.writeRef(buffer, value.get());
    }

    @Override
    public AtomicReference copy(AtomicReference origin) {
      return new AtomicReference(fory.copyObject(origin.get()));
    }

    @Override
    public AtomicReference read(MemoryBuffer buffer) {
      return new AtomicReference(fory.readRef(buffer));
    }
  }

  public static final class CurrencySerializer extends ImmutableSerializer<Currency> {
    public CurrencySerializer(Fory fory) {
      super(fory, Currency.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Currency object) {
      fory.writeString(buffer, object.getCurrencyCode());
    }

    @Override
    public Currency read(MemoryBuffer buffer) {
      String currencyCode = fory.readString(buffer);
      return Currency.getInstance(currencyCode);
    }
  }

  /** Serializer for {@link Charset}. */
  public static final class CharsetSerializer<T extends Charset> extends ImmutableSerializer<T> {
    public CharsetSerializer(Fory fory, Class<T> type) {
      super(fory, type);
    }

    public void write(MemoryBuffer buffer, T object) {
      fory.writeString(buffer, object.name());
    }

    public T read(MemoryBuffer buffer) {
      return (T) Charset.forName(fory.readString(buffer));
    }
  }

  public static final class URISerializer extends ImmutableSerializer<java.net.URI> {

    public URISerializer(Fory fory) {
      super(fory, URI.class);
    }

    @Override
    public void write(MemoryBuffer buffer, final URI uri) {
      fory.writeString(buffer, uri.toString());
    }

    @Override
    public URI read(MemoryBuffer buffer) {
      return URI.create(fory.readString(buffer));
    }
  }

  public static final class RegexSerializer extends ImmutableSerializer<Pattern> {
    public RegexSerializer(Fory fory) {
      super(fory, Pattern.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Pattern pattern) {
      fory.writeString(buffer, pattern.pattern());
      buffer.writeInt32(pattern.flags());
    }

    @Override
    public Pattern read(MemoryBuffer buffer) {
      String regex = fory.readString(buffer);
      int flags = buffer.readInt32();
      return Pattern.compile(regex, flags);
    }
  }

  public static final class UUIDSerializer extends ImmutableSerializer<UUID> {

    public UUIDSerializer(Fory fory) {
      super(fory, UUID.class);
    }

    @Override
    public void write(MemoryBuffer buffer, final UUID uuid) {
      buffer.writeInt64(uuid.getMostSignificantBits());
      buffer.writeInt64(uuid.getLeastSignificantBits());
    }

    @Override
    public UUID read(MemoryBuffer buffer) {
      return new UUID(buffer.readInt64(), buffer.readInt64());
    }
  }

  public static final class ClassSerializer extends ImmutableSerializer<Class> {
    public ClassSerializer(Fory fory) {
      super(fory, Class.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Class value) {
      fory.getClassResolver().writeClassInternal(buffer, value);
    }

    @Override
    public Class read(MemoryBuffer buffer) {
      return fory.getClassResolver().readClassInternal(buffer);
    }
  }

  /**
   * Serializer for empty object of type {@link Object}. Fory disabled serialization for jdk
   * internal types which doesn't implement {@link java.io.Serializable} for security, but empty
   * object is safe and used sometimes, so fory should support its serialization without disable
   * serializable or class registration checks.
   */
  // Use a separate serializer to avoid codegen for empty object.
  public static final class EmptyObjectSerializer extends ImmutableSerializer<Object> {

    public EmptyObjectSerializer(Fory fory) {
      super(fory, Object.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Object value) {}

    @Override
    public Object read(MemoryBuffer buffer) {
      return new Object();
    }
  }

  public static void registerDefaultSerializers(Fory fory) {
    TypeResolver resolver = fory.getTypeResolver();
    resolver.registerInternalSerializer(Class.class, new ClassSerializer(fory));
    resolver.registerInternalSerializer(StringBuilder.class, new StringBuilderSerializer(fory));
    resolver.registerInternalSerializer(StringBuffer.class, new StringBufferSerializer(fory));
    resolver.registerInternalSerializer(BigInteger.class, new BigIntegerSerializer(fory));
    resolver.registerInternalSerializer(BigDecimal.class, new BigDecimalSerializer(fory));
    resolver.registerInternalSerializer(AtomicBoolean.class, new AtomicBooleanSerializer(fory));
    resolver.registerInternalSerializer(AtomicInteger.class, new AtomicIntegerSerializer(fory));
    resolver.registerInternalSerializer(AtomicLong.class, new AtomicLongSerializer(fory));
    resolver.registerInternalSerializer(AtomicReference.class, new AtomicReferenceSerializer(fory));
    resolver.registerInternalSerializer(Currency.class, new CurrencySerializer(fory));
    resolver.registerInternalSerializer(URI.class, new URISerializer(fory));
    resolver.registerInternalSerializer(Pattern.class, new RegexSerializer(fory));
    resolver.registerInternalSerializer(UUID.class, new UUIDSerializer(fory));
    resolver.registerInternalSerializer(Object.class, new EmptyObjectSerializer(fory));
  }
}
