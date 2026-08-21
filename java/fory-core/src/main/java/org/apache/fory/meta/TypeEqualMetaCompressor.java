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

import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;

/**
 * A {@link MetaCompressor} wrapper which compare equality by the compressor type for better
 * serializer compile cache.
 */
class TypeEqualMetaCompressor implements MetaCompressor {
  private static final Logger LOG = LoggerFactory.getLogger(TypeEqualMetaCompressor.class);

  private final MetaCompressor compressor;

  public TypeEqualMetaCompressor(MetaCompressor compressor) {
    this.compressor = compressor;
    LOG.warn(
        "{} should implement equals/hashCode method, "
            + "otherwise compile cache may won't work. "
            + "Use type to check MetaCompressor identity instead, but this"
            + "may be incorrect if different compressor instance of same type "
            + "indicates different compressor.",
        compressor);
  }

  @Override
  public byte[] compress(byte[] data, int offset, int size) {
    return compressor.compress(data, offset, size);
  }

  @Override
  public byte[] decompress(byte[] data, int offset, int size, int maxOutputSize) {
    return compressor.decompress(data, offset, size, maxOutputSize);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null || obj.getClass() != getClass()) {
      return false;
    }
    return compressor.getClass().equals((((TypeEqualMetaCompressor) obj).compressor).getClass());
  }

  @Override
  public int hashCode() {
    return compressor.getClass().hashCode();
  }
}
