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

import java.util.Arrays;
import org.apache.fory.annotation.Internal;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.Platform;
import org.apache.fory.util.MurmurHash3;

@Internal
public final class EncodedMetaString {
  public static final EncodedMetaString EMPTY =
      new EncodedMetaString(new byte[0], MetaString.Encoding.forEmptyStr());
  private static final int HEADER_MASK = 0xff;

  public final byte[] bytes;
  public final long hash;
  public final MetaString.Encoding encoding;
  public final long first8Bytes;
  public final long second8Bytes;

  public EncodedMetaString(byte[] bytes, MetaString.Encoding encoding) {
    this(bytes, computeHash(bytes, encoding));
  }

  public EncodedMetaString(byte[] bytes, long hash) {
    assert hash != 0;
    this.bytes = bytes;
    this.hash = hash;
    this.encoding = MetaString.Encoding.fromInt((int) (hash & HEADER_MASK));
    byte[] data = bytes;
    if (bytes.length < 16) {
      data = new byte[16];
      System.arraycopy(bytes, 0, data, 0, bytes.length);
    }
    first8Bytes = LittleEndian.getInt64(data, Platform.BYTE_ARRAY_OFFSET);
    second8Bytes = LittleEndian.getInt64(data, Platform.BYTE_ARRAY_OFFSET + 8);
  }

  private static long computeHash(byte[] bytes, MetaString.Encoding encoding) {
    long hash = MurmurHash3.murmurhash3_x64_128(bytes, 0, bytes.length, 47)[0];
    hash = Math.abs(hash);
    if (hash == 0) {
      hash += 256;
    }
    hash &= 0xffffffffffffff00L;
    return hash | (encoding.getValue() & HEADER_MASK);
  }

  public String decode(char specialChar1, char specialChar2) {
    return decode(new MetaStringDecoder(specialChar1, specialChar2));
  }

  public String decode(MetaStringDecoder decoder) {
    return decoder.decode(bytes, encoding);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EncodedMetaString)) {
      return false;
    }
    EncodedMetaString that = (EncodedMetaString) o;
    return hash == that.hash;
  }

  @Override
  public int hashCode() {
    return (int) (hash >> 1);
  }

  @Override
  public String toString() {
    return "EncodedMetaString{"
        + "hash="
        + hash
        + ", size="
        + bytes.length
        + ", bytes="
        + Arrays.toString(bytes)
        + '}';
  }
}
