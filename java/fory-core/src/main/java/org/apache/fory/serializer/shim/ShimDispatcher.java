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

package org.apache.fory.serializer.shim;

import org.apache.fory.collection.ForyObjectMap;
import org.apache.fory.collection.ObjectMap;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.Serializers;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.MapSerializers;
import org.apache.fory.util.Preconditions;

/** A shim serializer dispatcher to resolve compatibility problems for common used classes. */
@SuppressWarnings("rawtypes")
public class ShimDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(ShimDispatcher.class);

  private final ForyObjectMap<String, Class<? extends Serializer>> className2ShimSerializerClass =
      new ObjectMap<>(4, 0.75f);

  private final TypeResolver typeResolver;

  public ShimDispatcher(TypeResolver typeResolver) {
    this.typeResolver = typeResolver;
  }

  public void initialize() {
    register("com.alibaba.fastjson.JSONObject", MapSerializers.StringKeyMapSerializer.class);
    register("com.alibaba.fastjson.JSONArray", CollectionSerializer.class);
  }

  public boolean contains(Class<?> clazz) {
    return className2ShimSerializerClass.containsKey(clazz.getName());
  }

  public void register(String className, Class<? extends Serializer> serializerClass) {
    Preconditions.checkArgument(className != null, "Class name cannot be null");
    Preconditions.checkArgument(serializerClass != null, "Serializer class cannot be null");

    if (className2ShimSerializerClass.containsKey(className)) {
      throw new IllegalArgumentException(
          "Class "
              + className
              + " has already been registered with serializer:"
              + serializerClass.getName());
    }

    className2ShimSerializerClass.put(className, serializerClass);
  }

  public Serializer<?> getSerializer(Class<?> clazz) {
    String className = clazz.getName();
    if (!className2ShimSerializerClass.containsKey(className)) {
      return null;
    }
    Class<? extends Serializer> serializerClass = className2ShimSerializerClass.get(className);
    try {
      return Serializers.newSerializer(typeResolver, clazz, serializerClass);
    } catch (Throwable e) {
      LOG.warn(
          "Construct shim serializer failed for class [{}] with serializer class [{}]",
          className,
          serializerClass);
      return null;
    }
  }
}
