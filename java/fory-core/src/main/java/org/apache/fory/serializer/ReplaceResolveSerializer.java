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

import java.io.Externalizable;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.collection.ClassValueCache;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.platform.internal._JDKAccess;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;

/**
 * Serializer for class which has jdk `writeReplace`/`readResolve` method defined. This serializer
 * will skip classname writing if object returned by `writeReplace` is different from current class.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ReplaceResolveSerializer extends Serializer {
  private static final Logger LOG = LoggerFactory.getLogger(ReplaceResolveSerializer.class);

  /**
   * Mock class of all class which has `writeReplace` method defined, so we can skip serialize those
   * classnames.
   */
  public static class ReplaceStub {}

  protected static final byte ORIGINAL = 0;
  protected static final byte REPLACED_NEW_TYPE = 1;
  protected static final byte REPLACED_SAME_TYPE = 2;

  // Extract Method Info to cache for graalvm build time lambda generation and avoid
  // generate function repeatedly too.
  protected static class ReplaceResolveInfo {
    protected final Method writeReplaceMethod;
    protected final Method readResolveMethod;
    private final Function writeReplaceFunc;
    private final Function readResolveFunc;

    private ReplaceResolveInfo(Class<?> cls) {
      Method writeReplaceMethod, readResolveMethod;
      if (AndroidSupport.IS_ANDROID || GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
        writeReplaceMethod = JavaSerializer.getWriteReplaceMethod(cls);
        readResolveMethod = JavaSerializer.getReadResolveMethod(cls);
      } else if (Serializable.class.isAssignableFrom(cls)) {
        writeReplaceMethod = SerializationHookLookup.getWriteReplaceMethod(cls);
        readResolveMethod = SerializationHookLookup.getReadResolveMethod(cls);
      } else {
        // FIXME class with `writeReplace` method defined should be Serializable,
        //  but hessian ignores this check and many existing system are using hessian,
        //  so we just warn it to keep compatibility with most applications.
        writeReplaceMethod = JavaSerializer.getWriteReplaceMethod(cls);
        readResolveMethod = JavaSerializer.getReadResolveMethod(cls);
        if (writeReplaceMethod != null) {
          LOG.warn(
              "{} doesn't implement {}, but defined writeReplace method {}",
              cls,
              Serializable.class,
              writeReplaceMethod);
        }
        if (readResolveMethod != null) {
          LOG.warn(
              "{} doesn't implement {}, but defined readResolve method {}",
              cls,
              Serializable.class,
              readResolveMethod);
        }
      }
      this.writeReplaceMethod = writeReplaceMethod;
      this.readResolveMethod = readResolveMethod;
      Class<?> declaringClass =
          writeReplaceMethod != null
              ? writeReplaceMethod.getDeclaringClass()
              : (readResolveMethod != null ? readResolveMethod.getDeclaringClass() : null);
      Function writeReplaceFunc = null, readResolveFunc = null;
      if (declaringClass != null) {
        if (AndroidSupport.IS_ANDROID) {
          makeAccessible(writeReplaceMethod);
          makeAccessible(readResolveMethod);
        } else {
          MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(declaringClass);
          if (writeReplaceMethod != null) {
            writeReplaceFunc = makeHookFunc(lookup, writeReplaceMethod);
          }
          if (readResolveMethod != null) {
            readResolveFunc = makeHookFunc(lookup, readResolveMethod);
          }
        }
      }
      this.writeReplaceFunc = writeReplaceFunc;
      this.readResolveFunc = readResolveFunc;
    }

    private static Function makeHookFunc(MethodHandles.Lookup lookup, Method method) {
      MethodHandle handle;
      try {
        handle = lookup.unreflect(method);
      } catch (IllegalAccessException e) {
        throw new ForyException(
            "Failed to access Java replacement hook "
                + method
                + ". "
                + _JDKAccess.jdk25AccessMessage(),
            e);
      }
      try {
        return _JDKAccess.makeJDKFunction(lookup, handle);
      } catch (Throwable e) {
        if (GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE) {
          return new MethodHandleFunction(handle);
        }
        throw ExceptionUtils.throwException(e);
      }
    }

    private static void makeAccessible(Method method) {
      if (method == null) {
        return;
      }
      try {
        method.setAccessible(true);
      } catch (RuntimeException e) {
        throw new ForyException("Failed to make Java replacement hook accessible: " + method, e);
      }
    }

    private static final class MethodHandleFunction implements Function<Object, Object> {
      private final MethodHandle handle;

      private MethodHandleFunction(MethodHandle handle) {
        this.handle = handle;
      }

      @Override
      public Object apply(Object value) {
        try {
          return handle.invoke(value);
        } catch (Throwable e) {
          throw ExceptionUtils.throwException(e);
        }
      }
    }

    Object writeReplace(Object o) {
      if (writeReplaceFunc != null) {
        return writeReplaceFunc.apply(o);
      } else {
        try {
          return writeReplaceMethod.invoke(o);
        } catch (Exception e) {
          ExceptionUtils.throwException(e);
          throw new IllegalStateException(e);
        }
      }
    }

    Object readResolve(Object o) {
      if (readResolveFunc != null) {
        return readResolveFunc.apply(o);
      } else {
        try {
          return readResolveMethod.invoke(o);
        } catch (Exception e) {
          ExceptionUtils.throwException(e);
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private static final ClassValueCache<ReplaceResolveInfo> REPLACE_RESOLVE_INFO_CACHE =
      ClassValueCache.newClassKeyCache(32);

  protected static class MethodInfoCache {
    protected final ReplaceResolveInfo info;
    private final TypeResolver typeResolver;
    private final Class<?> cls;
    private Class<? extends Serializer> serializerClass;

    protected volatile Serializer objectSerializer;

    public MethodInfoCache(ReplaceResolveInfo info, TypeResolver typeResolver, Class<?> cls) {
      this.info = info;
      this.typeResolver = typeResolver;
      this.cls = cls;
    }

    public void setSerializerClass(Class<? extends Serializer> serializerClass) {
      this.serializerClass = serializerClass;
    }

    public void setObjectSerializer(Serializer objectSerializer) {
      this.objectSerializer = objectSerializer;
    }

    public Serializer objectSerializer() {
      Serializer serializer = objectSerializer;
      if (serializer == null) {
        synchronized (this) {
          serializer = objectSerializer;
          if (serializer == null) {
            Class<? extends Serializer> sc = serializerClass;
            if (sc == null) {
              sc = serializerClass = dataSerializerClass(typeResolver, cls, this);
              serializer = objectSerializer;
              if (serializer != null) {
                return serializer;
              }
            }
            serializer = createDataSerializer(typeResolver, cls, sc);
            objectSerializer = serializer;
          }
        }
      }
      return serializer;
    }
  }

  static MethodInfoCache newJDKMethodInfoCache(TypeResolver typeResolver, Class<?> cls) {
    ReplaceResolveInfo replaceResolveInfo =
        REPLACE_RESOLVE_INFO_CACHE.get(cls, () -> new ReplaceResolveInfo(cls));
    MethodInfoCache methodInfoCache = new MethodInfoCache(replaceResolveInfo, typeResolver, cls);
    ClassResolver classResolver = (ClassResolver) typeResolver;
    Serializer registeredSerializer = classResolver.getSerializer(cls, false);
    if (registeredSerializer != null
        && !(registeredSerializer instanceof ReplaceResolveSerializer)) {
      methodInfoCache.setObjectSerializer(registeredSerializer);
      return methodInfoCache;
    }
    methodInfoCache.setSerializerClass(null);
    return methodInfoCache;
  }

  private static Class<? extends Serializer> dataSerializerClass(
      TypeResolver typeResolver, Class<?> cls, MethodInfoCache methodInfoCache) {
    ClassResolver classResolver = (ClassResolver) typeResolver;
    Class<? extends Serializer> serializerClass;
    if (Externalizable.class.isAssignableFrom(cls)) {
      serializerClass = ExternalizableSerializer.class;
    } else if (JavaSerializer.getReadRefMethod(cls, true) == null
        && JavaSerializer.getWriteObjectMethod(cls, true) == null) {
      serializerClass =
          classResolver.getObjectSerializerClass(
              cls,
              sc ->
                  methodInfoCache.setObjectSerializer(createDataSerializer(typeResolver, cls, sc)));
    } else {
      serializerClass = typeResolver.getDefaultJDKStreamSerializerType();
    }
    return serializerClass;
  }

  /**
   * Create data serializer for `cls`. Note that `cls` may be first read by this fory, so there
   * maybe no serializer created for it, `getSerializer(cls, false)` will be null in such cases.
   *
   * @see #readObject
   */
  private static Serializer createDataSerializer(
      TypeResolver typeResolver, Class<?> cls, Class<? extends Serializer> sc) {
    ClassResolver classResolver = (ClassResolver) typeResolver;
    Serializer prev = classResolver.getSerializer(cls, false);
    Serializer serializer = Serializers.newSerializer(typeResolver, cls, sc);
    classResolver.resetSerializer(cls, prev);
    return serializer;
  }

  protected final TypeResolver typeResolver;
  protected final ClassResolver classResolver;
  protected final MethodInfoCache jdkMethodInfoWriteCache;
  protected final TypeInfo writeTypeInfo;
  protected final Map<Class<?>, MethodInfoCache> classTypeInfoHolderMap = new HashMap<>();

  public ReplaceResolveSerializer(TypeResolver typeResolver, Class type) {
    this(typeResolver, type, false, true);
  }

  public ReplaceResolveSerializer(
      TypeResolver typeResolver, Class type, boolean isFinalField, boolean setSerializer) {
    super(typeResolver.getConfig(), type);
    this.typeResolver = typeResolver;
    classResolver = (ClassResolver) typeResolver;
    if (setSerializer) {
      // `setSerializer` before `newJDKMethodInfoCache` since it query classinfo from
      // `classResolver`,
      // which create serializer in turn.
      // ReplaceResolveSerializer is used as data serializer for ImmutableList/Map,
      // which serializer is already set.
      classResolver.setSerializerIfAbsent(type, this);
    }
    if (type != ReplaceStub.class) {
      jdkMethodInfoWriteCache = newJDKMethodInfoCache(typeResolver, type);
      classTypeInfoHolderMap.put(type, jdkMethodInfoWriteCache);
      if (isFinalField) {
        writeTypeInfo = null;
      } else {
        // FIXME new classinfo may miss serializer update in async compilation mode.
        int typeId = classResolver.getTypeIdForTypeDef(type);
        writeTypeInfo = classResolver.newTypeInfo(type, this, typeId);
      }
    } else {
      jdkMethodInfoWriteCache = null;
      writeTypeInfo = null;
    }
  }

  @Override
  public void write(WriteContext writeContext, Object value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    MethodInfoCache jdkMethodInfoCache = this.jdkMethodInfoWriteCache;
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoCache.info;
    Method writeReplaceMethod = replaceResolveInfo.writeReplaceMethod;
    if (writeReplaceMethod != null) {
      Object original = value;
      value = replaceResolveInfo.writeReplace(value);
      // FIXME JDK serialization will update reference table, which will change deserialized object
      // graph.
      //  If fory doesn't update reference table, deserialized object graph will be same,
      //  which is not a problem in almost every case but inconsistent with JDK serialization.
      if (value == null || value.getClass() != type) {
        buffer.writeByte(REPLACED_NEW_TYPE);
        if (!writeContext.writeRefOrNull(value)) {
          // replace original object reference id with new object reference id
          // for later object graph serialization.
          // written `REF_VALUE_FLAG`/`NOT_NULL_VALUE_FLAG` id outside this method call will be
          // ignored.
          writeContext.replaceRef(original, value);
          writeContext.writeNonRef(value);
        }
      } else {
        if (value != original) {
          buffer.writeByte(REPLACED_SAME_TYPE);
          if (!writeContext.writeRefOrNull(value)) {
            // replace original object reference id with new object reference id
            // for later object graph serialization,
            // written `REF_VALUE_FLAG`/`NOT_NULL_VALUE_FLAG` id outside this method call will be
            // ignored.
            writeContext.replaceRef(original, value);
            writeObject(writeContext, value, jdkMethodInfoCache);
          }
        } else {
          buffer.writeByte(ORIGINAL);
          writeObject(writeContext, value, jdkMethodInfoCache);
        }
      }
    } else {
      buffer.writeByte(ORIGINAL);
      writeObject(writeContext, value, jdkMethodInfoCache);
    }
  }

  protected void writeObject(
      WriteContext writeContext, Object value, MethodInfoCache jdkMethodInfoCache) {
    classResolver.writeClassInternal(writeContext, writeTypeInfo);
    jdkMethodInfoCache.objectSerializer().write(writeContext, value);
  }

  @Override
  public Object read(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    byte flag = buffer.readByte();
    if (flag == REPLACED_NEW_TYPE) {
      int outerRefId = readContext.lastPreservedRefId();
      int nextReadRefId = readContext.tryPreserveRefId();
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        // ref value or not-null value
        Object o = readContext.readData(classResolver.readTypeInfo(readContext));
        readContext.setReadRef(nextReadRefId, o);
        readContext.setReadRef(outerRefId, o);
        return o;
      } else {
        return readContext.getReadRef();
      }
    } else if (flag == REPLACED_SAME_TYPE) {
      int outerRefId = readContext.lastPreservedRefId();
      int nextReadRefId = readContext.tryPreserveRefId();
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        // ref value or not-null value
        Object o = readObject(readContext);
        readContext.setReadRef(nextReadRefId, o);
        readContext.setReadRef(outerRefId, o);
        return o;
      } else {
        return readContext.getReadRef();
      }
    } else {
      Preconditions.checkArgument(flag == ORIGINAL);
      return readObject(readContext);
    }
  }

  protected Object readObject(ReadContext readContext) {
    Class cls = classResolver.readClassInternal(readContext);
    MethodInfoCache jdkMethodInfoCache = getMethodInfoCache(cls);
    Object o = jdkMethodInfoCache.objectSerializer().read(readContext);
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoCache.info;
    if (replaceResolveInfo.readResolveMethod == null) {
      return o;
    }
    return replaceResolveInfo.readResolve(o);
  }

  @Override
  public Object copy(CopyContext copyContext, Object originObj) {
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoWriteCache.info;
    if (replaceResolveInfo.writeReplaceMethod == null) {
      return jdkMethodInfoWriteCache.objectSerializer().copy(copyContext, originObj);
    }
    Object newObj = originObj;
    newObj = replaceResolveInfo.writeReplace(newObj);
    if (needToCopyRef) {
      copyContext.reference(originObj, newObj);
    }
    MethodInfoCache jdkMethodInfoCache = getMethodInfoCache(newObj.getClass());
    newObj = jdkMethodInfoCache.objectSerializer().copy(copyContext, newObj);
    replaceResolveInfo = jdkMethodInfoCache.info;
    if (replaceResolveInfo.readResolveMethod != null) {
      newObj = replaceResolveInfo.readResolve(newObj);
    }
    return newObj;
  }

  protected MethodInfoCache getMethodInfoCache(Class<?> cls) {
    MethodInfoCache jdkMethodInfoCache = classTypeInfoHolderMap.get(cls);
    if (jdkMethodInfoCache == null) {
      jdkMethodInfoCache = newJDKMethodInfoCache(typeResolver, cls);
      classTypeInfoHolderMap.put(cls, jdkMethodInfoCache);
    }
    return jdkMethodInfoCache;
  }
}
