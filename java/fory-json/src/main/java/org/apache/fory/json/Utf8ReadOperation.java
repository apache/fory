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

package org.apache.fory.json;

import org.apache.fory.json.reader.Utf8JsonReader;

/**
 * Reads one JSON value with a {@link Utf8JsonReader} lent by {@link ForyJson#readUtf8(byte[], int,
 * int, Utf8ReadOperation)}.
 *
 * @param <R> the result type
 * @param <E> the checked exception the operation may throw
 */
@FunctionalInterface
public interface Utf8ReadOperation<R, E extends Throwable> {
  /**
   * Consumes exactly one JSON value from {@code reader}. The reader is only valid during this call.
   */
  R read(Utf8JsonReader reader) throws E;
}
