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
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamConstants;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import org.apache.fory.Fory;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.io.ClassLoaderObjectInputStream;
import org.apache.fory.io.MemoryBufferObjectInput;
import org.apache.fory.io.MemoryBufferObjectOutput;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.BigEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.TypeResolver;

/**
 * Serializes objects using Java's built in serialization to be compatible with java serialization.
 * This is very inefficient and should be avoided if possible. User can call {@link
 * Fory#registerSerializer} to avoid this.
 *
 * <p>When a serializer not found and {@link ClassResolver#requireJavaSerialization(Class)} return
 * true, this serializer will be used.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class JavaSerializer extends AbstractObjectSerializer {
  private static final Logger LOG = LoggerFactory.getLogger(JavaSerializer.class);
  private final MemoryBufferObjectInput objectInput;
  private final MemoryBufferObjectOutput objectOutput;

  public JavaSerializer(TypeResolver typeResolver, Class<?> cls) {
    super(typeResolver, cls);
    // TODO(chgaokunyang) enable this check when ObjectSerializer is implemented.
    // Preconditions.checkArgument(ClassResolver.requireJavaSerialization(cls));
    if (cls != SerializedLambda.class) {
      LOG.warn(
          "{} use java built-in serialization, which is inefficient. "
              + "Please replace it with a {} or implements {}",
          cls,
          Serializer.class.getName(),
          Externalizable.class.getName());
    }
    objectInput = new MemoryBufferObjectInput(config, null);
    objectOutput = new MemoryBufferObjectOutput(config, null);
  }

  @Override
  public void write(WriteContext writeContext, Object value) {
    try {
      objectOutput.setWriteContext(writeContext);
      ObjectOutputStream objectOutputStream =
          (ObjectOutputStream) writeContext.getContextObject(objectOutput);
      if (objectOutputStream == null) {
        objectOutputStream = new ObjectOutputStream(objectOutput);
        writeContext.putContextObject(objectOutput, objectOutputStream);
      }
      objectOutputStream.writeObject(value);
      objectOutputStream.flush();
    } catch (IOException e) {
      Platform.throwException(e);
    } finally {
      objectOutput.clearWriteContext();
    }
  }

  @Override
  public Object read(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    try {
      objectInput.setBuffer(buffer);
      objectInput.setReadContext(readContext);
      ObjectInputStream objectInputStream =
          (ObjectInputStream) readContext.getContextObject(objectInput);
      if (objectInputStream == null) {
        objectInputStream =
            new ClassLoaderObjectInputStream(typeResolver.getClassLoader(), objectInput);
        readContext.putContextObject(objectInput, objectInputStream);
      }
      return objectInputStream.readObject();
    } catch (IOException | ClassNotFoundException e) {
      Platform.throwException(e);
    } finally {
      objectInput.clearReadContext();
    }
    throw new IllegalStateException("unreachable code");
  }

  private static final ClassValue<Method> writeObjectMethodCache =
      new ClassValue<Method>() {
        @Override
        protected Method computeValue(Class<?> type) {
          return getWriteObjectMethod(type, true);
        }
      };

  public static Method getWriteObjectMethod(Class<?> clz) {
    return writeObjectMethodCache.get(clz);
  }

  public static Method getWriteObjectMethod(Class<?> clz, boolean searchParent) {
    Method writeObject = getMethod(clz, "writeObject", searchParent);
    if (writeObject != null) {
      if (isWriteObjectMethod(writeObject)) {
        return writeObject;
      }
    }
    return null;
  }

  public static boolean isWriteObjectMethod(Method method) {
    return method.getParameterTypes().length == 1
        && method.getParameterTypes()[0] == ObjectOutputStream.class
        && method.getReturnType() == void.class
        && Modifier.isPrivate(method.getModifiers());
  }

  private static final ClassValue<Method> readObjectMethodCache =
      new ClassValue<Method>() {
        @Override
        protected Method computeValue(Class<?> type) {
          return getReadRefMethod(type, true);
        }
      };

  public static Method getReadRefMethod(Class<?> clz) {
    return readObjectMethodCache.get(clz);
  }

  public static Method getReadRefMethod(Class<?> clz, boolean searchParent) {
    Method readObject = getMethod(clz, "readObject", searchParent);
    if (readObject != null && isReadObjectMethod(readObject)) {
      return readObject;
    }
    return null;
  }

  public static boolean isReadObjectMethod(Method method) {
    return method.getParameterTypes().length == 1
        && method.getParameterTypes()[0] == ObjectInputStream.class
        && method.getReturnType() == void.class
        && Modifier.isPrivate(method.getModifiers());
  }

  public static Method getReadRefNoData(Class<?> clz, boolean searchParent) {
    Method method = getMethod(clz, "readObjectNoData", searchParent);
    if (method != null
        && method.getParameterTypes().length == 0
        && method.getReturnType() == void.class
        && Modifier.isPrivate(method.getModifiers())) {
      return method;
    }
    return null;
  }

  private static final ClassValue<Method> readResolveCache =
      new ClassValue<Method>() {
        @Override
        protected Method computeValue(Class<?> type) {
          Method readResolve = getMethod(type, "readResolve", true);
          if (readResolve != null) {
            if (readResolve.getParameterTypes().length == 0
                && readResolve.getReturnType() == Object.class) {
              return readResolve;
            } else {
              LOG.warn(
                  "`readResolve` method doesn't match signature: `ANY-ACCESS-MODIFIER Object readResolve()`");
            }
          }
          return null;
        }
      };

  public static Method getReadResolveMethod(Class<?> clz) {
    return readResolveCache.get(clz);
  }

  private static final ClassValue<Method> writeReplaceCache =
      new ClassValue<Method>() {
        @Override
        protected Method computeValue(Class<?> type) {
          Method writeReplace = getMethod(type, "writeReplace", true);
          if (writeReplace != null) {
            if (writeReplace.getParameterTypes().length == 0
                && writeReplace.getReturnType() == Object.class) {
              return writeReplace;
            } else {
              LOG.warn(
                  "`writeReplace` method doesn't match signature: `ANY-ACCESS-MODIFIER Object writeReplace()");
            }
          }
          return null;
        }
      };

  public static Method getWriteReplaceMethod(Class<?> clz) {
    return writeReplaceCache.get(clz);
  }

  private static Method getMethod(Class<?> clz, String methodName, boolean searchParent) {
    Class<?> cls = clz;
    do {
      for (Method method : cls.getDeclaredMethods()) {
        if (method.getName().equals(methodName)) {
          return method;
        }
      }
      cls = cls.getSuperclass();
    } while (cls != null && searchParent);
    return null;
  }

  /**
   * Return true if current binary is serialized by JDK {@link ObjectOutputStream}.
   *
   * @see #serializedByJDK(byte[], int)
   */
  public static boolean serializedByJDK(byte[] data) {
    return serializedByJDK(data, 0);
  }

  /**
   * Return true if current binary is serialized by JDK {@link ObjectOutputStream}.
   *
   * <p>Note that one can fake magic number {@link ObjectStreamConstants#STREAM_MAGIC}, please use
   * this method carefully in a trusted environment. And it's not a strict check, if this method
   * return true, the data may be not serialized by JDK if other framework generate same magic
   * number by accident. But if this method return false, the data are definitely not serialized by
   * JDK.
   */
  public static boolean serializedByJDK(byte[] data, int offset) {
    // JDK serialization use big endian byte order.
    short magicNumber = BigEndian.getShortB(data, offset);
    return magicNumber == ObjectStreamConstants.STREAM_MAGIC;
  }

  /**
   * Return true if current binary is serialized by JDK {@link ObjectOutputStream}.
   *
   * @see #serializedByJDK(byte[], int)
   */
  public static boolean serializedByJDK(ByteBuffer buffer, int offset) {
    // (short) ((b[off + 1] & 0xFF) + (b[off] << 8));
    byte b1 = buffer.get(offset + 1);
    byte b0 = buffer.get(offset);
    short magicNumber = (short) ((b1 & 0xFF) + (b0 << 8));
    return magicNumber == ObjectStreamConstants.STREAM_MAGIC;
  }
}
