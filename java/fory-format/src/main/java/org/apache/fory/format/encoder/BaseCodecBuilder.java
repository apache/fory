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
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;

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
   * <p>This flag is part of the wire contract: producers and consumers must agree on it, and it
   * cannot be flipped on an existing dataset without rewriting. For array and map codecs it adds an
   * 8-byte strict-hash prefix to the payload. Because those codecs have no header otherwise, an
   * evolution-off reader has no way to tell that prefix from valid body bytes, so reading
   * evolution-on bytes with evolution off (or the reverse) mis-decodes silently rather than
   * failing. Row payloads already carry an 8-byte hash slot, so a flag mismatch there is detected
   * and rejected; but the slot is computed with a stricter hash under evolution (it also
   * distinguishes field names and nullability), so flipping the flag rejects reads of previously
   * written rows even when the field layout is byte-identical.
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
    SchemaHistory history = SchemaHistory.build(targetClass, schemaTransform());
    warnIfManyProjections(targetClass.getName(), history);
    return history;
  }

  /**
   * History of a top-level array element or map entry field, enumerated over the cross-product of
   * every versioned bean reachable through the field's list/map/array wrappers (a directly-typed
   * bean, a list element, or a map key or value). Used by the array/map codecs so the element
   * schema's strict hash identifies all nested layouts jointly, letting a wrapper that reaches more
   * than one distinct versioned bean class evolve every one of them.
   */
  protected SchemaHistory buildElementSchemaHistory(
      final String fieldName, final TypeRef<?> elementType) {
    SchemaHistory history = elementSchemaHistory(fieldName, elementType);
    warnIfManyProjections("element " + elementType, history);
    return history;
  }

  /**
   * Builds a position's history without the per-position projection-count warning. The map path
   * uses this and warns once on the key/value cross-product, which is the count of classes it
   * generates; the array path uses {@link #buildElementSchemaHistory}, whose per-position count is
   * exact.
   */
  protected SchemaHistory elementSchemaHistory(
      final String fieldName, final TypeRef<?> elementType) {
    return SchemaHistory.forElement(fieldName, elementType, schemaTransform());
  }

  private UnaryOperator<Schema> schemaTransform() {
    return codecFormat == CompactCodecFormat.INSTANCE
        ? CompactBinaryRowWriter::sortSchema
        : UnaryOperator.identity();
  }

  private static void warnIfManyProjections(final String label, final SchemaHistory history) {
    warnIfManyProjections(label, history.versions().size());
  }

  /**
   * Warn when {@code projectionCount} generated projection codec classes exceeds the threshold. The
   * map path passes the key/value cross-product here, not a single position's count, because that
   * product is the number of classes it actually generates.
   */
  protected static void warnIfManyProjections(final String label, final int projectionCount) {
    if (projectionCount > PROJECTION_COUNT_WARN_THRESHOLD) {
      LOG.warn(
          "Schema evolution for {} resolved {} historical schemas, each generating a projection "
              + "codec class. This count grows as the product of per-class version counts across "
              + "nested versioned beans; retire @ForyVersion history ranges you no longer read to "
              + "reduce it.",
          label,
          projectionCount);
    }
  }

  @SuppressWarnings("unchecked")
  protected B castThis() {
    return (B) this;
  }
}
