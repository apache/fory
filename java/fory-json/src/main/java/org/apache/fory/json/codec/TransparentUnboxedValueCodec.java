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

import java.lang.reflect.Method;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;

/**
 * Exact terminal conversion for a logical value transparent to one underlying JSON type.
 *
 * <p>Instances supplied by direct exact registration are keyed by implementation class and must
 * therefore expose the same terminal type, generated operations, and graph charges. A factory which
 * varies them must represent that difference in {@code JsonCodecFactory.factoryKey()}.
 */
@Internal
public interface TransparentUnboxedValueCodec extends UnboxedValueCodec {
  /** Returns the already-bound terminal value type. */
  JsonTypeInfo valueTypeInfo();

  /** Constructs the parent carrier from one decoded terminal value, charging intermediate boxes. */
  Object constructCarrier(JsonReader reader, Object value);

  /** Extracts the terminal value from one parent carrier. */
  Object extractValue(Object carrier);

  /** Returns exact terminal-to-carrier methods in invocation order. */
  Method[] constructMethods();

  /** Returns graph charges aligned with {@link #constructMethods()}; zero means no allocation. */
  int[] constructBoxBytes();

  /** Returns exact carrier-to-terminal methods in invocation order. */
  Method[] extractMethods();
}
