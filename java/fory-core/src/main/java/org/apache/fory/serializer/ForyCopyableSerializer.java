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
import org.apache.fory.ForyCopyable;
import org.apache.fory.memory.MemoryBuffer;

/** Fory custom copy serializer. see {@link ForyCopyable} */
public class ForyCopyableSerializer<T> extends Serializer<T> {

  private final Serializer<T> serializer;

  public ForyCopyableSerializer(Fory fory, Class<T> type, Serializer<T> serializer) {
    super(fory, type);
    this.serializer = serializer;
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    serializer.write(buffer, value);
  }

  @Override
  public T copy(T obj) {
    return ((ForyCopyable<T>) obj).copy(fory);
  }

  @Override
  public T read(MemoryBuffer buffer) {
    return serializer.read(buffer);
  }
}
