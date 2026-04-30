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

import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.RandomAccess;
import org.apache.fory.annotation.Internal;
import org.apache.fory.context.ReadContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeResolver;

/** Serializer for {@link ArrayAsList}. Helper for serialization of other classes. */
@Internal
@SuppressWarnings("rawtypes")
public final class ForyArrayAsListSerializer
    extends CollectionSerializer<ForyArrayAsListSerializer.ArrayAsList> {
  public ForyArrayAsListSerializer(TypeResolver typeResolver) {
    super(typeResolver, ArrayAsList.class, true);
  }

  public Collection newCollection(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    int numElements = buffer.readVarUInt32Small7();
    setNumElements(numElements);
    return new ArrayAsList(numElements);
  }

  /**
   * A List which wrap a Java array into a list, used for serialization only, do not use it in other
   * scenarios.
   */
  @Internal
  public static class ArrayAsList extends AbstractList<Object>
      implements RandomAccess, java.io.Serializable {
    private static final Object[] EMPTY = new Object[0];

    private Object[] array;
    private int size;

    public ArrayAsList(int size) {
      array = new Object[size];
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public boolean add(Object e) {
      array[size++] = e;
      return true;
    }

    @Override
    public Object get(int index) {
      return array[index];
    }

    public void clearArray() {
      size = 0;
      array = EMPTY;
    }

    public void setArray(Object[] a) {
      array = a;
      size = a.length;
    }

    public Object[] getArray() {
      return array;
    }

    @Override
    public Object set(int index, Object element) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int indexOf(Object o) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Object o) {
      throw new UnsupportedOperationException();
    }

    /** Returns original array without copy. */
    @Override
    public Object[] toArray() {
      return array;
    }

    @Override
    public Iterator<Object> iterator() {
      return new Iterator<Object>() {
        private int index;

        @Override
        public boolean hasNext() {
          return index < array.length;
        }

        @Override
        public Object next() {
          return array[index++];
        }
      };
    }
  }
}
