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

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.apache.fory.exception.InvalidDataException;

/** A meta compressor based on {@link Deflater} compression algorithm. */
public class DeflaterMetaCompressor implements MetaCompressor {
  @Override
  public byte[] compress(byte[] input, int offset, int size) {
    Deflater deflater = new Deflater();
    deflater.setInput(input, offset, size);
    deflater.finish();
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[128];
    while (!deflater.finished()) {
      int compressedSize = deflater.deflate(buffer);
      outputStream.write(buffer, 0, compressedSize);
    }
    return outputStream.toByteArray();
  }

  @Override
  public byte[] decompress(byte[] input, int offset, int size) {
    Inflater inflater = new Inflater();
    inflater.setInput(input, offset, size);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[128];
    try {
      while (!inflater.finished()) {
        int decompressedSize = inflater.inflate(buffer);
        if (decompressedSize > 0) {
          outputStream.write(buffer, 0, decompressedSize);
          continue;
        }
        if (inflater.needsDictionary()) {
          throw new InvalidDataException("Invalid compressed metadata, dictionary is required.");
        }
        if (inflater.needsInput()) {
          throw new InvalidDataException("Invalid compressed metadata, stream is truncated.");
        }
        throw new InvalidDataException("Invalid compressed metadata, inflater made no progress.");
      }
    } catch (DataFormatException e) {
      throw new InvalidDataException("Invalid compressed metadata format.", e);
    } finally {
      inflater.end();
    }
    return outputStream.toByteArray();
  }

  @Override
  public int hashCode() {
    return DeflaterMetaCompressor.class.hashCode();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o != null && getClass() == o.getClass();
  }
}
