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

/**
 * Exact parent-carrier operations for a semantic leaf which is not transparent to its carrier.
 *
 * <p>Instances supplied by direct exact registration are keyed by implementation class and must
 * therefore expose the same generated operations. A factory which varies these operations must
 * represent that difference in {@code JsonCodecFactory.factoryKey()}.
 */
@Internal
public interface DirectUnboxedValueCodec extends UnboxedValueCodec {
  /** Returns the exact static {@code (JsonReader) -> carrier} generated invocation. */
  Method readCarrierMethod();

  /** Returns the exact static {@code (JsonWriter, carrier) -> void} generated invocation. */
  Method writeCarrierMethod();
}
