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

package org.apache.fory.json.codec;

import org.apache.fory.annotation.Internal;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Types;

/**
 * Cold-bound operations for a logical value whose parent JVM member stores an unboxed carrier.
 *
 * <p>This capability is deliberately separate from {@link JsonValueCodec}. The logical codec owns
 * the semantic type and its recursive child lifecycle; an object field or creator argument may
 * select this capability only after resolving that canonical logical codec. Interpreted object
 * codecs use the representation-specific carrier methods below, where primitive boxing is already
 * inherent in their argument workspace. Generated codecs cold-select a specialized subtype and
 * never call these object-valued methods.
 */
@Internal
public interface UnboxedValueCodec {
  /** Returns whether this occurrence requires an exact phase-two carrier operation. */
  static boolean requiresCarrier(Class<?> carrier, TypeRef<?> logicalType) {
    if (carrier == null) {
      return false;
    }
    Class<?> logicalClass = logicalType.getRawType();
    TypeExtMeta metadata = logicalType.getTypeExtMeta();
    if (carrier == logicalClass) {
      // A primitive semantic leaf can share its JVM carrier with an ordinary primitive while
      // requiring different JSON parsing and formatting. Bind its canonical operation before
      // generated code specializes the field by carrier kind.
      return carrier.isPrimitive() && metadata != null && metadata.typeId() != Types.UNKNOWN;
    }
    return metadata != null;
  }

  /** Returns the exact JVM carrier stored by the parent member. */
  Class<?> carrierType();

  /** Reads one Latin-1 JSON value directly into the parent carrier. */
  Object readLatin1Carrier(Latin1JsonReader reader);

  /** Reads one UTF-16 JSON value directly into the parent carrier. */
  Object readUtf16Carrier(Utf16JsonReader reader);

  /** Reads one UTF-8 JSON value directly into the parent carrier. */
  Object readUtf8Carrier(Utf8JsonReader reader);

  /** Writes one parent carrier to the String JSON representation. */
  void writeStringCarrier(StringJsonWriter writer, Object carrier);

  /** Writes one parent carrier to the UTF-8 JSON representation. */
  void writeUtf8Carrier(Utf8JsonWriter writer, Object carrier);
}
