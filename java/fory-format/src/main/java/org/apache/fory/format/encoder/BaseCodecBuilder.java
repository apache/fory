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

package org.apache.fory.format.encoder;

import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.Fory;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;

public class BaseCodecBuilder<B extends BaseCodecBuilder<B>> {
  protected Schema schema;
  protected int initialBufferSize = 16;
  protected boolean sizeEmbedded = true;
  protected Fory fory;
  protected Encoding codecFormat = DefaultCodecFormat.INSTANCE;

  BaseCodecBuilder(final Schema schema) {
    this.schema = schema;
  }

  /** Configure the Fory instance used for embedded binary serialized objects. */
  public B fory(final Fory fory) {
    this.fory = fory;
    return castThis();
  }

  /** Configure the initial buffer size used when writing a new row. */
  public B initialBufferSize(final int initialBufferSize) {
    this.initialBufferSize = initialBufferSize;
    return castThis();
  }

  /**
   * Configure whether the encoder expects the size encoded inline in the data, or provided by
   * external framing. When true, the default, the encoder will write the size as part of its data,
   * to be read by the decoder. When false, the data size must be preserved by external framing you
   * provide.
   */
  public B withSizeEmbedded(final boolean sizeEmbedded) {
    this.sizeEmbedded = sizeEmbedded;
    return castThis();
  }

  /**
   * Configure compact encoding, which is more space efficient than the default encoding, but is not
   * yet stable. See {@link CompactBinaryRow} for details.
   */
  public B compactEncoding() {
    schema = CompactBinaryRowWriter.sortSchema(schema);
    codecFormat = CompactCodecFormat.INSTANCE;
    return castThis();
  }

  @SuppressWarnings("unchecked")
  protected B castThis() {
    return (B) this;
  }
}
