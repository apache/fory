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
import org.apache.fory.reflect.ObjectCreator;

/**
 * Base class for compatible serializer. Both JIT mode serializer and interpreter-mode serializer
 * will extend this class.
 */
public abstract class CompatibleSerializerBase<T> extends AbstractObjectSerializer<T> {
  public CompatibleSerializerBase(Fory fory, Class<T> type) {
    super(fory, type);
  }

  public CompatibleSerializerBase(Fory fory, Class<T> type, ObjectCreator<T> objectCreator) {
    super(fory, type, objectCreator);
  }

  public T readAndSetFields(MemoryBuffer buffer, T obj) {
    // java record object doesn't support update state.
    throw new UnsupportedOperationException();
  }

  /**
   * Write field values array directly.
   * This is used by ObjectOutputStream.PutField scenarios where fields are written as an array.
   *
   * @param buffer Memory buffer to write to
   * @param vals Field values array
   */
  public abstract void writeFieldsValues(MemoryBuffer buffer, Object[] vals);

  /**
   * Read field values array directly.
   * This is used by ObjectInputStream.GetField scenarios where fields are read into an array.
   *
   * @param buffer Memory buffer to read from
   * @param vals Field values array to fill
   */
  public abstract void readFields(MemoryBuffer buffer, Object[] vals);
}
