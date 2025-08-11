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

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import java.util.Arrays;

public class ZstdMetaCompressor implements MetaCompressor {

  @Override
  public byte[] compress(byte[] data, int offset, int size) {
    long maxCompressedSize = Zstd.compressBound(size);
    if (maxCompressedSize > Integer.MAX_VALUE) {
      throw new ZstdException(Zstd.errGeneric(), "Max output size is greater than MAX_INT");
    }
    byte[] compressedData = new byte[(int) maxCompressedSize];
    long originalSize =
        Zstd.compressByteArray(
            compressedData,
            0,
            (int) maxCompressedSize,
            data,
            offset,
            size,
            Zstd.defaultCompressionLevel());

    return Arrays.copyOf(compressedData, (int) originalSize);
  }

  @Override
  public byte[] decompress(byte[] data, int offset, int size) {
    int decompressedSize = (int) Zstd.getFrameContentSize(data, offset, size, false);
    byte[] decompressedBytes = new byte[decompressedSize];
    long originalSize =
        Zstd.decompressByteArray(decompressedBytes, 0, decompressedSize, data, offset, size);
    return Arrays.copyOf(decompressedBytes, (int) originalSize);
  }

  @Override
  public int hashCode() {
    return ZstdMetaCompressor.class.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o != null && getClass() == o.getClass();
  }
}
