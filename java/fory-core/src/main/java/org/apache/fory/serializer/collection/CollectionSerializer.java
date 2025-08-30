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

package org.apache.fory.serializer.collection;

import java.util.Collection;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.Preconditions;

/** Base serializer for all java collections. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class CollectionSerializer<T extends Collection> extends CollectionLikeSerializer<T> {
  public CollectionSerializer(Fory fory, Class<T> type) {
    super(fory, type);
  }

  public CollectionSerializer(Fory fory, Class<T> type, boolean supportCodegenHook) {
    super(fory, type, supportCodegenHook);
  }

  public CollectionSerializer(
      Fory fory, Class<T> type, boolean supportCodegenHook, boolean immutable) {
    super(fory, type, supportCodegenHook, immutable);
  }

  @Override
  public Collection onCollectionWrite(MemoryBuffer buffer, T value) {
    buffer.writeVarUint32Small7(value.size());
    return value;
  }

  @Override
  public T onCollectionRead(Collection collection) {
    return (T) collection;
  }

  @Override
  public T copy(T originCollection) {
    if (isImmutable()) {
      return originCollection;
    }
    Preconditions.checkArgument(supportCodegenHook);
    Collection newCollection = newCollection(originCollection);
    if (needToCopyRef) {
      fory.reference(originCollection, newCollection);
    }
    copyElements(originCollection, newCollection);
    return (T) newCollection;
  }

  @Override
  public T read(MemoryBuffer buffer) {
    Collection collection = newCollection(buffer);
    int numElements = getAndClearNumElements();
    if (numElements != 0) {
      readElements(fory, buffer, collection, numElements);
    }
    return onCollectionRead(collection);
  }
}
