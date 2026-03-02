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

package org.apache.fory.config;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.Platform;
import org.apache.fory.meta.DeflaterMetaCompressor;
import org.apache.fory.meta.MetaCompressor;
import org.apache.fory.pool.ThreadPoolFory;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.JavaSerializer;
import org.apache.fory.serializer.ObjectStreamSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.TimeSerializers;
import org.apache.fory.serializer.collection.GuavaCollectionSerializers;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;

/** Builder class to config and create {@link Fory}. */
// Method naming style for this builder:
// - withXXX: withCodegen
// - verbXXX: requireClassRegistration
@SuppressWarnings("rawtypes")
public final class ForyBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(ForyBuilder.class);

  private static final boolean ENABLE_CLASS_REGISTRATION_FORCIBLY;

  static {
    String flagValue =
        System.getProperty(
            "fory.enable_fory_security_mode_forcibly",
            System.getenv("ENABLE_CLASS_REGISTRATION_FORCIBLY"));
    ENABLE_CLASS_REGISTRATION_FORCIBLY = "true".equals(flagValue) || "1".equals(flagValue);
  }

  String name;
  boolean checkClassVersion = false;
  boolean xlang = false;
  boolean trackingRef = false;
  boolean copyRef = false;
  boolean stringRefIgnored = true;
  boolean timeRefIgnored = true;
  ClassLoader classLoader;
  boolean compressInt = true;
  public LongEncoding longEncoding = LongEncoding.TAGGED;
  boolean compressIntArray = false;
  boolean compressLongArray = false;
  boolean compressString = false;
  Boolean writeNumUtf16BytesForUtf8Encoding;
  CompatibleMode compatibleMode = CompatibleMode.SCHEMA_CONSISTENT;
  boolean checkJdkClassSerializable = true;
  Class<? extends Serializer> defaultJDKStreamSerializerType = ObjectStreamSerializer.class;
  boolean requireClassRegistration = true;
  Boolean metaShareEnabled;
  Boolean scopedMetaShareEnabled;
  boolean codeGenEnabled = true;
  Boolean deserializeUnknownClass;
  boolean asyncCompilationEnabled = false;
  boolean registerGuavaTypes = true;
  boolean scalaOptimizationEnabled = false;
  boolean suppressClassRegistrationWarnings = true;
  UnknownEnumValueStrategy unknownEnumValueStrategy = UnknownEnumValueStrategy.NOT_ALLOWED;
  boolean serializeEnumByName = false;
  int bufferSizeLimitBytes = 128 * 1024;
  MetaCompressor metaCompressor = new DeflaterMetaCompressor();
  int maxDepth = 50;
  int maxBinarySize = -1;
  int maxCollectionSize = -1;
  float mapRefLoadFactor = 0.51f;

  public ForyBuilder() {}

  /**
   * Whether cross-language serialize the object. If you used fory for java only, please keep it in
   * java mode, which will have much better performance.
   */
  public ForyBuilder withLanguage(Language language) {
    this.xlang = language == Language.XLANG;
    return this;
  }

  public ForyBuilder withXlang(boolean xlang) {
    this.xlang = xlang;
    return this;
  }

  /** Whether track shared or circular references. */
  public ForyBuilder withRefTracking(boolean trackingRef) {
    this.trackingRef = trackingRef;
    return this;
  }

  /**
   * Whether track {@link Fory#copy(Object)} shared or circular references.
   *
   * <p>If this option is false, shared reference will be copied into different object, and circular
   * reference copy will raise stack overflow exception.
   *
   * <p>If this option is enabled, the copy performance will be slower.
   */
  public ForyBuilder withRefCopy(boolean copyRef) {
    this.copyRef = copyRef;
    return this;
  }

  /** Whether ignore string shared reference. */
  public ForyBuilder ignoreStringRef(boolean ignoreStringRef) {
    this.stringRefIgnored = ignoreStringRef;
    return this;
  }

  /** ignore Enum Deserialize array out of bounds. */
  public ForyBuilder deserializeUnknownEnumValueAsNull(boolean deserializeUnknownEnumValueAsNull) {
    if (deserializeUnknownEnumValueAsNull) {
      this.unknownEnumValueStrategy = UnknownEnumValueStrategy.RETURN_NULL;
    } else {
      this.unknownEnumValueStrategy = UnknownEnumValueStrategy.NOT_ALLOWED;
    }
    return this;
  }

  /**
   * Sets the strategy applied when deserialization encounters an enum value that cannot be
   * resolved.
   *
   * @param action policy to apply for unknown enum values
   * @return this builder instance for chaining
   */
  public ForyBuilder withUnknownEnumValueStrategy(UnknownEnumValueStrategy action) {
    this.unknownEnumValueStrategy = action;
    return this;
  }

  /** deserialize and serialize enum by name. */
  public ForyBuilder serializeEnumByName(boolean serializeEnumByName) {
    this.serializeEnumByName = serializeEnumByName;
    return this;
  }

  /**
   * Whether ignore reference tracking of all time types registered in {@link TimeSerializers} when
   * ref tracking is enabled.
   *
   * @see Config#isTimeRefIgnored
   */
  public ForyBuilder ignoreTimeRef(boolean ignoreTimeRef) {
    this.timeRefIgnored = ignoreTimeRef;
    return this;
  }

  /** Use variable length encoding for int/long. */
  public ForyBuilder withNumberCompressed(boolean numberCompressed) {
    this.compressInt = numberCompressed;
    withLongCompressed(numberCompressed);
    return this;
  }

  /** Use variable length encoding for int. */
  public ForyBuilder withIntCompressed(boolean intCompressed) {
    this.compressInt = intCompressed;
    return this;
  }

  /**
   * Use variable length encoding for long. Enabled by default, use {@link LongEncoding#TAGGED}
   * (Small long as int) for long encoding.
   */
  public ForyBuilder withLongCompressed(boolean longCompressed) {
    return withLongCompressed(longCompressed ? LongEncoding.TAGGED : LongEncoding.FIXED);
  }

  /** Use variable length encoding for long. */
  public ForyBuilder withLongCompressed(LongEncoding longEncoding) {
    this.longEncoding = Objects.requireNonNull(longEncoding);
    return this;
  }

  /** Whether compress int arrays when values are small. */
  public ForyBuilder withIntArrayCompressed(boolean intArrayCompressed) {
    this.compressIntArray = intArrayCompressed;
    return this;
  }

  /** Whether compress long arrays when values are small. */
  public ForyBuilder withLongArrayCompressed(boolean longArrayCompressed) {
    this.compressLongArray = longArrayCompressed;
    return this;
  }

  /** Whether compress string for small size. */
  public ForyBuilder withStringCompressed(boolean stringCompressed) {
    this.compressString = stringCompressed;
    return this;
  }

  /**
   * Whether write num_bytes of utf16 for utf8 encoding. With this option enabled, fory will write
   * the num_bytes of utf16 before write utf8 encoded data, so that the deserialization can create
   * the appropriate utf16 array for store the data, thus save one copy.
   */
  public ForyBuilder withWriteNumUtf16BytesForUtf8Encoding(
      boolean writeNumUtf16BytesForUtf8Encoding) {
    this.writeNumUtf16BytesForUtf8Encoding = writeNumUtf16BytesForUtf8Encoding;
    return this;
  }

  /**
   * Sets the limit for Fory's internal buffer. If the buffer size exceeds this limit, it will be
   * reset to this limit after every serialization and deserialization.
   *
   * <p>The default is 128k.
   */
  public ForyBuilder withBufferSizeLimitBytes(int bufferSizeLimitBytes) {
    this.bufferSizeLimitBytes = bufferSizeLimitBytes;
    return this;
  }

  /**
   * Set classloader for fory to load classes, this classloader can't up updated. Fory will cache
   * the class meta data, if classloader can be updated, there may be class meta collision if
   * different classloaders have classes with same name.
   *
   * <p>If you want to change classloader, please use {@link org.apache.fory.util.LoaderBinding} or
   * {@link ThreadSafeFory} to setup mapping between classloaders and fory instances.
   */
  public ForyBuilder withClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
    return this;
  }

  /**
   * Set class schema compatible mode.
   *
   * @see CompatibleMode
   */
  public ForyBuilder withCompatibleMode(CompatibleMode compatibleMode) {
    this.compatibleMode = compatibleMode;
    return this;
  }

  public ForyBuilder withCompatible(boolean compatible) {
    return withCompatibleMode(
        compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT);
  }

  /**
   * Whether check class schema consistency, will be disabled automatically when {@link
   * CompatibleMode#COMPATIBLE} is enabled. Do not disable this option unless you can ensure the
   * class won't evolve.
   */
  public ForyBuilder withClassVersionCheck(boolean checkClassVersion) {
    if (xlang && compatibleMode == CompatibleMode.SCHEMA_CONSISTENT && !checkClassVersion) {
      throw new IllegalArgumentException(
          "XLANG Schema consistent mode must enable class version check");
    }
    this.checkClassVersion = checkClassVersion;
    return this;
  }

  /** Whether check classes under `java.*` implement {@link java.io.Serializable}. */
  public ForyBuilder withJdkClassSerializableCheck(boolean checkJdkClassSerializable) {
    this.checkJdkClassSerializable = checkJdkClassSerializable;
    return this;
  }

  /**
   * Whether pre-register guava types such as `RegularImmutableMap`/`RegularImmutableList`. Those
   * types are not public API, but seems pretty stable.
   *
   * @see GuavaCollectionSerializers
   */
  public ForyBuilder registerGuavaTypes(boolean register) {
    this.registerGuavaTypes = register;
    return this;
  }

  /**
   * Whether to require registering classes for serialization, enabled by default. If disabled,
   * unknown classes can be deserialized, which may be insecure and cause remote code execution
   * attack if the classes `constructor`/`equals`/`hashCode` method contain malicious code. Do not
   * disable class registration if you can't ensure your environment are *indeed secure*. We are not
   * responsible for security risks if you disable this option. If you disable this option, you can
   * configure {@link org.apache.fory.resolver.TypeChecker} by {@link
   * org.apache.fory.resolver.TypeResolver#setTypeChecker} to control which classes are allowed
   * being serialized.
   */
  public ForyBuilder requireClassRegistration(boolean requireClassRegistration) {
    this.requireClassRegistration = requireClassRegistration;
    return this;
  }

  /**
   * Whether suppress class registration warnings. The warnings can be used for security audit, but
   * may be annoying, this suppression will be enabled by default.
   *
   * @see Config#suppressClassRegistrationWarnings()
   */
  public ForyBuilder suppressClassRegistrationWarnings(boolean suppress) {
    this.suppressClassRegistrationWarnings = suppress;
    return this;
  }

  /** Whether to enable meta share mode. */
  public ForyBuilder withMetaShare(boolean shareMeta) {
    this.metaShareEnabled = shareMeta;
    if (!shareMeta) {
      scopedMetaShareEnabled = false;
    }
    return this;
  }

  /**
   * Scoped meta share focuses on a single serialization process. Metadata created or identified
   * during this process is exclusive to it and is not shared with by other serializations.
   */
  public ForyBuilder withScopedMetaShare(boolean scoped) {
    scopedMetaShareEnabled = scoped;
    return this;
  }

  /**
   * Set a compressor for meta compression. Note that the passed {@link MetaCompressor} should be
   * thread-safe. By default, a `Deflater` based compressor {@link DeflaterMetaCompressor} will be
   * used. Users can pass other compressor such as `zstd` for better compression rate.
   */
  public ForyBuilder withMetaCompressor(MetaCompressor metaCompressor) {
    this.metaCompressor = MetaCompressor.checkMetaCompressor(metaCompressor);
    return this;
  }

  /**
   * Whether deserialize/skip data of un-existed class.
   *
   * @see Config#deserializeUnknownClass()
   */
  public ForyBuilder withDeserializeUnknownClass(boolean deserializeUnknownClass) {
    this.deserializeUnknownClass = deserializeUnknownClass;
    return this;
  }

  /**
   * Whether enable jit for serialization. When disabled, the first serialization will be faster
   * since no need to generate code, but later will be much slower compared jit mode.
   */
  public ForyBuilder withCodegen(boolean codeGen) {
    this.codeGenEnabled = codeGen;
    return this;
  }

  /**
   * Whether enable async compilation. If enabled, serialization will use interpreter mode
   * serialization first and switch to jit serialization after async serializer jit for a class \ is
   * finished.
   *
   * <p>This option will be disabled automatically for graalvm native image since graalvm native
   * image doesn't support JIT at the image run time.
   *
   * @see Config#isAsyncCompilationEnabled()
   */
  public ForyBuilder withAsyncCompilation(boolean asyncCompilation) {
    this.asyncCompilationEnabled = asyncCompilation;
    return this;
  }

  /**
   * Set max depth for deserialization, when depth exceeds, an exception will be thrown. Default max
   * depth is 50.
   */
  public ForyBuilder withMaxDepth(int maxDepth) {
    Preconditions.checkArgument(maxDepth >= 2, "maxDepth must >= 2 but got %s", maxDepth);
    this.maxDepth = maxDepth;
    return this;
  }

  public ForyBuilder withMaxBinarySize(int maxBinarySize) {
    this.maxBinarySize = maxBinarySize;
    return this;
  }

  public ForyBuilder withMaxCollectionSize(int maxCollectionSize) {
    this.maxCollectionSize = maxCollectionSize;
    return this;
  }

  /** Set loadFactor of MapRefResolver writtenObjects. Default value is 0.51 */
  public ForyBuilder withMapRefLoadFactor(float loadFactor) {
    Preconditions.checkArgument(
        loadFactor > 0 && loadFactor < 1, "loadFactor must > 0 and < 1 but got %s", loadFactor);
    this.mapRefLoadFactor = loadFactor;
    return this;
  }

  /** Whether enable scala-specific serialization optimization. */
  public ForyBuilder withScalaOptimizationEnabled(boolean enableScalaOptimization) {
    this.scalaOptimizationEnabled = enableScalaOptimization;
    if (enableScalaOptimization) {
      try {
        Class.forName(
            ReflectionUtils.getPackage(Fory.class) + ".serializer.scala.ScalaSerializers");
      } catch (ClassNotFoundException e) {
        LOG.warn(
            "`fory-scala` library is not in the classpath, please add it to class path and invoke "
                + "`org.apache.fory.serializer.scala.ScalaSerializers.registerSerializers` for peek performance");
      }
    }
    return this;
  }

  /** Set name for Fory serialization. */
  public ForyBuilder withName(String name) {
    this.name = name;
    return this;
  }

  private void finish() {
    if (classLoader == null) {
      classLoader = Thread.currentThread().getContextClassLoader();
      if (classLoader == null) {
        classLoader = Fory.class.getClassLoader();
      }
    }
    if (xlang) {
      stringRefIgnored = true;
      longEncoding = LongEncoding.VARINT;
      compressInt = true;
      compressString = true;
    }
    if (ENABLE_CLASS_REGISTRATION_FORCIBLY) {
      if (!requireClassRegistration) {
        LOG.warn("Class registration is enabled forcibly.");
        requireClassRegistration = true;
      }
    }
    if (defaultJDKStreamSerializerType == JavaSerializer.class) {
      LOG.warn(
          "JDK serialization is used for types which customized java serialization by "
              + "implementing methods such as writeObject/readObject. This is not secure, try to "
              + "use {} instead, or implement a custom {}.",
          ObjectStreamSerializer.class,
          Serializer.class);
    }
    if (writeNumUtf16BytesForUtf8Encoding == null) {
      writeNumUtf16BytesForUtf8Encoding = !xlang;
    }
    if (compatibleMode == CompatibleMode.COMPATIBLE) {
      checkClassVersion = false;
      if (deserializeUnknownClass == null) {
        deserializeUnknownClass = true;
      }
      if (scopedMetaShareEnabled == null) {
        if (metaShareEnabled == null) {
          metaShareEnabled = true;
          scopedMetaShareEnabled = true;
        } else {
          scopedMetaShareEnabled = false;
        }
      } else {
        if (metaShareEnabled == null) {
          metaShareEnabled = scopedMetaShareEnabled;
        }
      }
    } else {
      if (deserializeUnknownClass == null) {
        deserializeUnknownClass = false;
      }
      if (scopedMetaShareEnabled != null && scopedMetaShareEnabled) {
        LOG.warn("Scoped meta share is for CompatibleMode only, disable it for {}", compatibleMode);
      }
      scopedMetaShareEnabled = false;
      if (metaShareEnabled == null) {
        metaShareEnabled = false;
      }
      if (xlang) {
        checkClassVersion = true;
      }
    }
    if (!requireClassRegistration) {
      LOG.warn(
          "Class registration isn't forced, unknown classes can be deserialized. "
              + "If the environment isn't secure, please enable class registration by "
              + "`ForyBuilder#requireClassRegistration(true)` or configure TypeChecker by "
              + "`TypeResolver#setTypeChecker`");
    }
    if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE && asyncCompilationEnabled) {
      LOG.info("Use sync compilation for graalvm native image since it doesn't support JIT.");
      asyncCompilationEnabled = false;
    }
  }

  /**
   * Create Fory and print exception when failed. Many application will create fory as a static
   * variable, Fory creation exception will be swallowed by {@link NoTypeDefFoundError}. We print
   * exception explicitly for better debugging.
   */
  private static Fory newFory(ForyBuilder builder, ClassLoader classLoader) {
    try {
      return new Fory(builder, classLoader);
    } catch (Throwable t) {
      t.printStackTrace();
      LOG.error("Fory creation failed with classloader {}", classLoader);
      Platform.throwException(t);
      throw new RuntimeException(t);
    }
  }

  public Fory build() {
    finish();
    ClassLoader loader = this.classLoader;
    // clear classLoader to avoid `LoaderBinding#foryFactory` lambda capture classLoader by
    // capturing `ForyBuilder`, which make `classLoader` not able to be gc.
    this.classLoader = null;
    return newFory(this, loader);
  }

  /** Build thread safe fory. */
  public ThreadSafeFory buildThreadSafeFory() {
    return buildThreadLocalFory();
  }

  /** Build thread safe fory backed by {@link ThreadLocalFory}. */
  public ThreadLocalFory buildThreadLocalFory() {
    finish();
    ClassLoader loader = this.classLoader;
    // clear classLoader to avoid `LoaderBinding#foryFactory` lambda capture classLoader by
    // capturing `ForyBuilder`,  which make `classLoader` not able to be gc.
    this.classLoader = null;
    ThreadLocalFory threadSafeFory = new ThreadLocalFory(classLoader -> newFory(this, classLoader));
    threadSafeFory.setClassLoader(loader);
    return threadSafeFory;
  }

  /**
   * Build pooled ThreadSafeFory.
   *
   * @param minPoolSize min pool size
   * @param maxPoolSize max pool size
   * @return ThreadSafeForyPool
   */
  public ThreadSafeFory buildThreadSafeForyPool(int minPoolSize, int maxPoolSize) {
    return buildThreadSafeForyPool(minPoolSize, maxPoolSize, 30L, TimeUnit.SECONDS);
  }

  /**
   * Build pooled ThreadSafeFory.
   *
   * @param minPoolSize min pool size
   * @param maxPoolSize max pool size
   * @param expireTime cache expire time, default 5's
   * @param timeUnit TimeUnit, default SECONDS
   * @return ThreadSafeForyPool
   */
  public ThreadSafeFory buildThreadSafeForyPool(
      int minPoolSize, int maxPoolSize, long expireTime, TimeUnit timeUnit) {
    if (minPoolSize < 0 || maxPoolSize < 0 || minPoolSize > maxPoolSize) {
      throw new IllegalArgumentException(
          String.format(
              "thread safe fory pool's init pool size error, please check it, min:[%s], max:[%s]",
              minPoolSize, maxPoolSize));
    }
    finish();
    ClassLoader loader = this.classLoader;
    this.classLoader = null;
    ThreadSafeFory threadSafeFory =
        new ThreadPoolFory(
            classLoader -> newFory(this, classLoader),
            minPoolSize,
            maxPoolSize,
            expireTime,
            timeUnit);
    threadSafeFory.setClassLoader(loader);
    return threadSafeFory;
  }
}
