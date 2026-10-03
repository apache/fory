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
import org.apache.fory.json.annotation.JsonProperty.NullHandling;

/**
 * A codec that owns JSON array elements or object-map values.
 *
 * <p>This cold capability lets language modules select an occurrence's immediate content handling
 * without wrapping the codec, changing its child codecs, or mutating a shared type binding.
 */
@Internal
public interface ContainerJsonCodec<T> extends JsonValueCodec<T> {
  /**
   * Returns a codec for the same representation and children with the requested content handling.
   */
  ContainerJsonCodec<?> withContentNullRead(NullHandling handling);
}
