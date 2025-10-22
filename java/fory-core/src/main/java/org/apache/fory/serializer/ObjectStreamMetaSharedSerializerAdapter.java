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

import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Adapter to use ObjectSerializer within ObjectStreamSerializer in meta-shared mode.
 * This adapter wraps ObjectSerializer to provide compatibility with CompatibleSerializerBase API.
 */
final class ObjectStreamMetaSharedSerializerAdapter<T> extends CompatibleSerializerBase<T> {
  private final ObjectSerializer<T> objectSerializer;

  public ObjectStreamMetaSharedSerializerAdapter(Fory fory, Class<T> type) {
    super(fory, type);
    // Create ObjectSerializer without resolving parent to avoid duplicate registration
    this.objectSerializer = new ObjectSerializer<>(fory, type, false);
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    objectSerializer.write(buffer, value);
  }

  @Override
  public T read(MemoryBuffer buffer) {
    return objectSerializer.read(buffer);
  }

  @Override
  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    return objectSerializer.readAndSetFields(buffer, obj);
  }
}
