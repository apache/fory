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

package org.apache.fory.resolver;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.ClassDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.NonexistentClass.NonexistentMetaShared;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.type.GenericType;
import org.apache.fory.type.ScalaTypes;

// Internal type dispatcher.
// Do not use this interface outside of fory package
@Internal
public abstract class TypeResolver {
  private final Fory fory;

  protected TypeResolver(Fory fory) {
    this.fory = fory;
  }

  public abstract boolean needToWriteRef(TypeRef<?> typeRef);

  public abstract boolean isRegistered(Class<?> cls);

  public abstract boolean isRegisteredById(Class<?> cls);

  public abstract boolean isRegisteredByName(Class<?> cls);

  public abstract boolean isMonomorphic(Class<?> clz);

  public abstract ClassInfo getClassInfo(Class<?> cls);

  public abstract ClassInfo getClassInfo(Class<?> cls, boolean createIfAbsent);

  public abstract ClassInfo getClassInfo(Class<?> cls, ClassInfoHolder classInfoHolder);

  public abstract void writeClassInfo(MemoryBuffer buffer, ClassInfo classInfo);

  /**
   * Native code for ClassResolver.writeClassInfo is too big to inline, so inline it manually.
   *
   * <p>See `already compiled into a big method` in <a
   * href="https://wiki.openjdk.org/display/HotSpot/Server+Compiler+Inlining+Messages">Server+Compiler+Inlining+Messages</a>
   */
  // Note: Thread safe for jit thread to call.
  public Expression writeClassExpr(
      Expression classResolverRef, Expression buffer, Expression classInfo) {
    return new Invoke(classResolverRef, "writeClassInfo", buffer, classInfo);
  }

  public abstract ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfoHolder classInfoHolder);

  public abstract ClassInfo readClassInfo(MemoryBuffer buffer, ClassInfo classInfoCache);

  public abstract <T> Serializer<T> getSerializer(Class<T> cls);

  public abstract Serializer<?> getRawSerializer(Class<?> cls);

  public abstract <T> void setSerializer(Class<T> cls, Serializer<T> serializer);

  public abstract <T> void setSerializerIfAbsent(Class<T> cls, Serializer<T> serializer);

  public abstract ClassInfo nilClassInfo();

  public abstract ClassInfoHolder nilClassInfoHolder();

  public abstract GenericType buildGenericType(TypeRef<?> typeRef);

  public abstract GenericType buildGenericType(Type type);

  public abstract void initialize();

  public abstract ClassDef getTypeDef(Class<?> cls, boolean resolveParent);

  public final boolean isSerializable(Class<?> cls) {
    if (ReflectionUtils.isAbstract(cls) || cls.isInterface()) {
      return false;
    }
    try {
      getSerializerClass(cls, false);
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public abstract Class<? extends Serializer> getSerializerClass(Class<?> cls);

  public abstract Class<? extends Serializer> getSerializerClass(Class<?> cls, boolean codegen);

  public boolean isCollection(Class<?> cls) {
    if (Collection.class.isAssignableFrom(cls)) {
      return true;
    }
    if (fory.getConfig().isScalaOptimizationEnabled()) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaIterableType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public boolean isSet(Class<?> cls) {
    if (Set.class.isAssignableFrom(cls)) {
      return true;
    }
    if (fory.getConfig().isScalaOptimizationEnabled()) {
      // Scala map is scala iterable too.
      if (ScalaTypes.getScalaMapType().isAssignableFrom(cls)) {
        return false;
      }
      return ScalaTypes.getScalaSetType().isAssignableFrom(cls);
    } else {
      return false;
    }
  }

  public boolean isMap(Class<?> cls) {
    if (cls == NonexistentMetaShared.class) {
      return false;
    }
    return Map.class.isAssignableFrom(cls)
        || (fory.getConfig().isScalaOptimizationEnabled()
            && ScalaTypes.getScalaMapType().isAssignableFrom(cls));
  }

  public DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors, boolean descriptorsGroupedOrdered) {
    return createDescriptorGrouper(descriptors, descriptorsGroupedOrdered, null);
  }

  public abstract DescriptorGrouper createDescriptorGrouper(
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator);

  public abstract Collection<Descriptor> getFieldDescriptors(Class<?> beanClass, boolean b);

  public final Fory getFory() {
    return fory;
  }
}
