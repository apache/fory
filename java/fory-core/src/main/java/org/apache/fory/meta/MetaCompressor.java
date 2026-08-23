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

package org.apache.fory.meta;

import org.apache.fory.exception.InvalidDataException;

/**
 * An interface used to compress class metadata such as field names and types. The implementation of
 * this interface should be thread safe.
 */
public interface MetaCompressor {
  byte[] compress(byte[] data, int offset, int size);

  /**
   * Decompress at most {@code maxOutputSize} bytes.
   *
   * <p>Implementations must enforce the bound incrementally or before sizing any allocation from
   * metadata declared by the compressed input.
   *
   * @throws InvalidDataException if the output would exceed {@code maxOutputSize}
   */
  byte[] decompress(byte[] data, int offset, int size, int maxOutputSize);

  /**
   * Check whether {@link MetaCompressor} implements `equals/hashCode` method. If not implemented,
   * return {@link TypeEqualMetaCompressor} instead which compare equality by the compressor type
   * for better serializer compile cache.
   */
  static MetaCompressor checkMetaCompressor(MetaCompressor compressor) {
    Class<?> clz = compressor.getClass();
    if (clz != DeflaterMetaCompressor.class) {
      while (clz != null) {
        try {
          clz.getDeclaredMethod("hashCode");
          if (clz == Object.class) {
            return new TypeEqualMetaCompressor(compressor);
          }
          break;
        } catch (NoSuchMethodException e) {
          clz = clz.getSuperclass();
        }
      }
    }
    return compressor;
  }
}
