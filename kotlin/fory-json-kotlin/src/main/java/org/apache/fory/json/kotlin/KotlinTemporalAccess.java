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

package org.apache.fory.json.kotlin;

import kotlin.uuid.Uuid;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;

/**
 * Java source access to Kotlin-internal getters and inline carriers not expressible without boxing.
 */
@Internal
final class KotlinTemporalAccess {
  private KotlinTemporalAccess() {}

  static long uuidHigh(Uuid value) {
    return value.getMostSignificantBits();
  }

  static long uuidLow(Uuid value) {
    return value.getLeastSignificantBits();
  }

  static Object readDurationCarrier(JsonReader reader) {
    return Long.valueOf(KotlinTemporalCodecs.readDurationRaw(reader));
  }

  static void writeDurationCarrier(JsonWriter writer, Object carrier) {
    KotlinTemporalCodecs.writeDurationRaw(writer, ((Long) carrier).longValue());
  }
}
