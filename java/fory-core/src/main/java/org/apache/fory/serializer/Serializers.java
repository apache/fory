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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
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
import org.apache.fory.collection.Cache;
import org.apache.fory.collection.CacheBuilder;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.config.Config;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.CodegenSerializer.LazyInitBeanSerializer;
import org.apache.fory.serializer.collection.ChildContainerSerializers;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.apache.fory.serializer.collection.MapSerializer;
import org.apache.fory.serializer.collection.MapSerializers;
import org.apache.fory.serializer.scala.SingletonCollectionSerializer;
import org.apache.fory.serializer.scala.SingletonMapSerializer;
import org.apache.fory.serializer.scala.SingletonObjectSerializer;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.GraalvmSupport;
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

  private static final MethodType SIG1 =
      MethodType.methodType(void.class, TypeResolver.class, Class.class);
  private static final MethodType SIG2 = MethodType.methodType(void.class, TypeResolver.class);
  private static final MethodType SIG3 =
      MethodType.methodType(void.class, Config.class, Class.class);
  private static final MethodType SIG4 = MethodType.methodType(void.class, Config.class);
  private static final MethodType SIG5 = MethodType.methodType(void.class, Class.class);
  private static final MethodType SIG6 = MethodType.methodType(void.class);

  /**
   * Serializer subclass must have a constructor which take parameters of type {@link TypeResolver}
   * and {@link Class}, or {@link TypeResolver}, or {@link Config} and {@link Class}, or {@link
   * Config}, or {@link Class}, or no-arg constructor.
   */
  public static <T> Serializer<T> newSerializer(
      Fory fory, Class type, Class<? extends Serializer> serializerClass) {
    return newSerializer(fory.getTypeResolver(), type, serializerClass);
  }

  /**
   * Serializer subclass must have a constructor which take parameters of type {@link TypeResolver}
   * and {@link Class}, or {@link TypeResolver}, or {@link Config} and {@link Class}, or {@link
   * Config}, or {@link Class}, or no-arg constructor.
   */
  public static <T> Serializer<T> newSerializer(
      TypeResolver typeResolver, Class type, Class<? extends Serializer> serializerClass) {
    TypeInfo typeInfo = typeResolver.getTypeInfo(type, false);
    Serializer serializer = typeInfo == null ? null : typeInfo.getSerializer();
    try {
      return buildSerializer(typeResolver, type, serializerClass);
    } catch (Throwable t) {
      // Some serializer may set itself in constructor as serializer, but the
      // constructor failed later. For example, some final type field doesn't
      // support serialization.
      typeResolver.resetSerializer(type, serializer);
      if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
        Platform.throwException(t.getCause());
      }
      Platform.throwException(t);
    }
    throw new IllegalStateException("unreachable");
  }

  private static <T> Serializer<T> buildSerializer(
      TypeResolver typeResolver, Class type, Class<? extends Serializer> serializerClass) {
    try {
      Config config = typeResolver.getConfig();
      Serializer<T> serializer =
          buildBuiltinSerializer(typeResolver, config, type, serializerClass);
      if (serializer != null) {
        return serializer;
      }
      Tuple2<MethodType, MethodHandle> ctrInfo = CTR_MAP.getIfPresent(serializerClass);
      if (ctrInfo != null) {
        MethodType sig = ctrInfo.f0;
        MethodHandle handle = ctrInfo.f1;
        if (sig.equals(SIG1)) {
          return (Serializer<T>) handle.invoke(typeResolver, type);
        } else if (sig.equals(SIG2)) {
          return (Serializer<T>) handle.invoke(typeResolver);
        } else if (sig.equals(SIG3)) {
          return (Serializer<T>) handle.invoke(config, type);
        } else if (sig.equals(SIG4)) {
          return (Serializer<T>) handle.invoke(config);
        } else if (sig.equals(SIG5)) {
          return (Serializer<T>) handle.invoke(type);
        } else {
          return (Serializer<T>) handle.invoke();
        }
      }
      return createSerializer(typeResolver, type, serializerClass);
    } catch (Throwable t) {
      Platform.throwException(t);
      throw new IllegalStateException("unreachable");
    }
  }

  private static <T> Serializer<T> buildBuiltinSerializer(
      TypeResolver typeResolver,
      Config config,
      Class type,
      Class<? extends Serializer> serializerClass) {
    if (serializerClass == ObjectSerializer.class) {
      return new ObjectSerializer(typeResolver, type);
    }
    if (serializerClass == ArraySerializers.ObjectArraySerializer.class) {
      return new ArraySerializers.ObjectArraySerializer(typeResolver, type);
    }
    if (serializerClass == ObjectStreamSerializer.class) {
      return new ObjectStreamSerializer(typeResolver, type);
    }
    if (serializerClass == ExceptionSerializers.ExceptionSerializer.class) {
      return new ExceptionSerializers.ExceptionSerializer(typeResolver, type);
    }
    if (serializerClass == ExceptionSerializers.StackTraceElementSerializer.class) {
      return (Serializer<T>) new ExceptionSerializers.StackTraceElementSerializer(config);
    }
    if (serializerClass == MetaSharedSerializer.class) {
      TypeDef typeDef = typeResolver.getTypeDef(type, true);
      return new MetaSharedSerializer(typeResolver, type, typeDef);
    }
    if (serializerClass == EnumSerializer.class) {
      return (Serializer<T>) new EnumSerializer(config, type);
    }
    if (serializerClass == LambdaSerializer.class) {
      return new LambdaSerializer(typeResolver, type);
    }
    if (serializerClass == JdkProxySerializer.class) {
      return new JdkProxySerializer(typeResolver, type);
    }
    if (serializerClass == ReplaceResolveSerializer.class) {
      return new ReplaceResolveSerializer(typeResolver, type);
    }
    if (serializerClass == ExternalizableSerializer.class) {
      return new ExternalizableSerializer(typeResolver, type);
    }
    if (serializerClass == LazyInitBeanSerializer.class) {
      return new LazyInitBeanSerializer(typeResolver, type);
    }
    if (serializerClass == TimeSerializers.CalendarSerializer.class) {
      return (Serializer<T>) new TimeSerializers.CalendarSerializer(config, type);
    }
    if (serializerClass == TimeSerializers.ZoneIdSerializer.class) {
      return (Serializer<T>) new TimeSerializers.ZoneIdSerializer(config, type);
    }
    if (serializerClass == TimeSerializers.TimeZoneSerializer.class) {
      return (Serializer<T>) new TimeSerializers.TimeZoneSerializer(config, type);
    }
    if (serializerClass == BufferSerializers.ByteBufferSerializer.class) {
      return (Serializer<T>) new BufferSerializers.ByteBufferSerializer(typeResolver, type);
    }
    if (serializerClass == CharsetSerializer.class) {
      return new CharsetSerializer(config, type);
    }
    if (serializerClass == CollectionSerializers.EnumSetSerializer.class) {
      return (Serializer<T>) new CollectionSerializers.EnumSetSerializer(typeResolver, type);
    }
    if (serializerClass == CollectionSerializer.class) {
      return new CollectionSerializer(typeResolver, type);
    }
    if (serializerClass == CollectionSerializers.DefaultJavaCollectionSerializer.class) {
      return new CollectionSerializers.DefaultJavaCollectionSerializer(typeResolver, type);
    }
    if (serializerClass == CollectionSerializers.JDKCompatibleCollectionSerializer.class) {
      return new CollectionSerializers.JDKCompatibleCollectionSerializer(typeResolver, type);
    }
    if (serializerClass == MapSerializer.class) {
      return new MapSerializer(typeResolver, type);
    }
    if (serializerClass == MapSerializers.DefaultJavaMapSerializer.class) {
      return new MapSerializers.DefaultJavaMapSerializer(typeResolver, type);
    }
    if (serializerClass == MapSerializers.JDKCompatibleMapSerializer.class) {
      return new MapSerializers.JDKCompatibleMapSerializer(typeResolver, type);
    }
    if (serializerClass == ChildContainerSerializers.ChildCollectionSerializer.class) {
      return new ChildContainerSerializers.ChildCollectionSerializer(typeResolver, type);
    }
    if (serializerClass == ChildContainerSerializers.ChildArrayListSerializer.class) {
      return new ChildContainerSerializers.ChildArrayListSerializer(typeResolver, type);
    }
    if (serializerClass == ChildContainerSerializers.ChildMapSerializer.class) {
      return new ChildContainerSerializers.ChildMapSerializer(typeResolver, type);
    }
    if (serializerClass == ChildContainerSerializers.ChildSortedSetSerializer.class) {
      return new ChildContainerSerializers.ChildSortedSetSerializer(typeResolver, type);
    }
    if (serializerClass == ChildContainerSerializers.ChildPriorityQueueSerializer.class) {
      return new ChildContainerSerializers.ChildPriorityQueueSerializer(typeResolver, type);
    }
    if (serializerClass == ChildContainerSerializers.ChildSortedMapSerializer.class) {
      return new ChildContainerSerializers.ChildSortedMapSerializer(typeResolver, type);
    }
    if (serializerClass == SingletonCollectionSerializer.class) {
      return new SingletonCollectionSerializer(typeResolver, type);
    }
    if (serializerClass == SingletonMapSerializer.class) {
      return new SingletonMapSerializer(typeResolver, type);
    }
    if (serializerClass == SingletonObjectSerializer.class) {
      return new SingletonObjectSerializer(typeResolver, type);
    }
    return null;
  }

  private static <T> Serializer<T> createSerializer(
      TypeResolver typeResolver, Class<?> type, Class<? extends Serializer> serializerClass) {
    try {
      MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(serializerClass);
      Config config = typeResolver.getConfig();
      try {
        MethodHandle ctr = lookup.findConstructor(serializerClass, SIG1);
        CTR_MAP.put(serializerClass, Tuple2.of(SIG1, ctr));
        return (Serializer<T>) ctr.invoke(typeResolver, type);
      } catch (NoSuchMethodException e) {
        ExceptionUtils.ignore(e);
      }
      try {
        MethodHandle ctr = lookup.findConstructor(serializerClass, SIG2);
        CTR_MAP.put(serializerClass, Tuple2.of(SIG2, ctr));
        return (Serializer<T>) ctr.invoke(typeResolver);
      } catch (NoSuchMethodException e) {
        ExceptionUtils.ignore(e);
      }
      try {
        MethodHandle ctr = lookup.findConstructor(serializerClass, SIG3);
        CTR_MAP.put(serializerClass, Tuple2.of(SIG3, ctr));
        return (Serializer<T>) ctr.invoke(config, type);
      } catch (NoSuchMethodException e) {
        ExceptionUtils.ignore(e);
      }
      try {
        MethodHandle ctr = lookup.findConstructor(serializerClass, SIG4);
        CTR_MAP.put(serializerClass, Tuple2.of(SIG4, ctr));
        return (Serializer<T>) ctr.invoke(config);
      } catch (NoSuchMethodException e) {
        ExceptionUtils.ignore(e);
      }
      try {
        MethodHandle ctr = lookup.findConstructor(serializerClass, SIG5);
        CTR_MAP.put(serializerClass, Tuple2.of(SIG5, ctr));
        return (Serializer<T>) ctr.invoke(type);
      } catch (NoSuchMethodException e) {
        ExceptionUtils.ignore(e);
      }
      MethodHandle ctr = ReflectionUtils.getCtrHandle(serializerClass);
      CTR_MAP.put(serializerClass, Tuple2.of(SIG6, ctr));
      return (Serializer<T>) ctr.invoke();
    } catch (Throwable t) {
      Platform.throwException(t);
      throw new IllegalStateException("unreachable");
    }
  }

  public static <T> void write(WriteContext writeContext, Serializer<T> serializer, T obj) {
    serializer.write(writeContext, obj);
  }

  public static <T> T read(ReadContext readContext, Serializer<T> serializer) {
    return serializer.read(readContext);
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
    private final Config config;

    public AbstractStringBuilderSerializer(Config config, Class<T> type) {
      super(config, type);
      this.config = config;
    }

    @Override
    public void write(WriteContext writeContext, T value) {
      MemoryBuffer buffer = writeContext.getBuffer();
      StringSerializer stringSerializer = writeContext.getStringSerializer();
      if (config.isXlang()) {
        stringSerializer.writeString(buffer, value.toString());
        return;
      }
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
    }
  }

  public static final class StringBuilderSerializer
      extends AbstractStringBuilderSerializer<StringBuilder> {

    public StringBuilderSerializer(Config config) {
      super(config, StringBuilder.class);
    }

    @Override
    public StringBuilder copy(CopyContext copyContext, StringBuilder origin) {
      return new StringBuilder(origin);
    }

    @Override
    public StringBuilder read(ReadContext readContext) {
      return new StringBuilder(readContext.readString());
    }
  }

  public static final class StringBufferSerializer
      extends AbstractStringBuilderSerializer<StringBuffer> {

    public StringBufferSerializer(Config config) {
      super(config, StringBuffer.class);
    }

    @Override
    public StringBuffer copy(CopyContext copyContext, StringBuffer origin) {
      return new StringBuffer(origin);
    }

    @Override
    public StringBuffer read(ReadContext readContext) {
      return new StringBuffer(readContext.readString());
    }
  }

  public static final class AtomicBooleanSerializer extends Serializer<AtomicBoolean>
      implements Shareable {

    public AtomicBooleanSerializer(Config config) {
      super(config, AtomicBoolean.class);
    }

    @Override
    public void write(WriteContext writeContext, AtomicBoolean value) {
      writeContext.getBuffer().writeBoolean(value.get());
    }

    @Override
    public AtomicBoolean copy(CopyContext copyContext, AtomicBoolean origin) {
      return new AtomicBoolean(origin.get());
    }

    @Override
    public AtomicBoolean read(ReadContext readContext) {
      return new AtomicBoolean(readContext.getBuffer().readBoolean());
    }
  }

  public static final class AtomicIntegerSerializer extends Serializer<AtomicInteger>
      implements Shareable {

    public AtomicIntegerSerializer(Config config) {
      super(config, AtomicInteger.class);
    }

    @Override
    public void write(WriteContext writeContext, AtomicInteger value) {
      writeContext.getBuffer().writeInt32(value.get());
    }

    @Override
    public AtomicInteger copy(CopyContext copyContext, AtomicInteger origin) {
      return new AtomicInteger(origin.get());
    }

    @Override
    public AtomicInteger read(ReadContext readContext) {
      return new AtomicInteger(readContext.getBuffer().readInt32());
    }
  }

  public static final class AtomicLongSerializer extends Serializer<AtomicLong>
      implements Shareable {

    public AtomicLongSerializer(Config config) {
      super(config, AtomicLong.class);
    }

    @Override
    public void write(WriteContext writeContext, AtomicLong value) {
      writeContext.getBuffer().writeInt64(value.get());
    }

    @Override
    public AtomicLong copy(CopyContext copyContext, AtomicLong origin) {
      return new AtomicLong(origin.get());
    }

    @Override
    public AtomicLong read(ReadContext readContext) {
      return new AtomicLong(readContext.getBuffer().readInt64());
    }
  }

  public static final class AtomicReferenceSerializer extends Serializer<AtomicReference>
      implements Shareable {

    public AtomicReferenceSerializer(Config config) {
      super(config, AtomicReference.class);
    }

    @Override
    public void write(WriteContext writeContext, AtomicReference value) {
      writeContext.writeRef(value.get());
    }

    @Override
    public AtomicReference copy(CopyContext copyContext, AtomicReference origin) {
      return new AtomicReference(copyContext.copyObject(origin.get()));
    }

    @Override
    public AtomicReference read(ReadContext readContext) {
      return new AtomicReference(readContext.readRef());
    }
  }

  public static final class CurrencySerializer extends ImmutableSerializer<Currency>
      implements Shareable {
    public CurrencySerializer(Config config) {
      super(config, Currency.class);
    }

    @Override
    public void write(WriteContext writeContext, Currency object) {
      writeContext.writeString(object.getCurrencyCode());
    }

    @Override
    public Currency read(ReadContext readContext) {
      return Currency.getInstance(readContext.readString());
    }
  }

  /** Serializer for {@link Charset}. */
  public static final class CharsetSerializer<T extends Charset> extends ImmutableSerializer<T>
      implements Shareable {
    public CharsetSerializer(Config config, Class<T> type) {
      super(config, type);
    }

    public void write(WriteContext writeContext, T object) {
      writeContext.writeString(object.name());
    }

    public T read(ReadContext readContext) {
      return (T) Charset.forName(readContext.readString());
    }
  }

  public static final class URISerializer extends ImmutableSerializer<java.net.URI>
      implements Shareable {

    public URISerializer(Config config) {
      super(config, URI.class);
    }

    @Override
    public void write(WriteContext writeContext, final URI uri) {
      writeContext.writeString(uri.toString());
    }

    @Override
    public URI read(ReadContext readContext) {
      return URI.create(readContext.readString());
    }
  }

  public static final class RegexSerializer extends ImmutableSerializer<Pattern>
      implements Shareable {
    public RegexSerializer(Config config) {
      super(config, Pattern.class);
    }

    @Override
    public void write(WriteContext writeContext, Pattern pattern) {
      MemoryBuffer buffer = writeContext.getBuffer();
      writeContext.writeString(pattern.pattern());
      buffer.writeInt32(pattern.flags());
    }

    @Override
    public Pattern read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      String regex = readContext.readString();
      int flags = buffer.readInt32();
      return Pattern.compile(regex, flags);
    }
  }

  public static final class UUIDSerializer extends ImmutableSerializer<UUID> implements Shareable {

    public UUIDSerializer(Config config) {
      super(config, UUID.class);
    }

    @Override
    public void write(WriteContext writeContext, final UUID uuid) {
      MemoryBuffer buffer = writeContext.getBuffer();
      buffer.writeInt64(uuid.getMostSignificantBits());
      buffer.writeInt64(uuid.getLeastSignificantBits());
    }

    @Override
    public UUID read(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      return new UUID(buffer.readInt64(), buffer.readInt64());
    }
  }

  public static final class ClassSerializer extends ImmutableSerializer<Class>
      implements Shareable {
    public ClassSerializer(Config config) {
      super(config, Class.class);
    }

    @Override
    public void write(WriteContext writeContext, Class value) {
      ((ClassResolver) writeContext.getTypeResolver()).writeClassInternal(writeContext, value);
    }

    @Override
    public Class read(ReadContext readContext) {
      return ((ClassResolver) readContext.getTypeResolver()).readClassInternal(readContext);
    }
  }

  /**
   * Serializer for empty object of type {@link Object}. Fory disabled serialization for jdk
   * internal types which doesn't implement {@link java.io.Serializable} for security, but empty
   * object is safe and used sometimes, so fory should support its serialization without disable
   * serializable or class registration checks.
   */
  // Use a separate serializer to avoid codegen for empty object.
  public static final class EmptyObjectSerializer extends ImmutableSerializer<Object>
      implements Shareable {

    public EmptyObjectSerializer(Config config) {
      super(config, Object.class);
    }

    @Override
    public void write(WriteContext writeContext, Object value) {}

    @Override
    public Object read(ReadContext readContext) {
      return new Object();
    }
  }

  public static void registerDefaultSerializers(TypeResolver resolver) {
    Config config = resolver.getConfig();
    resolver.registerInternalSerializer(Class.class, new ClassSerializer(config));
    resolver.registerInternalSerializer(StringBuilder.class, new StringBuilderSerializer(config));
    resolver.registerInternalSerializer(StringBuffer.class, new StringBufferSerializer(config));
    resolver.registerInternalSerializer(BigInteger.class, new BigIntegerSerializer(config));
    resolver.registerInternalSerializer(BigDecimal.class, new DecimalSerializer(config));
    resolver.registerInternalSerializer(AtomicBoolean.class, new AtomicBooleanSerializer(config));
    resolver.registerInternalSerializer(AtomicInteger.class, new AtomicIntegerSerializer(config));
    resolver.registerInternalSerializer(AtomicLong.class, new AtomicLongSerializer(config));
    resolver.registerInternalSerializer(
        AtomicReference.class, new AtomicReferenceSerializer(config));
    resolver.registerInternalSerializer(Currency.class, new CurrencySerializer(config));
    resolver.registerInternalSerializer(URI.class, new URISerializer(config));
    resolver.registerInternalSerializer(Pattern.class, new RegexSerializer(config));
    resolver.registerInternalSerializer(UUID.class, new UUIDSerializer(config));
    resolver.registerInternalSerializer(Object.class, new EmptyObjectSerializer(config));
  }
}
