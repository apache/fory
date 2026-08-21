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

package org.apache.fory.io;

import java.nio.ByteBuffer;
import org.apache.fory.memory.MemoryBuffer;

/**
 * An abstract {@link ForyStreamReader} for subclass implementation convenience.
 *
 * <p>Data-loading methods throw by default. Subclasses must override every loading operation they
 * support so that an inherited no-op cannot leave {@link MemoryBuffer} without the bytes required
 * by a following read.
 */
public abstract class AbstractStreamReader implements ForyStreamReader {
  @Override
  public int fillBuffer(int minFillSize) {
    throw new IndexOutOfBoundsException("Subclasses must override fillBuffer");
  }

  @Override
  public void readTo(byte[] dst, int dstIndex, int length) {
    throw new IndexOutOfBoundsException("Subclasses must override readTo");
  }

  @Override
  public void readToByteBuffer(ByteBuffer dst, int length) {
    throw new IndexOutOfBoundsException("Subclasses must override readToByteBuffer");
  }

  @Override
  public int readToByteBuffer(ByteBuffer dst) {
    throw new IndexOutOfBoundsException("Subclasses must override readToByteBuffer");
  }

  @Override
  public MemoryBuffer getBuffer() {
    return null;
  }
}
