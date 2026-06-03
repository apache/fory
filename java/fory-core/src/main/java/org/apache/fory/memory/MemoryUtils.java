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

package org.apache.fory.memory;

import java.nio.ByteBuffer;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Memory utils for fory. */
public class MemoryUtils {
  // Android does not expose these private JDK fields. GraalVM native image support is decided by
  // the accessor owner instead of being disabled globally here.
  public static final boolean JDK_INTERNAL_FIELD_ACCESS =
      !AndroidSupport.IS_ANDROID && _JDKAccess.JDK_INTERNAL_FIELD_ACCESS;
  public static final boolean JDK_LANG_FIELD_ACCESS =
      !AndroidSupport.IS_ANDROID && _JDKAccess.JDK_LANG_FIELD_ACCESS;
  public static final boolean JDK_COLLECTION_FIELD_ACCESS =
      !AndroidSupport.IS_ANDROID && _JDKAccess.JDK_COLLECTION_FIELD_ACCESS;
  public static final boolean JDK_CONCURRENT_FIELD_ACCESS =
      !AndroidSupport.IS_ANDROID && _JDKAccess.JDK_CONCURRENT_FIELD_ACCESS;
  public static final boolean JDK_PROXY_FIELD_ACCESS =
      !AndroidSupport.IS_ANDROID && _JDKAccess.JDK_PROXY_FIELD_ACCESS;

  public static MemoryBuffer buffer(int size) {
    return wrap(new byte[size]);
  }

  /**
   * Creates a new memory segment that targets to the given heap memory region.
   *
   * <p>This method should be used to turn short lived byte arrays into memory segments.
   *
   * @param buffer The heap memory region.
   * @return A new memory segment that targets the given heap memory region.
   */
  public static MemoryBuffer wrap(byte[] buffer, int offset, int length) {
    return MemoryBuffer.fromByteArray(buffer, offset, length);
  }

  public static MemoryBuffer wrap(byte[] buffer) {
    return MemoryBuffer.fromByteArray(buffer);
  }

  /**
   * Creates a new memory segment that represents the memory backing the given byte buffer section
   * of [buffer.position(), buffer,limit()).
   *
   * @param buffer a direct buffer or heap buffer
   */
  public static MemoryBuffer wrap(ByteBuffer buffer) {
    if (AndroidSupport.IS_ANDROID) {
      // Android ByteBuffer roots copy into a Fory-owned heap buffer; never read direct-buffer
      // native
      // addresses or depend on read-only buffers exposing arrays.
      return copyToHeapBuffer(buffer);
    } else if (buffer.isDirect()) {
      return MemoryBuffer.fromByteBuffer(buffer);
    } else if (buffer.hasArray()) {
      int offset = buffer.arrayOffset() + buffer.position();
      return MemoryBuffer.fromByteArray(buffer.array(), offset, buffer.remaining());
    } else {
      return copyToHeapBuffer(buffer);
    }
  }

  private static MemoryBuffer copyToHeapBuffer(ByteBuffer buffer) {
    ByteBuffer duplicate = buffer.duplicate();
    byte[] bytes = new byte[duplicate.remaining()];
    duplicate.get(bytes);
    return MemoryBuffer.fromByteArray(bytes);
  }
}
