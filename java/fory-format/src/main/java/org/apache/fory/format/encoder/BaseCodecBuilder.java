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

import java.util.function.UnaryOperator;
import org.apache.fory.Fory;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;

public class BaseCodecBuilder<B extends BaseCodecBuilder<B>> {
  private static final Logger LOG = LoggerFactory.getLogger(BaseCodecBuilder.class);

  /**
   * Number of historical schemas for one bean above which {@link #buildSchemaHistory} logs a
   * warning. Each distinct schema becomes one generated projection codec class (compiled and loaded
   * at build time), and the count grows as the product of the per-class version counts across
   * nested versioned beans. The JVM handles far more classes than this; the threshold flags a
   * likely misconfigured version history, since no hand-written history reaches it by accident.
   */
  private static final int PROJECTION_COUNT_WARN_THRESHOLD = 256;

  protected Schema schema;
  protected int initialBufferSize = 16;
  protected boolean sizeEmbedded = true;
  protected Fory fory;
  protected Encoding codecFormat = DefaultCodecFormat.INSTANCE;
  protected boolean schemaEvolution = false;

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
   * Enable schema evolution. The codec accepts payloads written by older versions of the same bean,
   * using the {@link org.apache.fory.format.annotation.ForyVersion} and {@link
   * org.apache.fory.format.annotation.ForySchema} annotations to reconstruct historical schemas.
   * Writing always uses the current version.
   *
   * <p>For array and map codecs, this changes the wire format by adding an 8-byte strict-hash
   * prefix to the payload, so producers and consumers must agree on the flag. Row payloads already
   * carry an 8-byte hash slot; under schema evolution that slot is computed with a stricter hash
   * that also distinguishes field names and nullability.
   */
  public B withSchemaEvolution() {
    this.schemaEvolution = true;
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

  /**
   * Build the schema history for {@code targetClass} under the active codec format. The compact
   * format sorts schema fields, so historical schemas must be sorted the same way for their strict
   * hashes and layouts to match what the writer produces; the default format passes schemas through
   * unchanged.
   */
  protected SchemaHistory buildSchemaHistory(final Class<?> targetClass) {
    UnaryOperator<Schema> schemaTransform =
        codecFormat == CompactCodecFormat.INSTANCE
            ? CompactBinaryRowWriter::sortSchema
            : UnaryOperator.identity();
    SchemaHistory history = SchemaHistory.build(targetClass, schemaTransform);
    int projectionCount = history.versions().size();
    if (projectionCount > PROJECTION_COUNT_WARN_THRESHOLD) {
      LOG.warn(
          "Schema evolution for {} resolved {} historical schemas, each generating a projection "
              + "codec class. This count grows as the product of per-class version counts across "
              + "nested versioned beans; retire @ForyVersion history ranges you no longer read to "
              + "reduce it.",
          targetClass.getName(),
          projectionCount);
    }
    return history;
  }

  @SuppressWarnings("unchecked")
  protected B castThis() {
    return (B) this;
  }
}
