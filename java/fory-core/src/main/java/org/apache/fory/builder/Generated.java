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

package org.apache.fory.builder;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.fory.context.WriteContext;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.AbstractObjectSerializer;
import org.apache.fory.serializer.MetaSharedLayerSerializerBase;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.Preconditions;

/**
 * Since janino doesn't support generics, we use {@link Object} to represent object type rather
 * generic type.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public interface Generated {

  /** Base class for all generated serializers. */
  abstract class GeneratedSerializer extends AbstractObjectSerializer implements Generated {
    public GeneratedSerializer(TypeResolver typeResolver, Class<?> cls) {
      super(typeResolver, cls);
    }

    /**
     * Register notify callback to update final field type serializers if it's jit-able.
     *
     * @param serializerFieldInfos fields name and types whose field value will be updated when jit
     *     succeed for these field types.
     * @see BaseObjectCodecBuilder#getOrCreateSerializer(Class)
     * @see BaseObjectCodecBuilder#genCode
     */
    // Invoked in jit constructor.
    public void registerJITNotifyCallback(
        Serializer subclassSerializer, Object... serializerFieldInfos) {
      if (serializerFieldInfos.length > 0) {
        Map<String, Field> fieldsMap = new HashMap<>(serializerFieldInfos.length / 2);
        for (Field field : getClass().getDeclaredFields()) {
          if (field.getType() == Serializer.class) {
            fieldsMap.put(field.getName(), field);
          }
        }
        for (int i = 0; i < serializerFieldInfos.length; i += 2) {
          String serializerFieldName = (String) serializerFieldInfos[i];
          Class<?> beanFieldType = (Class<?>) serializerFieldInfos[i + 1];
          Field field = Objects.requireNonNull(fieldsMap.get(serializerFieldName));
          typeResolver
              .getJITContext()
              .registerJITNotifyCallback(
                  beanFieldType,
                  new JITContext.NotifyCallback() {
                    @Override
                    public void onNotifyResult(Object result) {
                      Serializer<?> fieldSerializer = typeResolver.getSerializer(beanFieldType);
                      Preconditions.checkState(beanFieldType == fieldSerializer.getType());
                      Preconditions.checkState(result == fieldSerializer.getClass());
                      ReflectionUtils.setObjectFieldValue(
                          subclassSerializer, field, fieldSerializer);
                    }

                    @Override
                    public void onNotifyMissed() {
                      Serializer<?> fieldSerializer = typeResolver.getSerializer(beanFieldType);
                      ReflectionUtils.setObjectFieldValue(
                          subclassSerializer, field, fieldSerializer);
                    }
                  });
        }
      }
    }
  }

  /** Base class for all type consist serializers. */
  abstract class GeneratedObjectSerializer extends GeneratedSerializer implements Generated {
    public GeneratedObjectSerializer(TypeResolver typeResolver, Class<?> cls) {
      super(typeResolver, cls);
    }
  }

  /** Base class for all serializers with meta shared by {@link TypeDef}. */
  abstract class GeneratedMetaSharedSerializer extends GeneratedSerializer implements Generated {
    public static final String SERIALIZER_FIELD_NAME = "serializer";

    /** Will be set in generated constructor by {@link MetaSharedCodecBuilder}. */
    public Serializer serializer;

    public GeneratedMetaSharedSerializer(TypeResolver typeResolver, Class<?> cls) {
      super(typeResolver, cls);
    }

    @Override
    public void write(WriteContext writeContext, Object value) {
      serializer.write(writeContext, value);
    }
  }

  /**
   * Base class for layer serializers with meta shared by {@link TypeDef}. Unlike {@link
   * GeneratedMetaSharedSerializer}, this serializer only handles fields from a single class layer
   * and does not include parent class fields.
   */
  abstract class GeneratedMetaSharedLayerSerializer extends MetaSharedLayerSerializerBase
      implements Generated {
    public GeneratedMetaSharedLayerSerializer(TypeResolver typeResolver, Class<?> cls) {
      super(typeResolver, cls);
    }
  }
}
