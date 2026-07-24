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

/**
 * Owns the single interface-invocation bytecode used by large generated UTF-8 writer receivers.
 *
 * <p>This helper is deliberately not a general codec dispatcher. Generated object bodies and member
 * groups store their completed receiver in a final interface-typed field and call this class.
 * Consequently, every qualifying generated receiver contributes to the type profile of the same
 * {@code invokeinterface} bytecode index instead of creating one profile per generated class. That
 * real polymorphic profile prevents an outer generated collection or object from absorbing a
 * receiver's complete transitive field closure before the receiver has formed its own level-4
 * nmethod.
 *
 * <p>The static helper itself is intentionally tiny and may inline into generated callers. The
 * boundary comes from the shared interface-call profile that remains after helper inlining, not
 * from making this method large. Generated receivers own the real work and any naturally large
 * method; this class owns only their common interface-call bytecode index.
 *
 * <p>Do not duplicate this helper per generated type, replace the interface field with a concrete
 * receiver, or move root/handwritten codec calls through it. Root startup codecs execute before
 * generated capabilities are published and would dominate the shared profile; concrete receiver
 * types would let C2 recover and inline the exact implementation. Do not add type resolution,
 * lifecycle state, allocation, callbacks, or fallback logic here. Those concerns belong to the
 * resolver or the generated receiver, while this class owns only the shared invocation bytecode.
 */
@Internal
public final class JsonTrampolineInvoke {
  private JsonTrampolineInvoke() {}

  /**
   * Invokes generated UTF-8 object bodies and field groups through one interface-call BCI.
   *
   * <p>The interface invocation below must remain the only operation and the only {@code
   * invokeinterface} bytecode in this method. The static helper call may itself be inlined, but
   * HotSpot still consumes the one method-data profile owned by this bytecode index. The receivers
   * must be real, fully constructed generated capabilities published only after construction; never
   * add synthetic receivers merely to force polymorphism.
   */
  public static void writeUtf8(Utf8WriterCodec<Object> codec, Utf8JsonWriter writer, Object value) {
    codec.writeUtf8(writer, value);
  }
}
