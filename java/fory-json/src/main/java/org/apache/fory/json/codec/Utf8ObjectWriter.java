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
import org.apache.fory.json.writer.Utf8JsonWriter;

/** UTF-8 object-member refinement used by closed inline subtype writers. */
@Internal
public interface Utf8ObjectWriter<T> extends Utf8WriterCodec<T> {
  /** Writes object members after {@code written} members have already been emitted. */
  void writeUtf8Members(Utf8JsonWriter writer, T value, int written);
}
