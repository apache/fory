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

package org.apache.fory.benchmark.data;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.util.Objects;

public class SerializableByteBuffer implements Externalizable {
  private ByteBuffer byteBuffer;

  public SerializableByteBuffer(ByteBuffer byteBuffer) {
    this.byteBuffer = byteBuffer;
  }

  public SerializableByteBuffer() {}

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    out.writeInt(byteBuffer.remaining());
    if (byteBuffer.hasArray()) {
      out.write(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining());
    } else {
      byte[] bytes = new byte[byteBuffer.remaining()];
      byteBuffer.get(bytes);
      out.write(bytes);
    }
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException {
    int size = in.readInt();
    byte[] bytes = new byte[size];
    int read = 0;
    while (read != size) {
      read += in.read(bytes, read, size);
    }
    byteBuffer = ByteBuffer.wrap(bytes);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SerializableByteBuffer that = (SerializableByteBuffer) o;
    return Objects.equals(byteBuffer, that.byteBuffer);
  }

  @Override
  public int hashCode() {
    return Objects.hash(byteBuffer);
  }
}
