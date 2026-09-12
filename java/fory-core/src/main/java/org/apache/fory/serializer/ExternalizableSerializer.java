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
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.io.MemoryBufferObjectInput;
import org.apache.fory.io.MemoryBufferObjectOutput;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.util.ExceptionUtils;

/** Serializer for class implements {@link Externalizable}. */
public class ExternalizableSerializer<T extends Externalizable>
    extends AbstractObjectSerializer<T> {
  private final MemoryBufferObjectInput objectInput;
  private final MemoryBufferObjectOutput objectOutput;

  public ExternalizableSerializer(TypeResolver typeResolver, Class<T> cls) {
    super(typeResolver, cls);
    objectInput = new MemoryBufferObjectInput(config, null);
    objectOutput = new MemoryBufferObjectOutput(config, null);
  }

  @Override
  public void write(WriteContext writeContext, T value) {
    if (config.isXlang()) {
      throw new UnsupportedOperationException("Externalizable can only be used in java");
    }
    WriteContext previous = objectOutput.setWriteContext(writeContext);
    try {
      value.writeExternal(objectOutput);
    } catch (IOException e) {
      ExceptionUtils.throwException(e);
    } finally {
      // Nested callbacks reuse this adapter; only the outermost call may clear it.
      if (previous == null) {
        objectOutput.clearWriteContext();
      } else {
        objectOutput.setWriteContext(previous);
      }
    }
  }

  @Override
  public T read(ReadContext readContext) {
    T t = objectInstantiator.newInstance();
    readContext.reference(t);
    ReadContext previous = objectInput.setReadContext(readContext);
    try {
      t.readExternal(objectInput);
    } catch (IOException | ClassNotFoundException e) {
      ExceptionUtils.throwException(e);
    } finally {
      // Restore the enclosing readExternal callback before it resumes reading.
      if (previous == null) {
        objectInput.clearReadContext();
      } else {
        objectInput.setReadContext(previous);
      }
    }
    return t;
  }
}
