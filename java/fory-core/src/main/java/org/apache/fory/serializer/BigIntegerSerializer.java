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

package org.apache.fory.serializer;

import java.math.BigInteger;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;

/** Serializer for {@link BigInteger} in native and xlang modes. */
public final class BigIntegerSerializer extends ImmutableSerializer<BigInteger>
    implements Shareable {
  private final boolean xlang;

  public BigIntegerSerializer(Config config) {
    super(config, BigInteger.class);
    xlang = config.isXlang();
  }

  @Override
  public void write(WriteContext writeContext, BigInteger value) {
    if (xlang) {
      writeXlang(writeContext, value);
    } else {
      writeNative(writeContext, value);
    }
  }

  @Override
  public BigInteger read(ReadContext readContext) {
    if (xlang) {
      return readXlang(readContext);
    }
    return readNative(readContext);
  }

  private void writeNative(WriteContext writeContext, BigInteger value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    byte[] bytes = value.toByteArray();
    buffer.writeVarUint32Small7(bytes.length);
    buffer.writeBytes(bytes);
  }

  private BigInteger readNative(ReadContext readContext) {
    MemoryBuffer buffer = readContext.getBuffer();
    int len = buffer.readVarUint32Small7();
    byte[] bytes = buffer.readBytes(len);
    return new BigInteger(bytes);
  }

  private void writeXlang(WriteContext writeContext, BigInteger value) {
    DecimalSerializer.writeXlangDecimal(writeContext.getBuffer(), 0, value);
  }

  private BigInteger readXlang(ReadContext readContext) {
    return DecimalSerializer.readXlangBigInteger(readContext.getBuffer());
  }
}
