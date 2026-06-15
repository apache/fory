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

package org.apache.fory.format.type;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.format.encoder.CustomCodec;
import org.apache.fory.format.encoder.CustomCollectionFactory;

/**
 * Registry of custom codecs and collection factories. Generated row codecs cache the registered
 * instance in a {@code static final} field, so each encode/decode call is a direct invocation.
 */
@Internal
public class CustomTypeEncoderRegistry {
  static final Map<CustomTypeRegistration, CustomCodec<?, ?>> REGISTRY = new ConcurrentHashMap<>();
  static final Map<CustomTypeRegistration, CustomCollectionFactory<?, ?>> COLLECTION_REGISTRY =
      new ConcurrentHashMap<>();

  private static final CustomTypeHandler HANDLER = new RegistryHandler();

  static <T> void registerCustomCodec(
      final CustomTypeRegistration registration, final CustomCodec<T, ?> encoder) {
    REGISTRY.put(registration, encoder);
  }

  static void registerCustomCollection(
      final Class<?> iterableType,
      final Class<?> elementType,
      final CustomCollectionFactory<?, ?> decoder) {
    COLLECTION_REGISTRY.put(new CustomTypeRegistration(iterableType, elementType), decoder);
  }

  /** Handler view used by codegen for type discovery; not on the runtime dispatch path. */
  public static CustomTypeHandler customTypeHandler() {
    return HANDLER;
  }

  /**
   * Look up a codec for {@code fieldType}, preferring a {@code beanType}-scoped registration and
   * falling back to one registered against {@code Object.class}. Public so generated row codecs can
   * invoke it across classloaders.
   */
  public static CustomCodec<?, ?> findCodec(Class<?> beanType, Class<?> fieldType) {
    CustomCodec<?, ?> result = REGISTRY.get(new CustomTypeRegistration(beanType, fieldType));
    if (result == null) {
      result = REGISTRY.get(new CustomTypeRegistration(Object.class, fieldType));
    }
    return result;
  }

  /**
   * Look up a collection factory by container and element type. Public for the same reason as
   * {@link #findCodec}.
   */
  public static CustomCollectionFactory<?, ?> findCollectionFactory(
      Class<?> containerType, Class<?> elementType) {
    return COLLECTION_REGISTRY.get(new CustomTypeRegistration(containerType, elementType));
  }

  private static final class RegistryHandler implements CustomTypeHandler {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T> CustomCodec<T, ?> findCodec(Class<?> beanType, Class<T> fieldType) {
      return (CustomCodec) CustomTypeEncoderRegistry.findCodec(beanType, fieldType);
    }

    @Override
    public CustomCollectionFactory<?, ?> findCollectionFactory(
        Class<?> containerType, Class<?> elementType) {
      return CustomTypeEncoderRegistry.findCollectionFactory(containerType, elementType);
    }
  }
}
