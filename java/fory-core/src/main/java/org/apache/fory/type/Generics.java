/* Copyright (c) 2008-2023, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package org.apache.fory.type;

import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeInfo;

// Derived from
// https://github.com/EsotericSoftware/kryo/blob/135df69526615bb3f6b34846e58ba3fec3b631c3/src/com/esotericsoftware/kryo/util/DefaultGenerics.java.

/**
 * Handles storage of generic type information. TODO(chaokunyang) refactor to support push multiple
 * generics more efficiently. For example, avoid push and pop Map KV generics repeatedly in the
 * loop.
 */
public class Generics {
  private final Fory fory;
  private int genericTypesSize;
  private GenericType[] genericTypes = new GenericType[16];
  // Use depth and `genericTypesSize` as index to query `genericTypes`, this
  // ensures `genericTypes` are dense, which avoid sparse array to waste memory in recursive
  // circular serialization.
  private int[] depths = new int[16];

  public Generics(Fory fory) {
    this.fory = fory;
  }

  /**
   * Sets the type that is currently being serialized. Must be balanced by {@link
   * #popGenericType()}. Between those calls, the {@link GenericType generic type} are returned by
   * {@link #nextGenericType}. Fory serialization depth should be increased after this call and
   * before {@link #nextGenericType}.
   *
   * @see Fory#writeRef(MemoryBuffer, Object, TypeInfo)
   */
  public void pushGenericType(GenericType fieldType) {
    int size = genericTypesSize++;
    GenericType[] genericTypes = this.genericTypes;
    if (size == genericTypes.length) {
      genericTypes = allocateGenericTypes(genericTypes, size);
    }
    genericTypes[size] = fieldType;
    depths[size] = fory.getDepth();
  }

  private GenericType[] allocateGenericTypes(GenericType[] genericTypes, int size) {
    genericTypes = new GenericType[genericTypes.length << 1];
    System.arraycopy(this.genericTypes, 0, genericTypes, 0, size);
    this.genericTypes = genericTypes;
    int[] depthsNew = new int[depths.length << 1];
    System.arraycopy(depths, 0, depthsNew, 0, size);
    depths = depthsNew;
    return genericTypes;
  }

  /**
   * Removes the generic types being tracked since the corresponding {@link
   * #pushGenericType(GenericType)}. This is safe to call even if {@link
   * #pushGenericType(GenericType)} was not called. Fory serialization depth should be decreased
   * before this call and after {@link #nextGenericType}.
   *
   * @see Fory#writeRef(MemoryBuffer, Object, TypeInfo)
   */
  public void popGenericType() {
    int size = genericTypesSize;
    if (size == 0) {
      return;
    }
    size--;
    if (depths[size] < fory.getDepth()) {
      return;
    }
    genericTypes[size] = null;
    genericTypesSize = size;
  }

  /**
   * Returns the current type parameters.
   *
   * @return May be null.
   */
  public GenericType nextGenericType() {
    int index = genericTypesSize;
    if (index > 0) {
      index--;
      GenericType genericType = genericTypes[index];
      // The depth must match to prevent the types being wrong if a serializer doesn't call
      // nextGenericType.
      if (depths[index] == fory.getDepth() - 1) {
        return genericType;
      }
    }
    return null;
  }
}
