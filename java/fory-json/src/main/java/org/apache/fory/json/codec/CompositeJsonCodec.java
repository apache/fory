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
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.reflect.TypeRef;

/**
 * A codec whose child type metadata is bound after its own metadata has been published.
 *
 * <p>This lifecycle belongs only to composite codecs. Keep {@link JsonValueCodec} free of resolver
 * binding so leaf codecs do not inherit a meaningless cold-path capability.
 */
@Internal
public interface CompositeJsonCodec<T> extends JsonValueCodec<T> {
  /** Binds child type metadata during the resolver-owned cold resolution transaction. */
  void resolveTypes(TypeRef<?> type, JsonTypeResolver resolver);

  /** Binds explicitly configured direct child codecs during the same cold transaction. */
  default void resolveTypes(TypeRef<?> type, JsonTypeResolver resolver, JsonCodec childCodecs) {
    throw new ForyJsonException("@JsonCodec child slots are unsupported for " + type.getType());
  }
}
