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

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import org.apache.fory.config.Config;
import org.apache.fory.config.Int64Encoding;
import org.apache.fory.context.ReadContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.PrimitiveSerializers.LongSerializer;
import org.apache.fory.util.Preconditions;

/** ObjectInput based on {@link MemoryBuffer}. */
public class MemoryBufferObjectInput extends InputStream implements ObjectInput {
  private final boolean compressInt;
  private final Int64Encoding longEncoding;
  private MemoryBuffer buffer;
  private ReadContext readContext;

  public MemoryBufferObjectInput(Config config, ReadContext readContext) {
    this.compressInt = config.compressInt();
    this.longEncoding = config.longEncoding();
    this.readContext = readContext;
    if (readContext != null) {
      this.buffer = readContext.getBuffer();
    }
  }

  public void setBuffer(MemoryBuffer buffer) {
    this.buffer = buffer;
  }

  public void setReadContext(ReadContext readContext) {
    this.readContext = readContext;
    this.buffer = readContext.getBuffer();
  }

  public void clearReadContext() {
    this.readContext = null;
    this.buffer = null;
  }

  @Override
  public Object readObject() throws ClassNotFoundException, IOException {
    return readContext.readRef();
  }

  @Override
  public int read() throws IOException {
    return buffer.readByte();
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int available = available();
    if (available == 0) {
      return -1;
    }

    len = Math.min(available, len);
    buffer.readBytes(b, off, len);
    return len;
  }

  @Override
  public long skip(long n) throws IOException {
    Preconditions.checkArgument(n < Integer.MAX_VALUE);
    int newIndex = Math.addExact(buffer.readerIndex(), (int) n);
    Preconditions.checkArgument(newIndex < buffer.size());
    buffer.readerIndex(newIndex);
    return n;
  }

  @Override
  public int available() throws IOException {
    return buffer.size() - buffer.readerIndex();
  }

  @Override
  public void close() throws IOException {}

  @Override
  public void readFully(byte[] b) throws IOException {
    readFully(b, 0, b.length);
  }

  @Override
  public void readFully(byte[] b, int off, int len) throws IOException {
    buffer.readBytes(b, off, len);
  }

  @Override
  public int skipBytes(int n) throws IOException {
    buffer.readerIndex(buffer.readerIndex() + n);
    return n;
  }

  @Override
  public boolean readBoolean() throws IOException {
    return buffer.readBoolean();
  }

  @Override
  public byte readByte() throws IOException {
    return buffer.readByte();
  }

  @Override
  public int readUnsignedByte() throws IOException {
    return buffer.readByte() & 0xFF;
  }

  @Override
  public short readShort() throws IOException {
    return buffer.readInt16();
  }

  @Override
  public int readUnsignedShort() throws IOException {
    return buffer.readInt16() & 0xffff;
  }

  @Override
  public char readChar() throws IOException {
    return buffer.readChar();
  }

  @Override
  public int readInt() throws IOException {
    return compressInt ? buffer.readVarInt32() : buffer.readInt32();
  }

  @Override
  public long readLong() throws IOException {
    return LongSerializer.readInt64(buffer, longEncoding);
  }

  @Override
  public float readFloat() throws IOException {
    return buffer.readFloat32();
  }

  @Override
  public double readDouble() throws IOException {
    return buffer.readFloat64();
  }

  @Override
  public String readLine() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String readUTF() throws IOException {
    return readContext.getStringSerializer().readString(buffer);
  }
}
