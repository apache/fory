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

package org.apache.fory.json.writer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Objects;
import java.util.UUID;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.JsonConfig;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;

public abstract class JsonWriter {
  private final JsonTypeResolver typeResolver;
  private final boolean writeNullFields;
  private final int maxDepth;
  private int depth;

  JsonWriter(JsonConfig config, JsonTypeResolver typeResolver) {
    this.typeResolver = Objects.requireNonNull(typeResolver, "typeResolver");
    writeNullFields = config.writeNullFields();
    maxDepth = config.maxDepth();
  }

  /**
   * Returns the resolver owned by this writer for custom codecs that resolve dynamic child types.
   */
  public final JsonTypeResolver typeResolver() {
    return typeResolver;
  }

  public final boolean writeNullFields() {
    return writeNullFields;
  }

  public void reset() {
    depth = 0;
  }

  protected final void enterDepth() {
    int nextDepth = depth + 1;
    if (nextDepth > maxDepth) {
      throwDepthExceeded(maxDepth);
    }
    depth = nextDepth;
  }

  protected final void exitDepth() {
    depth--;
  }

  private static void throwDepthExceeded(int maxDepth) {
    throw new ForyJsonException("JSON max depth " + maxDepth + " exceeded");
  }

  public abstract void writeNull();

  public abstract void writeBoolean(boolean value);

  public abstract void writeInt(int value);

  public abstract void writeLong(long value);

  public abstract void writeFloat(float value);

  public abstract void writeDouble(double value);

  public abstract void writeNumber(String value);

  public abstract void writeChar(char value);

  public abstract void writeString(String value);

  public void writeString(CharSequence value) {
    writeString(value.toString());
  }

  // Concrete writers own compact BigDecimal formatting, BigInteger chunk output, and canonical
  // arbitrary-precision text copying in their byte or compact-string representation.
  public abstract void writeBigInteger(BigInteger value);

  public abstract void writeBigDecimal(BigDecimal value);

  protected static void throwUnsupportedBigNumber(Class<?> type) {
    throw new ForyJsonException(
        "Unsupported JSON big-number subtype " + type + "; register an explicit codec");
  }

  public void writeUuid(UUID value) {
    writeString(value.toString());
  }

  public void writeLocalDate(LocalDate value) {
    writeString(value.toString());
  }

  public void writeOffsetDateTime(OffsetDateTime value) {
    writeString(value.toString());
  }

  public void writeTemporal(TemporalAccessor value, DateTimeFormatter formatter) {
    writeString(formatter.format(value));
  }

  public void writeDuration(Duration value) {
    writeString(value.toString());
  }

  public void writePeriod(Period value) {
    writeString(value.toString());
  }

  public void writeYear(Year value) {
    writeString(value.toString());
  }

  public abstract void writeFieldName(String name);

  public abstract void writeFieldName(JsonFieldInfo field);

  public abstract void writeIntFieldName(int value);

  public abstract void writeLongFieldName(long value);

  public abstract void writeObjectStart();

  public abstract void writeObjectEnd();

  public abstract void writeArrayStart();

  public abstract void writeArrayEnd();

  public abstract void writeComma(int index);
}
