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

package org.apache.fory.format.encoder;

import org.apache.fory.Fory;
import org.apache.fory.collection.LongMap;

/**
 * One encoder's projection codecs, keyed by the strict hash of their historical layout and compiled
 * on the first decode of each hash. It needs no locking because an encoder is single-threaded; the
 * shared {@link ProjectionSource}s generate each class once across encoders.
 */
final class ProjectionCache<P> {
  private final LongMap<? extends ProjectionSource<?, P>> sources;
  private final LongMap<P> compiled = new LongMap<>();
  private final Fory fory;

  /**
   * {@code sources} is shared, immutable, and built without compiling anything. It can be empty
   * when the codec has evolution on but no older layout.
   */
  ProjectionCache(final LongMap<? extends ProjectionSource<?, P>> sources, final Fory fory) {
    this.sources = sources;
    this.fory = fory;
  }

  /** The projection codec for {@code hash}, or {@code null} if no older layout has that hash. */
  P get(final long hash) {
    P projection = compiled.get(hash);
    if (projection != null) {
      return projection;
    }
    ProjectionSource<?, P> source = sources.get(hash);
    if (source == null) {
      return null;
    }
    projection = source.compile(fory);
    compiled.put(hash, projection);
    return projection;
  }
}
