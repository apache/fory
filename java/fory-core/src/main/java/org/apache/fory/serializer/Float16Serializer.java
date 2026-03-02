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
import org.apache.fory.type.Float16;

public final class Float16Serializer extends ImmutableSerializer<Float16> {

  public Float16Serializer(Fory fory) {
    super(fory, Float16.class);
  }

  @Override
  public void write(MemoryBuffer buffer, Float16 value) {
    buffer.writeInt16(value.toBits());
  }

  @Override
  public Float16 read(MemoryBuffer buffer) {
    return Float16.fromBits(buffer.readInt16());
  }
}
