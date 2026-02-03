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
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.unsafe._JDKAccess;

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
      // In JDK17, set private jdk method accessible will fail by default, use ObjectStreamClass
      // instead, since it set accessible.
      if (Serializable.class.isAssignableFrom(cls)) {
        ObjectStreamClass objectStreamClass = ObjectStreamClass.lookup(cls);
        writeReplaceMethod =
            (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "writeReplaceMethod");
        readResolveMethod =
            (Method) ReflectionUtils.getObjectFieldValue(objectStreamClass, "readResolveMethod");
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
        MethodHandles.Lookup lookup = _JDKAccess._trustedLookup(declaringClass);
        try {
          if (writeReplaceMethod != null) {
            writeReplaceFunc =
                _JDKAccess.makeJDKFunction(lookup, lookup.unreflect(writeReplaceMethod));
          }
          if (readResolveMethod != null) {
            readResolveFunc =
                _JDKAccess.makeJDKFunction(lookup, lookup.unreflect(readResolveMethod));
          }
        } catch (Exception e) {
          if (writeReplaceMethod != null && !writeReplaceMethod.isAccessible()) {
            writeReplaceMethod.setAccessible(true);
          }
          if (readResolveMethod != null && !readResolveMethod.isAccessible()) {
            readResolveMethod.setAccessible(true);
          }
        }
      }
      this.writeReplaceFunc = writeReplaceFunc;
      this.readResolveFunc = readResolveFunc;
    }

    Object writeReplace(Object o) {
      if (writeReplaceFunc != null) {
        return writeReplaceFunc.apply(o);
      } else {
        try {
          return writeReplaceMethod.invoke(o);
        } catch (Exception e) {
          Platform.throwException(e);
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
          Platform.throwException(e);
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private static final ClassValue<ReplaceResolveInfo> REPLACE_RESOLVE_INFO_CACHE =
      new ClassValue<ReplaceResolveInfo>() {
        @Override
        protected ReplaceResolveInfo computeValue(Class<?> type) {
          return new ReplaceResolveInfo(type);
        }
      };

  protected static class MethodInfoCache {
    protected final ReplaceResolveInfo info;

    protected Serializer objectSerializer;

    public MethodInfoCache(ReplaceResolveInfo info) {
      this.info = info;
    }

    public void setObjectSerializer(Serializer objectSerializer) {
      this.objectSerializer = objectSerializer;
    }
  }

  static MethodInfoCache newJDKMethodInfoCache(Class<?> cls, Fory fory) {
    ReplaceResolveInfo replaceResolveInfo = REPLACE_RESOLVE_INFO_CACHE.get(cls);
    MethodInfoCache methodInfoCache = new MethodInfoCache(replaceResolveInfo);
    Class<? extends Serializer> serializerClass;
    if (Externalizable.class.isAssignableFrom(cls)) {
      serializerClass = ExternalizableSerializer.class;
    } else if (JavaSerializer.getReadObjectMethod(cls, true) == null
        && JavaSerializer.getWriteObjectMethod(cls, true) == null) {
      serializerClass =
          fory.getClassResolver()
              .getObjectSerializerClass(
                  cls,
                  sc -> methodInfoCache.setObjectSerializer(createDataSerializer(fory, cls, sc)));
    } else {
      serializerClass = fory.getDefaultJDKStreamSerializerType();
    }
    methodInfoCache.setObjectSerializer(createDataSerializer(fory, cls, serializerClass));
    return methodInfoCache;
  }

  /**
   * Create data serializer for `cls`. Note that `cls` may be first read by this fory, so there
   * maybe no serializer created for it, `getSerializer(cls, false)` will be null in such cases.
   *
   * @see #readObject
   */
  private static Serializer createDataSerializer(
      Fory fory, Class<?> cls, Class<? extends Serializer> sc) {
    Serializer prev = fory.getClassResolver().getSerializer(cls, false);
    Serializer serializer = Serializers.newSerializer(fory, cls, sc);
    fory.getClassResolver().resetSerializer(cls, prev);
    return serializer;
  }

  protected final RefResolver refResolver;
  protected final ClassResolver classResolver;
  protected final MethodInfoCache jdkMethodInfoWriteCache;
  protected final TypeInfo writeTypeInfo;
  protected final Map<Class<?>, MethodInfoCache> classTypeInfoHolderMap = new HashMap<>();

  public ReplaceResolveSerializer(Fory fory, Class type) {
    this(fory, type, false, true);
  }

  public ReplaceResolveSerializer(
      Fory fory, Class type, boolean isFinalField, boolean setSerializer) {
    super(fory, type);
    refResolver = fory.getRefResolver();
    classResolver = fory.getClassResolver();
    if (setSerializer) {
      // `setSerializer` before `newJDKMethodInfoCache` since it query classinfo from
      // `classResolver`,
      // which create serializer in turn.
      // ReplaceResolveSerializer is used as data serializer for ImmutableList/Map,
      // which serializer is already set.
      classResolver.setSerializerIfAbsent(type, this);
    }
    if (type != ReplaceStub.class) {
      jdkMethodInfoWriteCache = newJDKMethodInfoCache(type, fory);
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
  public void write(MemoryBuffer buffer, Object value) {
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
        if (!refResolver.writeRefOrNull(buffer, value)) {
          // replace original object reference id with new object reference id
          // for later object graph serialization.
          // written `REF_VALUE_FLAG`/`NOT_NULL_VALUE_FLAG` id outside this method call will be
          // ignored.
          refResolver.replaceRef(original, value);
          fory.writeNonRef(buffer, value);
        }
      } else {
        if (value != original) {
          buffer.writeByte(REPLACED_SAME_TYPE);
          if (!refResolver.writeRefOrNull(buffer, value)) {
            // replace original object reference id with new object reference id
            // for later object graph serialization,
            // written `REF_VALUE_FLAG`/`NOT_NULL_VALUE_FLAG` id outside this method call will be
            // ignored.
            refResolver.replaceRef(original, value);
            writeObject(buffer, value, jdkMethodInfoCache);
          }
        } else {
          buffer.writeByte(ORIGINAL);
          writeObject(buffer, value, jdkMethodInfoCache);
        }
      }
    } else {
      buffer.writeByte(ORIGINAL);
      writeObject(buffer, value, jdkMethodInfoCache);
    }
  }

  protected void writeObject(
      MemoryBuffer buffer, Object value, MethodInfoCache jdkMethodInfoCache) {
    classResolver.writeClassInternal(buffer, writeTypeInfo);
    jdkMethodInfoCache.objectSerializer.write(buffer, value);
  }

  @Override
  public Object read(MemoryBuffer buffer) {
    byte flag = buffer.readByte();
    RefResolver refResolver = this.refResolver;
    if (flag == REPLACED_NEW_TYPE) {
      int outerRefId = refResolver.lastPreservedRefId();
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        // ref value or not-null value
        Object o = fory.readData(buffer, classResolver.readTypeInfo(buffer));
        refResolver.setReadObject(nextReadRefId, o);
        refResolver.setReadObject(outerRefId, o);
        return o;
      } else {
        return refResolver.getReadObject();
      }
    } else if (flag == REPLACED_SAME_TYPE) {
      int outerRefId = refResolver.lastPreservedRefId();
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
        // ref value or not-null value
        Object o = readObject(buffer);
        refResolver.setReadObject(nextReadRefId, o);
        refResolver.setReadObject(outerRefId, o);
        return o;
      } else {
        return refResolver.getReadObject();
      }
    } else {
      Preconditions.checkArgument(flag == ORIGINAL);
      return readObject(buffer);
    }
  }

  protected Object readObject(MemoryBuffer buffer) {
    Class cls = classResolver.readClassInternal(buffer);
    MethodInfoCache jdkMethodInfoCache = getMethodInfoCache(cls);
    Object o = jdkMethodInfoCache.objectSerializer.read(buffer);
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoCache.info;
    if (replaceResolveInfo.readResolveMethod == null) {
      return o;
    }
    return replaceResolveInfo.readResolve(o);
  }

  @Override
  public Object copy(Object originObj) {
    ReplaceResolveInfo replaceResolveInfo = jdkMethodInfoWriteCache.info;
    if (replaceResolveInfo.writeReplaceMethod == null) {
      return jdkMethodInfoWriteCache.objectSerializer.copy(originObj);
    }
    Object newObj = originObj;
    newObj = replaceResolveInfo.writeReplace(newObj);
    if (needToCopyRef) {
      fory.reference(originObj, newObj);
    }
    MethodInfoCache jdkMethodInfoCache = getMethodInfoCache(newObj.getClass());
    newObj = jdkMethodInfoCache.objectSerializer.copy(newObj);
    replaceResolveInfo = jdkMethodInfoCache.info;
    if (replaceResolveInfo.readResolveMethod != null) {
      newObj = replaceResolveInfo.readResolve(newObj);
    }
    return newObj;
  }

  protected MethodInfoCache getMethodInfoCache(Class<?> cls) {
    MethodInfoCache jdkMethodInfoCache = classTypeInfoHolderMap.get(cls);
    if (jdkMethodInfoCache == null) {
      jdkMethodInfoCache = newJDKMethodInfoCache(cls, fory);
      classTypeInfoHolderMap.put(cls, jdkMethodInfoCache);
    }
    return jdkMethodInfoCache;
  }
}
