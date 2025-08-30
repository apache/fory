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

package org.apache.fory.serializer.scala;

import java.lang.reflect.Field;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.serializer.collection.MapLikeSerializer;
import org.apache.fory.util.GraalvmSupport;
import org.apache.fory.util.Preconditions;

/**
 * Singleton serializer for scala map. We need this serializer for fory jit serialization, otherwise
 * the case exception will happen is an empty collection is being serialized as a field of an
 * object.
 */
@SuppressWarnings("rawtypes")
public class SingletonMapSerializer extends MapLikeSerializer {
  private final Field field;
  private Object base = null;
  private long offset = -1;

  public SingletonMapSerializer(Fory fory, Class cls) {
    super(fory, cls, false);
    try {
      field = type.getDeclaredField("MODULE$");
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(type + " doesn't have `MODULE$` field", e);
    }
  }

  @Override
  public Map onMapWrite(MemoryBuffer buffer, Object value) {
    throw new IllegalStateException("unreachable");
  }

  @Override
  public void write(MemoryBuffer buffer, Object value) {}

  @Override
  public Object read(MemoryBuffer buffer) {
    long offset = this.offset;
    if (offset == -1) {
      Preconditions.checkArgument(!GraalvmSupport.isGraalBuildtime());
      offset = this.offset = Platform.UNSAFE.staticFieldOffset(field);
      base = Platform.UNSAFE.staticFieldBase(field);
    }
    return Platform.getObject(base, offset);
  }

  @Override
  public Object onMapCopy(Map map) {
    throw new IllegalStateException("unreachable");
  }

  @Override
  public Object onMapRead(Map map) {
    throw new IllegalStateException("unreachable");
  }
}
