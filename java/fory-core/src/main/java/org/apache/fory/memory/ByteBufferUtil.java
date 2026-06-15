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

import java.nio.Buffer;
import java.nio.ByteBuffer;

public class ByteBufferUtil {
  public static final Class<?> HEAP_BYTE_BUFFER_CLASS = ByteBuffer.allocate(0).getClass();
  public static final Class<?> DIRECT_BYTE_BUFFER_CLASS = ByteBuffer.allocateDirect(0).getClass();

  public static void clearBuffer(Buffer buffer) {
    buffer.clear();
  }

  public static void flipBuffer(Buffer buffer) {
    buffer.flip();
  }

  public static void rewind(Buffer buffer) {
    buffer.rewind();
  }

  public static void position(Buffer buffer, int pos) {
    buffer.position(pos);
  }
}
