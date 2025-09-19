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

package org.apache.fory;

import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.annotation.Internal;
import org.apache.fory.resolver.ClassChecker;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;

public abstract class AbstractThreadSafeFory implements ThreadSafeFory {
  @Override
  public void register(Class<?> clz) {
    registerCallback(fory -> fory.register(clz));
  }

  @Override
  public void register(Class<?> cls, boolean createSerializer) {
    registerCallback(fory -> fory.register(cls, createSerializer));
  }

  @Override
  public void register(Class<?> cls, int id) {
    registerCallback(fory -> fory.register(cls, id));
  }

  @Override
  public void register(Class<?> cls, int id, boolean createSerializer) {
    registerCallback(fory -> fory.register(cls, id, createSerializer));
  }

  @Override
  public void register(Class<?> cls, String typeName) {
    registerCallback(fory -> fory.register(cls, typeName));
  }

  @Override
  public void register(Class<?> cls, String namespace, String typeName) {
    registerCallback(fory -> fory.register(cls, namespace, typeName));
  }

  @Override
  public <T> void registerSerializer(Class<T> type, Class<? extends Serializer> serializerClass) {
    registerCallback(fory -> fory.registerSerializer(type, serializerClass));
  }

  @Override
  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    registerCallback(fory -> fory.registerSerializer(type, serializer));
  }

  @Override
  public void registerSerializer(Class<?> type, Function<Fory, Serializer<?>> serializerCreator) {
    registerCallback(fory -> fory.registerSerializer(type, serializerCreator.apply(fory)));
  }

  @Override
  public void setSerializerFactory(SerializerFactory serializerFactory) {
    registerCallback(fory -> fory.setSerializerFactory(serializerFactory));
  }

  @Override
  public void setClassChecker(ClassChecker classChecker) {
    registerCallback(fory -> fory.getClassResolver().setClassChecker(classChecker));
  }

  @Override
  public void ensureSerializersCompiled() {
    execute(
        fory -> {
          fory.ensureSerializersCompiled();
          return null;
        });
  }

  @Override
  public ClassResolver getClassResolver() {
    return execute(fory -> fory.getClassResolver());
  }

  @Internal
  public abstract void registerCallback(Consumer<Fory> callback);
}
