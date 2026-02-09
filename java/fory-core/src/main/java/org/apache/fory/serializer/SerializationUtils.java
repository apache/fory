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

import java.util.Collection;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.collection.CollectionSerializer;
import org.apache.fory.serializer.collection.MapSerializer;

@Internal
public class SerializationUtils {
  public static TypeResolver getTypeResolver(Fory fory) {
    return fory.isCrossLanguage() ? fory.getXtypeResolver() : fory.getClassResolver();
  }

  public static TypeInfo getTypeInfo(Fory fory, Class<?> cls) {
    return getTypeResolver(fory).getTypeInfo(cls);
  }

  public static void validateSerializer(
      Class<?> type,
      Class<? extends Serializer> serializerClass,
      Class<?> parentType,
      Class<?> requiredSerializerBase) {
    if (!parentType.isAssignableFrom(type)) {
      return;
    }
    boolean valid = requiredSerializerBase.isAssignableFrom(serializerClass);
    if (!valid) {
      throw new IllegalArgumentException(
          "Serializer for type "
              + type.getName()
              + " must extend "
              + requiredSerializerBase.getSimpleName()
              + ", but got "
              + serializerClass.getName());
    }
  }

  public static void validate(Class<?> type, Class<? extends Serializer> serializerClass) {
    validateSerializer(type, serializerClass, Collection.class, CollectionSerializer.class);
    validateSerializer(type, serializerClass, Map.class, MapSerializer.class);
  }
}
