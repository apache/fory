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
import org.apache.fory.json.writer.StringJsonWriter;

/**
 * Child-owned object-member representation used to compose an object around that child's members.
 *
 * <p>This refinement is independent of the composing parent: it receives no discriminator, base
 * type, subtype index, or reserved name. The child still owns field access, omission, property
 * order, Any placement, and recursive complete-value writes, so moving member generation into a
 * parent codec would duplicate those child semantics. Implementations also remain complete {@link
 * StringWriterCodec} instances for nested and ordinary child values.
 *
 * <p>The caller has already written the opening brace and any preceding members. Implementations
 * write only ordinary object members: they must not write null for the object, braces, or depth.
 */
@Internal
public interface StringObjectWriter<T> extends StringWriterCodec<T> {
  /** Writes object members after {@code written} members have already been emitted. */
  void writeStringMembers(StringJsonWriter writer, T value, int written);
}
