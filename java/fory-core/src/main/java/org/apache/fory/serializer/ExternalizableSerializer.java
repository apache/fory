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
import org.apache.fory.Fory;
import org.apache.fory.io.MemoryBufferObjectInput;
import org.apache.fory.io.MemoryBufferObjectOutput;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;

/** Serializer for class implements {@link Externalizable}. */
public class ExternalizableSerializer<T extends Externalizable>
    extends AbstractObjectSerializer<T> {
  private final MemoryBufferObjectInput objectInput;
  private final MemoryBufferObjectOutput objectOutput;

  public ExternalizableSerializer(Fory fory, Class<T> cls) {
    super(fory, cls);
    objectInput = new MemoryBufferObjectInput(fory, null);
    objectOutput = new MemoryBufferObjectOutput(fory, null);
  }

  @Override
  public void write(MemoryBuffer buffer, T value) {
    if (!isJava) {
      throw new UnsupportedOperationException("Externalizable can only be used in java");
    }
    objectOutput.setBuffer(buffer);
    try {
      value.writeExternal(objectOutput);
    } catch (IOException e) {
      Platform.throwException(e);
    }
  }

  @Override
  public T read(MemoryBuffer buffer) {
    T t = objectCreator.newInstance();
    refResolver.reference(t);
    objectInput.setBuffer(buffer);
    try {
      t.readExternal(objectInput);
    } catch (IOException | ClassNotFoundException e) {
      Platform.throwException(e);
    }
    return t;
  }
}
